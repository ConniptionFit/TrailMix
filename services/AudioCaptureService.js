const fs = require('fs');
const path = require('path');
const { spawn, execSync } = require('child_process');
const { computeStereoLevelsAsync } = require('../lib/audio-levels');

/**
 * Detect whether PipeWire is managing audio (informational only).
 * ffmpeg capture always uses the PulseAudio compatibility layer (`-f pulse`)
 * because most distro ffmpeg builds lack a native PipeWire demuxer.
 */
function detectAudioBackend() {
  try {
    const pulseInfo = execSync('pactl info 2>/dev/null', { encoding: 'utf8' });
    if (/PipeWire/i.test(pulseInfo)) {
      return 'pipewire-pulse';
    }
  } catch (err) {
    // Fall through.
  }

  try {
    execSync('wpctl status 2>/dev/null', { encoding: 'utf8' });
    return 'pipewire-pulse';
  } catch (err) {
    return 'pulse';
  }
}

function ffmpegSupportsPipewireInput() {
  try {
    const help = execSync('ffmpeg -hide_banner -formats 2>/dev/null', { encoding: 'utf8' });
    return /\s+E\s+.*pipewire/i.test(help) || /\s+D\s+.*pipewire/i.test(help);
  } catch (err) {
    return false;
  }
}

class AudioCaptureService {
  constructor({ tempDir }) {
    this.tempDir = tempDir;
    this.recordingProcess = null;
    this.chunkPollTimer = null;
    this.levelPollTimer = null;
    this.processedChunks = new Set();
    this.onChunkReady = null;
    this.onError = null;
    this.onLevels = null;
    this.audioBackend = detectAudioBackend();
    this.latestLevels = { left: 0, right: 0, combined: 0 };
  }

  configure({ onChunkReady, onError, onLevels }) {
    this.onChunkReady = onChunkReady;
    this.onError = onError;
    this.onLevels = onLevels;
  }

  getAudioBackend() {
    return this.audioBackend;
  }

  async prepareTempDir(reset = true) {
    if (reset && fs.existsSync(this.tempDir)) {
      await fs.promises.rm(this.tempDir, { recursive: true, force: true });
    }
    await fs.promises.mkdir(this.tempDir, { recursive: true });
    this.processedChunks.clear();
  }

  buildFfmpegInputFormat() {
    // PipeWire systems expose devices through the PulseAudio API; do not load
    // module-loopback (causes mic bleed to speakers) or use -f pipewire unless
    // this ffmpeg build explicitly supports it.
    if (this.audioBackend.startsWith('pipewire') && ffmpegSupportsPipewireInput()) {
      return 'pipewire';
    }
    return 'pulse';
  }

  async start({ sinkMonitor, source }) {
    await this.stopFfmpeg();
    await this.prepareTempDir(true);

    const inputFormat = this.buildFfmpegInputFormat();
    const ffmpegArgs = [
      '-y',
      '-f', inputFormat, '-i', sinkMonitor,
      '-f', inputFormat, '-i', source,
      '-filter_complex', '[0:a]pan=mono|c0=c0[left]; [1:a]pan=mono|c0=c0[right]; [left][right]amerge=inputs=2[a]',
      '-map', '[a]',
      '-f', 'segment',
      '-segment_time', '2',
      '-segment_format', 'wav',
      '-c:a', 'pcm_s16le',
      '-ar', '16000',
      '-ac', '2',
      path.join(this.tempDir, 'chunk_%03d.wav')
    ];

    console.log(`Audio capture: backend=${this.audioBackend}, ffmpeg format=${inputFormat}`);

    this.recordingProcess = spawn('ffmpeg', ffmpegArgs);

    this.recordingProcess.stderr.on('data', (chunk) => {
      const text = chunk.toString();
      if (/error/i.test(text)) {
        console.error('ffmpeg:', text.trim());
      }
    });

    this.recordingProcess.on('error', (err) => {
      console.error('Ffmpeg recording error', err);
      if (this.onError) this.onError(err);
    });

    this.recordingProcess.on('close', (code) => {
      if (code !== 0 && code !== null && code !== 255) {
        console.error(`ffmpeg exited with code ${code}`);
        if (this.onError) {
          this.onError(new Error(`ffmpeg recording failed (exit ${code})`));
        }
      }
    });

    this.startChunkPolling();
    this.startLevelPolling();
  }

  async resumeCapture() {
    await this.prepareTempDir(true);
  }

  startChunkPolling() {
    this.stopChunkPolling();

    this.chunkPollTimer = setInterval(() => {
      this.pollForCompletedChunks().catch((err) => {
        console.error('Chunk polling failed', err);
      });
    }, 1000);
  }

  startLevelPolling() {
    this.stopLevelPolling();
    this._levelPollInFlight = false;

    // 250ms is enough for UI meters and halves main-thread I/O vs 120ms.
    this.levelPollTimer = setInterval(() => {
      if (this._levelPollInFlight) return;
      this._levelPollInFlight = true;
      this.pollAudioLevels()
        .catch((err) => {
          console.error('Level polling failed', err);
        })
        .finally(() => {
          this._levelPollInFlight = false;
        });
    }, 250);
  }

  stopChunkPolling() {
    if (this.chunkPollTimer) {
      clearInterval(this.chunkPollTimer);
      this.chunkPollTimer = null;
    }
  }

  stopLevelPolling() {
    if (this.levelPollTimer) {
      clearInterval(this.levelPollTimer);
      this.levelPollTimer = null;
    }
  }

  async pollAudioLevels() {
    if (!fs.existsSync(this.tempDir)) return;

    const files = await fs.promises.readdir(this.tempDir);
    const wavChunks = files
      .filter((file) => this.isChunkCandidate(file))
      .sort();

    if (wavChunks.length === 0) return;

    const latestChunk = wavChunks[wavChunks.length - 1];
    const chunkPath = path.join(this.tempDir, latestChunk);

    try {
      const stats = await fs.promises.stat(chunkPath);
      if (stats.size < 1024) return;

      const levels = await computeStereoLevelsAsync(chunkPath);
      this.latestLevels = levels;

      if (this.onLevels) {
        this.onLevels({
          left: levels.left,
          right: levels.right,
          combined: levels.combined,
          backend: this.audioBackend
        });
      }
    } catch (err) {
      // Chunk may still be writing.
    }
  }

  isChunkCandidate(fileName) {
    return fileName.startsWith('chunk_')
      && fileName.endsWith('.wav')
      && !fileName.includes('_mono')
      && !fileName.includes('_left')
      && !fileName.includes('_right')
      && !fileName.includes('_trans');
  }

  async pollForCompletedChunks(includeLastChunk = false) {
    if (!fs.existsSync(this.tempDir)) return [];

    const files = await fs.promises.readdir(this.tempDir);
    const wavChunks = files.filter((file) => this.isChunkCandidate(file)).sort();
    const readyChunks = includeLastChunk ? wavChunks : wavChunks.slice(0, -1);
    const newlyReady = [];

    for (const chunkFile of readyChunks) {
      const chunkPath = path.join(this.tempDir, chunkFile);
      if (this.processedChunks.has(chunkPath)) continue;

      this.processedChunks.add(chunkPath);
      const chunkIndex = parseInt(chunkFile.match(/\d+/)[0], 10);
      newlyReady.push({ chunkPath, chunkIndex });

      if (this.onChunkReady) {
        this.onChunkReady({ chunkPath, chunkIndex });
      }
    }

    return newlyReady;
  }

  async flushRemainingChunks() {
    return this.pollForCompletedChunks(true);
  }

  async countChunkCandidates() {
    if (!fs.existsSync(this.tempDir)) return 0;
    const files = await fs.promises.readdir(this.tempDir);
    return files.filter((file) => this.isChunkCandidate(file)).length;
  }

  async stopFfmpeg() {
    this.stopChunkPolling();
    this.stopLevelPolling();

    if (!this.recordingProcess) return;

    const processRef = this.recordingProcess;
    this.recordingProcess = null;

    await new Promise((resolve) => {
      let settled = false;
      const finish = () => {
        if (settled) return;
        settled = true;
        resolve();
      };

      processRef.once('close', finish);

      try {
        processRef.stdin.write('q');
      } catch (err) {
        try {
          processRef.kill('SIGINT');
        } catch (killErr) {
          try {
            processRef.kill('SIGKILL');
          } catch (forceErr) {
            // Process already exited.
          }
        }
      }

      setTimeout(() => {
        try {
          if (!processRef.killed) processRef.kill('SIGKILL');
        } catch (err) {
          // Ignore.
        }
        finish();
      }, 2000);
    });
  }

  getProcessedChunkCount() {
    return this.processedChunks.size;
  }

  hasActiveProcess() {
    return Boolean(this.recordingProcess);
  }

  resetProcessedChunks() {
    this.processedChunks.clear();
  }
}

module.exports = {
  AudioCaptureService,
  detectAudioBackend,
  ffmpegSupportsPipewireInput
};
