const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'folders',
  version: '1.0.0',
  channels: [
    IPC.FOLDERS_GET,
    IPC.FOLDERS_CREATE,
    IPC.FOLDERS_UPDATE,
    IPC.FOLDERS_DELETE
  ]
};
