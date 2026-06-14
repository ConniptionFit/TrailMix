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

function isMicSpeaker(name) {
  const normalized = String(name || '').toLowerCase();
  return normalized === 'you' || normalized === 'me';
}

function estimateBleedConfidence(micSegment, systemSegment) {
  const similarity = textSimilarity(micSegment.text, systemSegment.text);
  const timeDelta = Math.abs((micSegment.timestampMs || 0) - (systemSegment.timestampMs || 0));
  const timeScore = timeDelta <= 1200 ? 1 : timeDelta <= 2500 ? 0.75 : timeDelta <= 4000 ? 0.5 : 0;

  if (similarity >= 0.75 && timeScore >= 0.75) return 0.95;
  if (similarity >= 0.6 && timeScore >= 0.5) return 0.85;
  if (similarity >= 0.5 && timeScore >= 0.5) return 0.72;
  if (similarity >= 0.45 && timeDelta <= 3000) return 0.62;
  return 0;
}

function findBestSystemMatch(micSegment, systemSegments) {
  let best = null;
  let bestConfidence = 0;

  systemSegments.forEach((systemSegment) => {
    const confidence = estimateBleedConfidence(micSegment, systemSegment);
    if (confidence > bestConfidence) {
      bestConfidence = confidence;
      best = systemSegment;
    }
  });

  return { match: best, confidence: bestConfidence };
}

function correctBleedInTranscript(transcript, options = {}) {
  const minConfidence = options.minConfidence ?? 0.72;
  if (!Array.isArray(transcript) || transcript.length < 2) {
    return { transcript: transcript || [], removedSegmentIds: [] };
  }

  const systemSegments = transcript.filter((segment) => !isMicSpeaker(segment.speaker));
  const removedSegmentIds = [];

  const kept = transcript.filter((segment) => {
    if (!isMicSpeaker(segment.speaker)) return true;
    if (!systemSegments.length) return true;

    const { confidence } = findBestSystemMatch(segment, systemSegments);
    if (confidence >= minConfidence) {
      removedSegmentIds.push(segment.id);
      return false;
    }
    return true;
  });

  return {
    transcript: kept,
    removedSegmentIds
  };
}

module.exports = {
  correctBleedInTranscript,
  estimateBleedConfidence,
  textSimilarity,
  isMicSpeaker
};
