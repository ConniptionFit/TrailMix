const fs = require('fs');

/**
 * Compute RMS amplitude (0–1) from a 16-bit PCM WAV file.
 */
function computeWavRms(filePath) {
  if (!fs.existsSync(filePath)) return 0;

  const buffer = fs.readFileSync(filePath);
  if (buffer.length < 44) return 0;

  const dataOffset = 44;
  const samples = buffer.length - dataOffset;
  if (samples < 2) return 0;

  let sumSquares = 0;
  let count = 0;

  for (let i = dataOffset; i < buffer.length - 1; i += 2) {
    const sample = buffer.readInt16LE(i);
    sumSquares += sample * sample;
    count += 1;
  }

  if (count === 0) return 0;
  const rms = Math.sqrt(sumSquares / count) / 32768;
  return Math.min(1, rms * 4);
}

/**
 * Split stereo WAV into left (system) and right (mic) channel levels.
 */
function computeStereoLevels(filePath) {
  if (!fs.existsSync(filePath)) {
    return { left: 0, right: 0, combined: 0 };
  }

  const buffer = fs.readFileSync(filePath);
  if (buffer.length < 44) {
    return { left: 0, right: 0, combined: 0 };
  }

  const numChannels = buffer.readUInt16LE(22) || 2;
  const bitsPerSample = buffer.readUInt16LE(34) || 16;
  const dataOffset = 44;

  if (bitsPerSample !== 16 || numChannels < 1) {
    const combined = computeWavRms(filePath);
    return { left: combined, right: combined, combined };
  }

  let leftSum = 0;
  let rightSum = 0;
  let leftCount = 0;
  let rightCount = 0;
  const frameSize = numChannels * 2;

  for (let i = dataOffset; i < buffer.length - frameSize + 1; i += frameSize) {
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

module.exports = {
  computeWavRms,
  computeStereoLevels
};
