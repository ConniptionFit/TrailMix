const fs = require('fs');
const fsPromises = fs.promises;

/**
 * Compute RMS amplitude (0–1) from a 16-bit PCM WAV buffer or file.
 * Samples a subset of frames for level metering so the main process stays light.
 */
function computeWavRmsFromBuffer(buffer, { sampleStride = 1 } = {}) {
  if (!buffer || buffer.length < 44) return 0;

  const dataOffset = 44;
  if (buffer.length - dataOffset < 2) return 0;

  let sumSquares = 0;
  let count = 0;
  const step = Math.max(1, sampleStride) * 2;

  for (let i = dataOffset; i < buffer.length - 1; i += step) {
    const sample = buffer.readInt16LE(i);
    sumSquares += sample * sample;
    count += 1;
  }

  if (count === 0) return 0;
  const rms = Math.sqrt(sumSquares / count) / 32768;
  return Math.min(1, rms * 4);
}

function computeWavRms(filePath) {
  if (!fs.existsSync(filePath)) return 0;
  return computeWavRmsFromBuffer(fs.readFileSync(filePath));
}

function computeStereoLevelsFromBuffer(buffer, { sampleStride = 8 } = {}) {
  if (!buffer || buffer.length < 44) {
    return { left: 0, right: 0, combined: 0 };
  }

  const numChannels = buffer.readUInt16LE(22) || 2;
  const bitsPerSample = buffer.readUInt16LE(34) || 16;
  const dataOffset = 44;

  if (bitsPerSample !== 16 || numChannels < 1) {
    const combined = computeWavRmsFromBuffer(buffer, { sampleStride });
    return { left: combined, right: combined, combined };
  }

  let leftSum = 0;
  let rightSum = 0;
  let leftCount = 0;
  let rightCount = 0;
  const frameSize = numChannels * 2;
  const stride = Math.max(1, sampleStride) * frameSize;

  // Only read the trailing window (~0.25s at 16kHz stereo) for responsive meters.
  const maxBytes = 16000 * frameSize / 4;
  const start = Math.max(dataOffset, buffer.length - maxBytes);

  for (let i = start; i < buffer.length - frameSize + 1; i += stride) {
    const left = buffer.readInt16LE(i);
    leftSum += left * left;
    leftCount += 1;

    if (numChannels >= 2) {
      const right = buffer.readInt16LE(i + 2);
      rightSum += right * right;
      rightCount += 1;
    }
  }

  const toLevel = (sum, count) => {
    if (count === 0) return 0;
    const rms = Math.sqrt(sum / count) / 32768;
    return Math.min(1, rms * 4);
  };

  const left = toLevel(leftSum, leftCount);
  const right = toLevel(rightSum, rightCount);
  return { left, right, combined: Math.max(left, right) };
}

function computeStereoLevels(filePath) {
  if (!fs.existsSync(filePath)) {
    return { left: 0, right: 0, combined: 0 };
  }
  return computeStereoLevelsFromBuffer(fs.readFileSync(filePath));
}

async function computeStereoLevelsAsync(filePath) {
  try {
    await fsPromises.access(filePath);
    const buffer = await fsPromises.readFile(filePath);
    return computeStereoLevelsFromBuffer(buffer);
  } catch (err) {
    return { left: 0, right: 0, combined: 0 };
  }
}

module.exports = {
  computeWavRms,
  computeWavRmsFromBuffer,
  computeStereoLevels,
  computeStereoLevelsFromBuffer,
  computeStereoLevelsAsync
};
