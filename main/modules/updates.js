const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'updates',
  version: '1.0.0',
  channels: [
    IPC.UPDATES_CHECK,
    IPC.UPDATES_INSTALL,
    IPC.UPDATES_GET_STATUS,
    IPC.UPDATES_ON_STATUS
  ]
};
