/**
 * Pass 2 (precision clustering) and Pass 3 (contextual name identification) prompts.
 */

function getPrecisionDiarizationSystemPrompt() {
  return `You are a local speaker clustering assistant. Analyze transcript turns and assign stable cluster IDs.
Rules:
- The local user's microphone channel is labeled "You". Always keep "You" as "You".
- All other speech currently shares one inbound channel. Cluster distinct inbound voices into "Speaker_00", "Speaker_01", "Speaker_02", etc.
- Use at most 5 inbound speakers.
- If two consecutive turns are clearly the same person, they MUST share the same Speaker_XX id.
- Output ONLY a valid JSON object mapping turnIndex strings ("1", "2", ...) to labels ("You", "Speaker_00", ...).
- No markdown, no commentary.`;
}

function getContextualIdentificationSystemPrompt(userName) {
  const displayName = userName || 'You';
  return `You are a local conversation analyst. Map speaker cluster IDs to human-readable names using conversational context.
Rules:
- "You" stays "${displayName}" if a name is needed, otherwise "You".
- Use direct evidence from the transcript (e.g. "John, what do you think?" → next speaker likely John).
- Prefer real names or roles ("Sarah", "Support Agent") over generic labels when confident.
- Output ONLY JSON: { "Speaker_00": "Name or Role", "Speaker_01": "..." }
- Include every Speaker_XX key present in the input. No markdown.`;
}

function buildPrecisionPrompt(turns) {
  return `Cluster these turns:\n\n${JSON.stringify(turns, null, 2)}\n\nReturn the JSON map.`;
}

function buildIdentificationPrompt(blocksWithClusters) {
  return `Map cluster IDs to names using context:\n\n${JSON.stringify(blocksWithClusters, null, 2)}\n\nReturn the JSON map.`;
}

function applyClusterMappingToBlock(block, mapping, globalOffset = 0) {
  block.forEach((segment, index) => {
    const turnKey = String(globalOffset + index + 1);
    const label = mapping[turnKey] || mapping[segment.id];
    if (!label) return;
    if (segment.speaker === 'You' && label !== 'You') return;
    segment.speaker = label;
  });
}

function applyGlobalSpeakerMap(transcript, speakerMap) {
  transcript.forEach((segment) => {
    if (segment.speaker === 'You') return;
    const mapped = speakerMap[segment.speaker];
    if (mapped) segment.speaker = mapped;
  });
}

module.exports = {
  getPrecisionDiarizationSystemPrompt,
  getContextualIdentificationSystemPrompt,
  buildPrecisionPrompt,
  buildIdentificationPrompt,
  applyClusterMappingToBlock,
  applyGlobalSpeakerMap
};
