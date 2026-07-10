const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'tasks',
  version: '1.0.0',
  channels: [
    IPC.TASKS_GET,
    IPC.TASKS_TOGGLE,
    IPC.TASKS_DELETE,
    IPC.TASKS_SET_OMITTED,
    IPC.TASKS_DELETE_MULTIPLE
  ]
};
