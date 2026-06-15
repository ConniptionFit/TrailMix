/** @module models — whisper + ollama model management */
module.exports = {
  id: 'models',
  version: '1.0.0',
  channels: [
    'models:get-specs',
    'models:get-ollama-models',
    'models:download-whisper',
    'models:on-download-progress'
  ],
  register(ctx) {
    ctx.models = {
      getSpecs: ctx.runtime.getHardwareSpecs
    };
  }
};
