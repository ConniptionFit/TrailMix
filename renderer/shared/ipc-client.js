/**
 * Renderer-side IPC client — thin typed wrapper over preload bridge.
 * Extend here as new channels are added; keeps renderer decoupled from raw IPC names.
 */
(function initIpcClient(global) {
  if (!global.api) return;

  const api = global.api;

  global.trailmix = {
    version: '0.5.0',
    api,
    audio: {
      getDevices: () => api.getAudioDevices(),
      start: (sessionId) => api.startRecording(sessionId),
      stop: () => api.stopRecording(),
      pause: () => api.pauseRecording(),
      resume: (sessionId) => api.resumeRecording(sessionId),
      getStatus: () => api.getRecordingStatus(),
      onTranscription: (cb) => api.onTranscriptionUpdate(cb),
      onLevels: (cb) => api.onAudioLevels(cb),
      onStatus: (cb) => api.onRecordingStatus(cb)
    },
    sessions: {
      load: (id) => api.loadMeetingSession(id),
      onUpdated: (cb) => api.onSessionUpdated(cb)
    },
    settings: {
      get: () => api.getSettings(),
      save: (s) => api.saveSettings(s)
    },
    updates: {
      check: (opts) => api.checkForUpdates(opts),
      install: () => api.installUpdate(),
      getStatus: () => api.getUpdateStatus(),
      onStatus: (cb) => api.onUpdateStatus(cb)
    },
    chat: {
      query: (payload) => api.chatQuery(payload),
      onStream: (cb) => api.onLlmStreamChunk(cb)
    }
  };
})(window);
