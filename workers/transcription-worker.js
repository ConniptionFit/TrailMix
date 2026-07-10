const { parentPort } = require('worker_threads');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFile } = require('child_process');
const { promisify } = require('util');

const execFileAsync = promisify(execFile);
const { checkVoiceActivity } = require('../lib/audio-vad');
const { secureShredFile } = require('../lib/secure-shred');
const { applyAecToWavBuffers, isMicDominatedBySystemEcho } = require('../lib/audio-aec');
const { resolveWhisperCli } = require('../lib/resolve-whisper-cli');

async function splitStereoChannels(chunkWavPath, leftWavPath, rightWavPath) {
  // Single ffmpeg invocation for both channel extracts.
  await execFileAsync('ffmpeg', [
    '-y', '-i', chunkWavPath,
    '-filter_complex', '[0:a]pan=mono|c0=c0[left];[0:a]pan=mono|c0=c1[right]',
    '-map', '[left]', leftWavPath,
    '-map', '[right]', rightWavPath
  ]);
}

async function transcribeMonoFile(monoWavPath, speakerName, whisperCli, whisperModel, threadBudget) {
  const outputBase = monoWavPath.replace('.wav', '_trans');
  const jsonPath = `${outputBase}.json`;
  const defaultThreads = Math.min(4, Math.max(2, Math.floor((os.cpus().length - 1) / 2) || 2));
  const threads = Math.max(1, Math.min(4, Number(threadBudget) || defaultThreads));

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

async function applyAppLevelAec(leftWavPath, rightWavPath, leftBuffer, rightBuffer) {
  const cleaned = applyAecToWavBuffers(leftBuffer, rightBuffer);
  await fs.promises.writeFile(rightWavPath, cleaned);
  return cleaned;
}

function normalizeTranscriptText(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function textSimilarity(a, b) {
  const left = normalizeTranscriptText(a).split(' ').filter(Boolean);
  const right = normalizeTranscriptText(b).split(' ').filter(Boolean);
  if (!left.length || !right.length) return 0;

  const rightSet = new Set(right);
  const overlap = left.filter((word) => rightSet.has(word)).length;
  return overlap / Math.max(left.length, right.length);
}

function dedupeEchoSegments(segments) {
  const inbound = segments.filter((segment) => segment.speaker !== 'You');
  const mic = segments.filter((segment) => segment.speaker === 'You');
  const kept = [...inbound];

  mic.forEach((segment) => {
    const isEcho = inbound.some((other) => (
      Math.abs(other.fromMs - segment.fromMs) < 1500
      && textSimilarity(other.text, segment.text) >= 0.55
    ));
    if (!isEcho) kept.push(segment);
  });

  return kept.sort((a, b) => a.fromMs - b.fromMs);
}

async function shouldTranscribeMicChannel(leftBuffer, rightBuffer, rightWavPath, aecMode) {
  if (aecMode === 'off') return true;
  if (isMicDominatedBySystemEcho(leftBuffer, rightBuffer)) {
    return false;
  }
  return checkVoiceActivity(rightWavPath);
}

async function processChunk(job) {
  const {
    chunkPath,
    chunkIndex,
    whisperDir,
    selectedModel,
    aecMode = 'off',
    threadBudget
  } = job;

  const whisperCli = resolveWhisperCli(whisperDir);
  const whisperModel = path.join(whisperDir, 'models', selectedModel);

  if (!fs.existsSync(whisperCli)) {
    throw new Error(
      `Whisper.cpp binary not found. Run ./scripts/setup-whisper.sh (tried ${whisperCli})`
    );
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

  let leftBuffer = null;
  let rightBuffer = null;
  try {
    leftBuffer = await fs.promises.readFile(leftWavPath);
    rightBuffer = await fs.promises.readFile(rightWavPath);
  } catch (err) {
    await secureShredFile(chunkPath);
    await secureShredFile(leftWavPath);
    await secureShredFile(rightWavPath);
    throw err;
  }

  if (aecMode === 'app' || aecMode === 'guard') {
    try {
      rightBuffer = await applyAppLevelAec(leftWavPath, rightWavPath, leftBuffer, rightBuffer);
    } catch (err) {
      console.error(`App-level AEC failed for chunk ${chunkIndex}`, err);
    }
  }

  let [hasLeftVoice, hasRightVoice] = await Promise.all([
    checkVoiceActivity(leftWavPath),
    checkVoiceActivity(rightWavPath)
  ]);

  if (!hasLeftVoice && !hasRightVoice) {
    console.log(`VAD noise gate: Chunk ${chunkIndex} Left & Right are silent. Dropping chunk.`);
    await secureShredFile(chunkPath);
    await secureShredFile(leftWavPath);
    await secureShredFile(rightWavPath);
    return [];
  }

  if (hasRightVoice && hasLeftVoice && aecMode !== 'off') {
    hasRightVoice = await shouldTranscribeMicChannel(leftBuffer, rightBuffer, rightWavPath, aecMode);
    if (!hasRightVoice) {
      console.log(`Bleed gate: Chunk ${chunkIndex} mic channel dominated by system echo. Skipping You.`);
    }
  }

  const whisperTasks = [];
  if (hasLeftVoice) {
    whisperTasks.push(
      transcribeMonoFile(leftWavPath, 'Speaker 1', whisperCli, whisperModel, threadBudget)
        .catch((err) => {
          console.error('Whisper execution failed for Speaker 1', err);
          return [];
        })
    );
  } else {
    await secureShredFile(leftWavPath);
  }

  if (hasRightVoice) {
    whisperTasks.push(
      transcribeMonoFile(rightWavPath, 'You', whisperCli, whisperModel, threadBudget)
        .catch((err) => {
          console.error('Whisper execution failed for You', err);
          return [];
        })
    );
  } else {
    await secureShredFile(rightWavPath);
  }

  const segmentGroups = await Promise.all(whisperTasks);
  const allSegments = segmentGroups.flat();

  const dedupedSegments = allSegments.some((segment) => segment.speaker === 'You')
    && allSegments.some((segment) => segment.speaker !== 'You')
    ? dedupeEchoSegments(allSegments)
    : allSegments;
  dedupedSegments.sort((a, b) => a.fromMs - b.fromMs);

  await secureShredFile(chunkPath);
  await secureShredFile(leftWavPath);
  await secureShredFile(rightWavPath);

  return dedupedSegments;
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
