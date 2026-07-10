const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'meetings',
  version: '1.0.0',
  channels: [
    IPC.MEETINGS_NEW,
    IPC.MEETINGS_OPEN,
    IPC.MEETINGS_FOCUS
  ]
};
