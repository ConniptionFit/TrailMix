const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'settings',
  version: '1.0.0',
  channels: [
    IPC.SETTINGS_GET,
    IPC.SETTINGS_SAVE,
    IPC.SETTINGS_GET_DEFAULT_PROMPTS,
    IPC.SETTINGS_SELECT_DIRECTORY
  ]
};
