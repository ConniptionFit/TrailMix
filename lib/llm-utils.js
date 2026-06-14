/**
 * Shared helpers for parsing and building local LLM prompts/responses.
 */

function parseLlmJsonResponse(rawText) {
  let cleanText = rawText.trim();
  const firstBrace = cleanText.indexOf('{');
  const lastBrace = cleanText.lastIndexOf('}');

  if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
    cleanText = cleanText.substring(firstBrace, lastBrace + 1);
  } else if (cleanText.includes('```')) {
    const match = cleanText.match(/```(?:json)?\s*([\s\S]+?)\s*```/);
    if (match) cleanText = match[1];
  }

  return JSON.parse(cleanText);
}

function applyPromptTemplate(template, transcriptText) {
  if (template.includes('{transcriptText}')) {
    return template.replace('{transcriptText}', transcriptText);
  }
  return `${template}\n\n${transcriptText}`;
}

module.exports = {
  parseLlmJsonResponse,
  applyPromptTemplate
};
