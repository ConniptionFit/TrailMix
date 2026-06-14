const COALESCE_GAP_MS = 12000;

function normalizeSpeaker(speaker) {
  const s = (speaker || '').trim().toLowerCase();
  if (s === 'me') return 'You';
  return speaker;
}

function canMergeSegments(previous, next, gapMs = COALESCE_GAP_MS) {
  if (!previous || !next) return false;
  const prevSpeaker = normalizeSpeaker(previous.speaker);
  const nextSpeaker = normalizeSpeaker(next.speaker);
  if (prevSpeaker !== nextSpeaker) return false;
  const gap = next.timestampMs - previous.timestampMs;
  return gap >= 0 && gap <= gapMs;
}

function mergeSegmentText(previous, next) {
  const merged = { ...previous };
  merged.text = `${previous.text} ${next.text}`.trim();
  merged.timestampMs = next.timestampMs;
  merged.timestamp = next.timestamp;
  merged.wallTimeMs = next.wallTimeMs || previous.wallTimeMs;
  if (next.chunkIndex != null) merged.chunkIndex = next.chunkIndex;
  return merged;
}

/**
 * Coalesce incoming chunk segments with each other, then with the tail of existing transcript.
 */
function coalesceIncomingSegments(existingTranscript, incomingSegments, gapMs = COALESCE_GAP_MS) {
  const transcript = Array.isArray(existingTranscript) ? [...existingTranscript] : [];
  let batch = Array.isArray(incomingSegments) ? [...incomingSegments] : [];

  if (batch.length === 0) {
    return { transcript, emitted: [] };
  }

  batch.sort((a, b) => a.timestampMs - b.timestampMs);

  const mergedBatch = [];
  for (const segment of batch) {
    const last = mergedBatch[mergedBatch.length - 1];
    if (last && canMergeSegments(last, segment, gapMs)) {
      mergedBatch[mergedBatch.length - 1] = mergeSegmentText(last, segment);
    } else {
      mergedBatch.push({ ...segment });
    }
  }

  const emitted = [];
  for (const segment of mergedBatch) {
    const tail = transcript[transcript.length - 1];
    if (tail && canMergeSegments(tail, segment, gapMs)) {
      transcript[transcript.length - 1] = mergeSegmentText(tail, segment);
      emitted.push({ ...transcript[transcript.length - 1], merged: true });
    } else {
      transcript.push(segment);
      emitted.push({ ...segment, merged: false });
    }
  }

  return { transcript, emitted };
}

module.exports = {
  COALESCE_GAP_MS,
  canMergeSegments,
  mergeSegmentText,
  coalesceIncomingSegments
};
