const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'processing',
  version: '1.0.0',
  channels: [
    IPC.PROCESSING_GET_JOBS,
    IPC.PROCESSING_RETRY,
    IPC.PROCESSING_JOBS_UPDATED
  ]
};
