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

function findTranscriptRefForText(text, transcriptSegments = []) {
  if (!text || !Array.isArray(transcriptSegments) || transcriptSegments.length === 0) {
    return null;
  }

  const needle = String(text).trim().slice(0, 48).toLowerCase();
  if (!needle) return null;

  const match = transcriptSegments.find((segment) => {
    const haystack = String(segment.text || '').toLowerCase();
    return haystack.includes(needle) || needle.includes(haystack.slice(0, 32));
  });

  if (!match) return null;

  return {
    segmentId: match.id || null,
    timestampMs: match.timestampMs || null,
    timestamp: match.timestamp || null,
    speaker: match.speaker || null
  };
}

function normalizeSpanPayload(rawSpans, transcriptSegments = []) {
  return rawSpans.map((span) => {
    const origin = span.origin === 'user' ? 'user' : 'ai';
    const transcriptRef = origin === 'ai'
      ? (span.transcriptRef || findTranscriptRefForText(span.text, transcriptSegments))
      : null;

    return createSpan(
      String(span.text || ''),
      origin,
      span.id || null,
      transcriptRef
    );
  });
}

function parseEnhanceNotesResponse(rawResponse, fallbackPlainText = '', transcriptSegments = []) {
  try {
    const payload = parseLlmJsonResponse(rawResponse);
    const validation = validateMixedSpans(payload.spans);
    if (!validation.valid) {
      throw new Error(validation.error);
    }

    const spans = normalizeSpanPayload(payload.spans, transcriptSegments);
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
  findTranscriptRefForText,
  normalizeSpanPayload,
  parseEnhanceNotesResponse
};
