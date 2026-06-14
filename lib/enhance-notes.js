const { parseLlmJsonResponse } = require('./llm-utils');
const {
  createSpan,
  validateMixedSpans,
  spansToMarkdown,
  spansToPlainText
} = require('./editor-document');

const ENHANCE_NOTES_JSON_INSTRUCTION = `
Return ONLY a valid JSON object with this exact shape:
{
  "spans": [
    { "origin": "user", "text": "exact preserved user shorthand" },
    { "origin": "ai", "text": " expanded transcript-backed context written in lighter supporting prose" }
  ]
}

Rules:
- Alternate or group spans so user-origin text stays verbatim from the jots whenever possible.
- Use origin "user" ONLY for text that came directly from the user's jots.
- Use origin "ai" for all transcript-derived expansions, connective tissue, and clarifications.
- Preserve paragraph breaks using "\\n" inside span text.
- Do not wrap the JSON in markdown fences or add commentary outside the JSON object.`;

function buildEnhanceNotesPrompt(jots, fullTranscript, basePrompt = '') {
  const promptBody = basePrompt || 'Blend the user jots with the meeting transcript into polished notes.';
  return `${promptBody}

${ENHANCE_NOTES_JSON_INSTRUCTION}

User Jots:
${jots || 'No jots provided.'}

Full Transcript:
${fullTranscript || 'No transcript available.'}`;
}

function normalizeSpanPayload(rawSpans) {
  return rawSpans.map((span) => createSpan(
    String(span.text || ''),
    span.origin === 'user' ? 'user' : 'ai'
  ));
}

function parseEnhanceNotesResponse(rawResponse, fallbackPlainText = '') {
  try {
    const payload = parseLlmJsonResponse(rawResponse);
    const validation = validateMixedSpans(payload.spans);
    if (!validation.valid) {
      throw new Error(validation.error);
    }

    const spans = normalizeSpanPayload(payload.spans);
    return {
      spans,
      enhancedNotes: spansToMarkdown(spans),
      enhancedPlainText: spansToPlainText(spans)
    };
  } catch (err) {
    if (!fallbackPlainText.trim()) {
      throw err;
    }

    const fallbackSpans = [
      createSpan(fallbackPlainText, 'user'),
      createSpan('\n\n', 'ai'),
      createSpan(String(rawResponse || '').trim(), 'ai')
    ];

    return {
      spans: fallbackSpans,
      enhancedNotes: spansToMarkdown(fallbackSpans),
      enhancedPlainText: spansToPlainText(fallbackSpans),
      usedFallback: true
    };
  }
}

module.exports = {
  ENHANCE_NOTES_JSON_INSTRUCTION,
  buildEnhanceNotesPrompt,
  parseEnhanceNotesResponse
};
