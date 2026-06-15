/** @module chat — Mix-Master AI chat and enhance */
module.exports = {
  id: 'chat',
  version: '1.0.0',
  dependencies: ['sessions'],
  channels: [
    'chat:query',
    'chat:get-recipes',
    'chat:mix-enhance',
    'llm:stream-chunk',
    'llm:cancel'
  ],
  register(ctx) {
    ctx.chat = {
      recipes: ctx.runtime.CHAT_RECIPES,
      buildRecipe: ctx.runtime.buildRecipePrompt
    };
  }
};
