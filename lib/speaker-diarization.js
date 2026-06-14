/**
 * Shared helpers for LLM-based speaker diarization.
 */

function getSpeakerDiarizationSystemPrompt(userName) {
  const displayName = userName || 'You';
  return `You are a local speaker attribution assistant. Your job is to analyze the conversation turns of a transcript and differentiate/label the speakers.
The user is "You" (their name is "${displayName}"). Always leave the speaker "You" as "You" (do not change it).
Other turns are currently labeled as "Speaker 1". All inbound speakers are mixed on the inbound audio channel. Differentiate them based on conversational context, flow, and text.
Assign them identifiers like "Speaker 1", "Speaker 2" if you cannot deduce their names. Limit the number of unique inbound speakers identified to a maximum of 5.
If you can deduce their actual name or role (e.g. "Sarah", "Netflix Support Agent", "BestBuy Support") from what they say in the transcript, use that descriptive name/label instead.
Output your results ONLY as a valid JSON object mapping turnIndex strings (e.g. "1", "2") to their corrected speaker labels. Do not include any reasoning, markdown formatting (like \`\`\`json), or conversational text. Output ONLY the raw JSON object.`;
}

function buildDiarizationTurns(transcript) {
  return transcript.map((segment, index) => ({
    turnIndex: index + 1,
    speaker: segment.speaker,
    text: segment.text
  }));
}

function buildDiarizationPrompt(turns) {
  return `Here is the current transcript segments list:\n\n${JSON.stringify(turns, null, 2)}\n\nAnalyze the segments and return the JSON map of speaker labels.`;
}

function applySpeakerLabelMapping(transcript, mapping) {
  transcript.forEach((segment, index) => {
    let label = mapping[segment.id];
    if (!label) {
      label = mapping[(index + 1).toString()] || mapping[index + 1];
    }
    if (!label) return;

    if (label !== 'You' && segment.speaker === 'You') {
      return;
    }

    segment.speaker = label;
  });
}

function notifySpeakerLabelsUpdated(mainWindow, mapping) {
  if (mainWindow) {
    mainWindow.webContents.send('audio:on-speaker-labels-updated', mapping);
  }
}

module.exports = {
  getSpeakerDiarizationSystemPrompt,
  buildDiarizationTurns,
  buildDiarizationPrompt,
  applySpeakerLabelMapping,
  notifySpeakerLabelsUpdated
};
