/** @module updates — electron-updater lifecycle */
module.exports = {
  id: 'updates',
  version: '1.0.0',
  channels: [
    'updates:check',
    'updates:install',
    'updates:get-status',
    'updates:on-status'
  ],
  register(ctx) {
    ctx.updates = {
      service: ctx.runtime.updateService
    };
  }
};
