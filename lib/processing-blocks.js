const DEFAULT_BLOCK_MS = 90000;
const DEFAULT_MAX_SEGMENTS = 24;
const IDENTIFICATION_BATCH_SIZE = 4;

function splitTranscriptIntoBlocks(transcript, options = {}) {
  const blockMs = options.blockMs || DEFAULT_BLOCK_MS;
  const maxSegments = options.maxSegments || DEFAULT_MAX_SEGMENTS;
  if (!Array.isArray(transcript) || transcript.length === 0) return [];

  const sorted = [...transcript].sort((a, b) => a.timestampMs - b.timestampMs);
  const blocks = [];
  let current = [];

  for (const segment of sorted) {
    if (current.length === 0) {
      current.push(segment);
      continue;
    }

    const blockStart = current[0].timestampMs;
    const spanMs = segment.timestampMs - blockStart;
    if (spanMs > blockMs || current.length >= maxSegments) {
      blocks.push(current);
      current = [segment];
    } else {
      current.push(segment);
    }
  }

  if (current.length > 0) blocks.push(current);
  return blocks;
}

function batchBlocks(blocks, batchSize = IDENTIFICATION_BATCH_SIZE) {
  const batches = [];
  for (let i = 0; i < blocks.length; i += batchSize) {
    batches.push(blocks.slice(i, i + batchSize));
  }
  return batches;
}

module.exports = {
  DEFAULT_BLOCK_MS,
  DEFAULT_MAX_SEGMENTS,
  IDENTIFICATION_BATCH_SIZE,
  splitTranscriptIntoBlocks,
  batchBlocks
};
