/**
 * Document model for the Jot & Enhance editor.
 *
 * During a meeting the editor stays in `plain` mode and persists raw user jots.
 * After enhancement the editor switches to `mixed` mode where each span tracks
 * whether text originated from the user or from the local LLM.
 */

const DOCUMENT_VERSION = 1;

function createSpan(text, origin = 'user', id = null, transcriptRef = null) {
  return {
    id: id || `span_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    origin,
    text: text || '',
    transcriptRef: transcriptRef || null
  };
}

function createPlainDocument(plainText = '') {
  return {
    version: DOCUMENT_VERSION,
    mode: 'plain',
    plainText: plainText || '',
    spans: [],
    enhancedAt: null
  };
}

function createMixedDocument(plainText, spans, enhancedAt = null) {
  return {
    version: DOCUMENT_VERSION,
    mode: 'mixed',
    plainText: plainText || '',
    spans: Array.isArray(spans) ? spans : [],
    enhancedAt: enhancedAt || new Date().toISOString()
  };
}

function normalizeDocument(rawDocument, fallbackPlainText = '') {
  if (!rawDocument) {
    return createPlainDocument(fallbackPlainText);
  }

  if (typeof rawDocument === 'string') {
    try {
      rawDocument = JSON.parse(rawDocument);
    } catch (err) {
      return createPlainDocument(rawDocument || fallbackPlainText);
    }
  }

  if (rawDocument.version !== DOCUMENT_VERSION) {
    return createPlainDocument(rawDocument.plainText || fallbackPlainText);
  }

  if (rawDocument.mode === 'mixed' && Array.isArray(rawDocument.spans) && rawDocument.spans.length > 0) {
    return {
      version: DOCUMENT_VERSION,
      mode: 'mixed',
      plainText: rawDocument.plainText || fallbackPlainText,
      spans: rawDocument.spans.map((span) => createSpan(span.text, span.origin, span.id, span.transcriptRef)),
      enhancedAt: rawDocument.enhancedAt || null
    };
  }

  return createPlainDocument(rawDocument.plainText ?? fallbackPlainText);
}

function documentFromSession(session) {
  if (!session) return createPlainDocument();

  if (session.editorDocument) {
    return normalizeDocument(session.editorDocument, session.mixNotes || '');
  }

  return createPlainDocument(session.mixNotes || '');
}

function applyPlainText(document, plainText) {
  const nextDocument = normalizeDocument(document, plainText);
  nextDocument.mode = 'plain';
  nextDocument.plainText = plainText;
  nextDocument.spans = [];
  nextDocument.enhancedAt = null;
  return nextDocument;
}

function applyMixedSpans(document, spans) {
  const plainText = document.plainText || '';
  return createMixedDocument(plainText, spans);
}

function serializeDocument(document) {
  return JSON.parse(JSON.stringify(normalizeDocument(document)));
}

function spansToPlainText(spans) {
  return (spans || []).map((span) => span.text).join('');
}

function spansToMarkdown(spans) {
  return (spans || []).map((span) => {
    if (span.origin === 'user') {
      return `**${span.text.trim()}**`;
    }
    return span.text;
  }).join('');
}

function validateMixedSpans(spans) {
  if (!Array.isArray(spans) || spans.length === 0) {
    return { valid: false, error: 'Expected a non-empty spans array.' };
  }

  for (const span of spans) {
    if (!span || typeof span.text !== 'string') {
      return { valid: false, error: 'Each span must include a text string.' };
    }
    if (span.origin !== 'user' && span.origin !== 'ai') {
      return { valid: false, error: 'Span origin must be "user" or "ai".' };
    }
  }

  return { valid: true };
}

module.exports = {
  DOCUMENT_VERSION,
  createSpan,
  createPlainDocument,
  createMixedDocument,
  normalizeDocument,
  documentFromSession,
  applyPlainText,
  applyMixedSpans,
  serializeDocument,
  spansToPlainText,
  spansToMarkdown,
  validateMixedSpans
};
