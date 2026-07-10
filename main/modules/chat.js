const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'chat',
  version: '1.0.0',
  channels: [
    IPC.CHAT_QUERY,
    IPC.CHAT_GET_RECIPES,
    IPC.CHAT_MIX_ENHANCE,
    IPC.LLM_STREAM_CHUNK,
    IPC.LLM_CANCEL
  ]
};
