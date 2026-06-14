const fs = require('fs');
const path = require('path');

async function checkVoiceActivity(wavPath) {
  try {
    await fs.promises.access(wavPath);
    const buffer = await fs.promises.readFile(wavPath);
    if (buffer.length <= 44) return false;

    const numChannels = buffer.readUInt16LE(22);
    const bytesPerFrame = numChannels * 2;

    let sum = 0;
    let count = 0;

    for (let i = 44; i < buffer.length; i += bytesPerFrame) {
      if (i + 1 < buffer.length) {
        const sample = buffer.readInt16LE(i);
        sum += sample * sample;
        count++;
      }
    }

    if (count === 0) return false;

    const rms = Math.sqrt(sum / count);
    console.log(`VAD check [${path.basename(wavPath)}]: RMS energy = ${Math.round(rms)}`);
    return rms > 100;
  } catch (err) {
    console.error('VAD check failed, treating as active', err);
    return true;
  }
}

module.exports = {
  checkVoiceActivity
};
