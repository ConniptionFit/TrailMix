/** @module settings — Nuts and Bolts configuration */
module.exports = {
  id: 'settings',
  version: '1.0.0',
  channels: [
    'settings:get',
    'settings:save',
    'settings:get-default-prompts',
    'settings:select-directory'
  ],
  register(ctx) {
    ctx.settingsApi = {
      get: () => ctx.runtime.settings,
      save: ctx.runtime.saveSettings,
      defaults: ctx.runtime.DEFAULT_PROMPTS
    };
  }
};
