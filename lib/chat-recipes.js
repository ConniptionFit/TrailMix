/**
 * Parameterized prompt templates ("Recipes") for tactical post-meeting chat.
 * Decoupled from renderer components — consumed by main process chat handler.
 */

const CHAT_RECIPES = [
  {
    id: 'action-items',
    label: 'List action items',
    icon: '',
    prompt: 'Review the finalized notes and transcript. Extract every actionable task with assignee and deadline. Format as a checklist with [ ] markers.',
    params: []
  },
  {
    id: 'follow-up-email',
    label: 'Write follow-up email',
    icon: '',
    prompt: 'Draft a concise follow-up email summarizing key decisions, action items, and next steps from this meeting. Use a professional tone suitable for sending to attendees.',
    params: []
  },
  {
    id: 'qa',
    label: 'List Q&A',
    icon: '',
    prompt: 'List every question asked during the meeting and the answer given (or note if unanswered). Format as Q / A pairs.',
    params: []
  },
  {
    id: 'decisions',
    label: 'List Decisions',
    icon: '',
    prompt: 'List every explicit decision or agreement reached during this meeting. Include who made or approved each decision when mentioned.',
    params: []
  },
  {
    id: 'risks',
    label: 'Surface Risks',
    icon: '',
    prompt: 'Identify open questions, risks, blockers, and unresolved threads mentioned in the meeting. Group by severity.',
    params: []
  },
  {
    id: 'missed',
    label: "What'd I miss?",
    icon: '',
    prompt: 'Summarize what happened in the last few minutes of the meeting that the user may have missed. Be brief and factual.',
    params: []
  }
];

function getRecipeById(id) {
  return CHAT_RECIPES.find((recipe) => recipe.id === id) || null;
}

function buildRecipePrompt(recipeId, overrides = {}) {
  const recipe = getRecipeById(recipeId);
  if (!recipe) return null;
  let prompt = recipe.prompt;
  Object.entries(overrides).forEach(([key, value]) => {
    prompt = prompt.replace(new RegExp(`\\{${key}\\}`, 'g'), String(value));
  });
  return prompt;
}

module.exports = {
  CHAT_RECIPES,
  getRecipeById,
  buildRecipePrompt
};
