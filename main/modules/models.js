const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'models',
  version: '1.0.0',
  channels: [
    IPC.MODELS_GET_SPECS,
    IPC.MODELS_GET_OLLAMA,
    IPC.MODELS_DOWNLOAD_WHISPER,
    IPC.MODELS_ON_DOWNLOAD_PROGRESS
  ]
};
