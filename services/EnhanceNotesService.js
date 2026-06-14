const {
  buildEnhanceNotesPrompt,
  parseEnhanceNotesResponse
} = require('../lib/enhance-notes');
const {
  applyMixedSpans,
  serializeDocument
} = require('../lib/editor-document');

class EnhanceNotesService {
  constructor(llmService) {
    this.llmService = llmService;
  }

  buildFullTranscript(transcriptSegments) {
    if (!Array.isArray(transcriptSegments) || transcriptSegments.length === 0) {
      return '';
    }

    return transcriptSegments
      .map((segment) => `[${segment.timestamp}] ${segment.speaker}: ${segment.text}`)
      .join('\n');
  }

  async enhanceDocument({
    editorDocument,
    fullTranscript,
    model,
    systemPrompt
  }) {
    const jots = editorDocument.plainText || '';
    if (!jots.trim()) {
      return null;
    }

    const userPrompt = buildEnhanceNotesPrompt(jots, fullTranscript, systemPrompt);
    const rawResponse = await this.llmService.queryComplete(userPrompt, model, systemPrompt);
    const parsed = parseEnhanceNotesResponse(rawResponse, jots);
    const mixedDocument = applyMixedSpans(editorDocument, parsed.spans);
    mixedDocument.enhancedAt = new Date().toISOString();

    return {
      editorDocument: serializeDocument(mixedDocument),
      enhancedNotes: parsed.enhancedNotes,
      enhancedPlainText: parsed.enhancedPlainText,
      usedFallback: Boolean(parsed.usedFallback)
    };
  }
}

module.exports = {
  EnhanceNotesService
};
