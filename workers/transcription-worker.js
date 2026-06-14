const { parentPort } = require('worker_threads');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile } = require('child_process');
const { promisify } = require('util');

const execFileAsync = promisify(execFile);
const { checkVoiceActivity } = require('../lib/audio-vad');
const { secureShredFile } = require('../lib/secure-shred');

async function splitStereoChannels(chunkWavPath, leftWavPath, rightWavPath) {
  await execFileAsync('ffmpeg', [
    '-y', '-i', chunkWavPath,
    '-af', 'pan=mono|c0=c0',
    leftWavPath
  ]);

  await execFileAsync('ffmpeg', [
    '-y', '-i', chunkWavPath,
    '-af', 'pan=mono|c0=c1',
    rightWavPath
  ]);
}

async function transcribeMonoFile(monoWavPath, speakerName, whisperCli, whisperModel) {
  const outputBase = monoWavPath.replace('.wav', '_trans');
  const jsonPath = `${outputBase}.json`;
  const threads = Math.min(8, Math.max(4, os.cpus().length - 2));

  await execFileAsync(whisperCli, [
    '-m', whisperModel,
    '-f', monoWavPath,
    '-t', String(threads),
    '-oj',
    '-of', outputBase
  ], { maxBuffer: 10 * 1024 * 1024 });

  if (!fs.existsSync(jsonPath)) {
    console.error(`Whisper JSON file not found for ${speakerName}`, jsonPath);
    return [];
  }

  try {
    const data = JSON.parse(await fs.promises.readFile(jsonPath, 'utf8'));
    const segments = data.transcription || [];
    const results = [];

    segments.forEach((segment) => {
      const text = segment.text.trim();
      if (!text) return;
      if (/^\[blank_audio\]$/i.test(text) || text.toUpperCase().includes('BLANK_AUDIO')) {
        return;
      }

      results.push({
        fromMs: segment.offsets.from,
        toMs: segment.offsets.to,
        speaker: speakerName,
        text
      });
    });

    try {
      await fs.promises.unlink(jsonPath);
    } catch (unlinkErr) {
      // Ignore cleanup errors.
    }

    return results;
  } catch (err) {
    console.error(`Error parsing whisper JSON output for ${speakerName}`, err);
    return [];
  }
}

async function processChunk(job) {
  const {
    chunkPath,
    chunkIndex,
    whisperDir,
    selectedModel
  } = job;

  const whisperCli = path.join(whisperDir, 'build', 'bin', 'whisper-cli');
  const whisperModel = path.join(whisperDir, 'models', selectedModel);

  if (!fs.existsSync(whisperCli)) {
    throw new Error(`Whisper.cpp binary not found at ${whisperCli}`);
  }
  if (!fs.existsSync(whisperModel)) {
    throw new Error(`Whisper model not found at ${whisperModel}`);
  }

  const leftWavPath = chunkPath.replace('.wav', '_left.wav');
  const rightWavPath = chunkPath.replace('.wav', '_right.wav');

  try {
    await splitStereoChannels(chunkPath, leftWavPath, rightWavPath);
  } catch (err) {
    await secureShredFile(chunkPath);
    throw new Error(`Ffmpeg channel splitting failed: ${err.message}`);
  }

  const hasLeftVoice = await checkVoiceActivity(leftWavPath);
  const hasRightVoice = await checkVoiceActivity(rightWavPath);

  if (!hasLeftVoice && !hasRightVoice) {
    console.log(`VAD noise gate: Chunk ${chunkIndex} Left & Right are silent. Dropping chunk.`);
    await secureShredFile(chunkPath);
    await secureShredFile(leftWavPath);
    await secureShredFile(rightWavPath);
    return [];
  }

  const allSegments = [];

  if (hasLeftVoice) {
    try {
      const leftSegments = await transcribeMonoFile(leftWavPath, 'Speaker 1', whisperCli, whisperModel);
      allSegments.push(...leftSegments);
    } catch (err) {
      console.error('Whisper execution failed for Speaker 1', err);
    }
  } else {
    await secureShredFile(leftWavPath);
  }

  if (hasRightVoice) {
    try {
      const rightSegments = await transcribeMonoFile(rightWavPath, 'You', whisperCli, whisperModel);
      allSegments.push(...rightSegments);
    } catch (err) {
      console.error('Whisper execution failed for You', err);
    }
  } else {
    await secureShredFile(rightWavPath);
  }

  allSegments.sort((a, b) => a.fromMs - b.fromMs);

  await secureShredFile(chunkPath);
  await secureShredFile(leftWavPath);
  await secureShredFile(rightWavPath);

  return allSegments;
}

parentPort.on('message', async (message) => {
  if (message.type !== 'process-chunk') return;

  try {
    const segments = await processChunk(message);
    parentPort.postMessage({
      type: 'chunk-complete',
      jobId: message.jobId,
      chunkIndex: message.chunkIndex,
      segments
    });
  } catch (err) {
    parentPort.postMessage({
      type: 'chunk-error',
      jobId: message.jobId,
      chunkIndex: message.chunkIndex,
      error: err.message
    });
  }
});
