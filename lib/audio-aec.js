/**
 * App-level acoustic echo cancellation.
 * Aligns system (reference) audio to mic and subtracts correlated echo energy.
 */

function downsample(buffer, factor) {
  const out = new Float32Array(Math.floor(buffer.length / factor));
  for (let i = 0; i < out.length; i += 1) {
    out[i] = buffer[i * factor] / 32768;
  }
  return out;
}

function gccPhatDelay(reference, mic, sampleRate = 16000) {
  const factor = 4;
  const ref = downsample(reference, factor);
  const sig = downsample(mic, factor);
  const n = Math.pow(2, Math.ceil(Math.log2(ref.length + sig.length)));
  const refPadded = new Float32Array(n);
  const sigPadded = new Float32Array(n);
  refPadded.set(ref);
  sigPadded.set(sig);

  // Time-domain cross-correlation (sufficient for 2s @ 4kHz ≈ 8000 samples)
  let bestLag = 0;
  let bestScore = -Infinity;
  const maxLag = Math.min(512, Math.floor(n / 4));

  for (let lag = -maxLag; lag <= maxLag; lag += 1) {
    let sum = 0;
    let refEnergy = 0;
    for (let i = 0; i < sig.length; i += 1) {
      const refIdx = i + lag;
      if (refIdx < 0 || refIdx >= ref.length) continue;
      sum += sig[i] * ref[refIdx];
      refEnergy += ref[refIdx] * ref[refIdx];
    }
    const score = refEnergy > 0 ? sum / Math.sqrt(refEnergy) : sum;
    if (score > bestScore) {
      bestScore = score;
      bestLag = lag;
    }
  }

  return Math.round(bestLag * factor);
}

function readPcm16(buffer, channels = 1, channelIndex = 0) {
  const dataOffset = 44;
  const frameSize = channels * 2;
  const samples = Math.floor((buffer.length - dataOffset) / frameSize);
  const out = new Int16Array(samples);

  for (let i = 0; i < samples; i += 1) {
    const offset = dataOffset + i * frameSize + channelIndex * 2;
    out[i] = buffer.readInt16LE(offset);
  }
  return out;
}

function rmsInt16(samples) {
  if (!samples.length) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i += 1) {
    sum += samples[i] * samples[i];
  }
  return Math.sqrt(sum / samples.length);
}

/**
 * Subtract aligned reference echo from mic PCM.
 */
function cancelEchoFromMic(micSamples, referenceSamples, options = {}) {
  const {
    maxGain = 0.95,
    coherenceThreshold = 0.55,
    frameSize = 320,
    hopSize = 160
  } = options;

  if (!micSamples.length || !referenceSamples.length) {
    return Int16Array.from(micSamples);
  }

  const delay = gccPhatDelay(referenceSamples, micSamples);
  const alignedRef = new Int16Array(micSamples.length);
  for (let i = 0; i < micSamples.length; i += 1) {
    const refIdx = i - delay;
    if (refIdx >= 0 && refIdx < referenceSamples.length) {
      alignedRef[i] = referenceSamples[refIdx];
    }
  }

  const refRms = rmsInt16(alignedRef);
  if (refRms < 120) {
    return Int16Array.from(micSamples);
  }

  const output = Int16Array.from(micSamples);
  const adaptiveGain = new Float32Array(frameSize).fill(0.3);

  for (let start = 0; start + frameSize <= output.length; start += hopSize) {
    let micEnergy = 0;
    let refEnergy = 0;
    let cross = 0;

    for (let i = 0; i < frameSize; i += 1) {
      const mic = output[start + i];
      const ref = alignedRef[start + i];
      micEnergy += mic * mic;
      refEnergy += ref * ref;
      cross += mic * ref;
    }

    const coherence = (refEnergy > 0 && micEnergy > 0)
      ? Math.abs(cross) / Math.sqrt(refEnergy * micEnergy)
      : 0;

    if (coherence < coherenceThreshold) continue;

    for (let i = 0; i < frameSize; i += 1) {
      const idx = start + i;
      const ref = alignedRef[idx];
      const mic = output[idx];
      const error = mic - adaptiveGain[i] * ref;
      adaptiveGain[i] += 0.01 * ref * error / (refEnergy / frameSize + 1);
      adaptiveGain[i] = Math.max(0, Math.min(maxGain, adaptiveGain[i]));
      output[idx] = Math.round(mic - adaptiveGain[i] * ref);
    }
  }

  const cleanedRms = rmsInt16(output);
  const originalRms = rmsInt16(micSamples);
  if (cleanedRms < originalRms * 0.05) {
    return Int16Array.from(micSamples);
  }

  return output;
}

function measureEchoCoherence(systemSamples, micSamples) {
  if (!systemSamples.length || !micSamples.length) {
    return { coherence: 0, systemRms: 0, micRms: 0 };
  }

  const delay = gccPhatDelay(systemSamples, micSamples);
  const alignedRef = new Int16Array(micSamples.length);
  for (let i = 0; i < micSamples.length; i += 1) {
    const refIdx = i - delay;
    if (refIdx >= 0 && refIdx < systemSamples.length) {
      alignedRef[i] = systemSamples[refIdx];
    }
  }

  const systemRms = rmsInt16(alignedRef);
  const micRms = rmsInt16(micSamples);
  let cross = 0;
  let refEnergy = 0;
  let micEnergy = 0;

  for (let i = 0; i < micSamples.length; i += 1) {
    const ref = alignedRef[i];
    const mic = micSamples[i];
    cross += ref * mic;
    refEnergy += ref * ref;
    micEnergy += mic * mic;
  }

  const coherence = (refEnergy > 0 && micEnergy > 0)
    ? Math.abs(cross) / Math.sqrt(refEnergy * micEnergy)
    : 0;

  return { coherence, systemRms, micRms };
}

function isMicDominatedBySystemEcho(systemBuffer, micBuffer) {
  const systemSamples = readPcm16(systemBuffer, 1);
  const micSamples = readPcm16(micBuffer, 1);
  const { coherence, systemRms, micRms } = measureEchoCoherence(systemSamples, micSamples);

  if (systemRms < 120) return false;
  if (coherence >= 0.55) return true;
  if (coherence >= 0.35 && micRms <= systemRms * 1.15) return true;
  return false;
}

function applyAecToWavBuffers(systemBuffer, micBuffer) {
  const systemSamples = readPcm16(systemBuffer, 1);
  const micSamples = readPcm16(micBuffer, 1);
  const cleaned = cancelEchoFromMic(micSamples, systemSamples);

  const out = Buffer.from(micBuffer);
  const dataOffset = 44;
  for (let i = 0; i < cleaned.length; i += 1) {
    out.writeInt16LE(cleaned[i], dataOffset + i * 2);
  }
  return out;
}

module.exports = {
  gccPhatDelay,
  cancelEchoFromMic,
  applyAecToWavBuffers,
  readPcm16,
  rmsInt16,
  measureEchoCoherence,
  isMicDominatedBySystemEcho
};
