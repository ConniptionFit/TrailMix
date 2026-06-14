const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

class AudioCaptureService {
  constructor({ tempDir }) {
    this.tempDir = tempDir;
    this.recordingProcess = null;
    this.chunkPollTimer = null;
    this.processedChunks = new Set();
    this.onChunkReady = null;
    this.onError = null;
  }

  configure({ onChunkReady, onError }) {
    this.onChunkReady = onChunkReady;
    this.onError = onError;
  }

  async prepareTempDir(reset = true) {
    if (reset && fs.existsSync(this.tempDir)) {
      await fs.promises.rm(this.tempDir, { recursive: true, force: true });
    }
    await fs.promises.mkdir(this.tempDir, { recursive: true });
    this.processedChunks.clear();
  }

  async start({ sinkMonitor, source }) {
    await this.prepareTempDir(true);

    const ffmpegArgs = [
      '-y',
      '-f', 'pulse', '-i', sinkMonitor,
      '-f', 'pulse', '-i', source,
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

    this.recordingProcess = spawn('ffmpeg', ffmpegArgs);

    this.recordingProcess.on('error', (err) => {
      console.error('Ffmpeg recording error', err);
      if (this.onError) this.onError(err);
    });

    this.startChunkPolling();
  }

  async resumeCapture() {
    await this.prepareTempDir(true);
    // Caller must re-spawn ffmpeg through start(); resume only resets temp state.
  }

  startChunkPolling() {
    this.stopChunkPolling();

    this.chunkPollTimer = setInterval(() => {
      this.pollForCompletedChunks().catch((err) => {
        console.error('Chunk polling failed', err);
      });
    }, 1000);
  }

  stopChunkPolling() {
    if (this.chunkPollTimer) {
      clearInterval(this.chunkPollTimer);
      this.chunkPollTimer = null;
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

    if (!this.recordingProcess) return;

    const processRef = this.recordingProcess;
    this.recordingProcess = null;

    await new Promise((resolve) => {
      processRef.once('close', resolve);

      try {
        processRef.stdin.write('q');
      } catch (err) {
        try {
          processRef.kill('SIGINT');
        } catch (killErr) {
          processRef.kill('SIGKILL');
        }
      }

      setTimeout(resolve, 1500);
    });
  }

  getProcessedChunkCount() {
    return this.processedChunks.size;
  }

  resetProcessedChunks() {
    this.processedChunks.clear();
  }
}

module.exports = {
  AudioCaptureService
};
