const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'windows',
  version: '1.0.0',
  channels: [
    IPC.APP_MINIMIZE,
    IPC.APP_RELAUNCH,
    IPC.APP_GET_VERSION
  ]
};
