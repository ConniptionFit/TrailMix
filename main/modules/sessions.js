const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'sessions',
  version: '1.0.0',
  channels: [
    IPC.SESSION_LOAD_MEETING,
    IPC.SESSION_SET_TITLE,
    IPC.SESSION_UPDATED
  ]
};
