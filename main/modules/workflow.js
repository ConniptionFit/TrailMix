const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'workflow',
  version: '1.0.0',
  channels: [
    IPC.WORKFLOW_REGISTER,
    IPC.WORKFLOW_EMIT
  ]
};
