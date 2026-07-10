const { IPC } = require('./lib/ipc-channels');
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  // App controls
  minimize: () => ipcRenderer.send('app:minimize'),
  relaunch: () => ipcRenderer.send('app:relaunch'),
  getAppVersion: () => ipcRenderer.invoke('app:get-version'),

  // Audio devices & Recording
  getAudioDevices: () => ipcRenderer.invoke('audio:get-devices'),
  startRecording: (sessionId) => ipcRenderer.invoke('audio:start-recording', sessionId),
  stopRecording: () => ipcRenderer.invoke('audio:stop-recording'),
  getRecordingStatus: () => ipcRenderer.invoke('audio:get-recording-status'),
  onTranscriptionUpdate: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-transcription-update', subscription);
    return () => ipcRenderer.removeListener('audio:on-transcription-update', subscription);
  },
  onTranscriptionCorrection: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-transcription-correction', subscription);
    return () => ipcRenderer.removeListener('audio:on-transcription-correction', subscription);
  },
  onRecordingStatus: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-recording-status', subscription);
    return () => ipcRenderer.removeListener('audio:on-recording-status', subscription);
  },
  onSpeakerLabelsUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-speaker-labels-updated', subscription);
    return () => ipcRenderer.removeListener('audio:on-speaker-labels-updated', subscription);
  },
  onAudioLevels: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-levels', subscription);
    return () => ipcRenderer.removeListener('audio:on-levels', subscription);
  },
  onQueuePressure: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-queue-pressure', subscription);
    return () => ipcRenderer.removeListener('audio:on-queue-pressure', subscription);
  },
  onTranscriptionError: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('audio:on-transcription-error', subscription);
    return () => ipcRenderer.removeListener('audio:on-transcription-error', subscription);
  },
  onCallListUpdated: (callback) => {
    const subscription = (event) => callback();
    ipcRenderer.on('calls:list-updated', subscription);
    return () => ipcRenderer.removeListener('calls:list-updated', subscription);
  },

  // Hub / meeting windows
  createMeeting: () => ipcRenderer.invoke(IPC.MEETINGS_NEW),
  openMeeting: (sessionId, options) => ipcRenderer.invoke(IPC.MEETINGS_OPEN, sessionId, options || {}),
  focusMeeting: (sessionId) => ipcRenderer.invoke('meetings:focus', sessionId),
  loadMeetingSession: (sessionId) => ipcRenderer.invoke('session:load-meeting', sessionId),
  setSessionTitle: (sessionId, title, userEdited = true) =>
    ipcRenderer.invoke('session:set-title', sessionId, title, userEdited),
  onSessionUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('session:updated', subscription);
    return () => ipcRenderer.removeListener('session:updated', subscription);
  },
  onFocusSegment: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('session:focus-segment', subscription);
    return () => ipcRenderer.removeListener('session:focus-segment', subscription);
  },

  // Models & Hardware
  getSpecs: () => ipcRenderer.invoke('models:get-specs'),
  getOllamaModels: () => ipcRenderer.invoke('models:get-ollama-models'),
  downloadWhisper: (modelName) => ipcRenderer.invoke('models:download-whisper', modelName),
  onDownloadProgress: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('models:on-download-progress', subscription);
    return () => ipcRenderer.removeListener('models:on-download-progress', subscription);
  },

  // File operations & Encryption
  getCallList: () => ipcRenderer.invoke(IPC.CALLS_GET_LIST),
  searchSessions: (term, options) => ipcRenderer.invoke(IPC.SEARCH_SESSIONS, term, options),
  saveCall: (callData, password) => ipcRenderer.invoke(IPC.CALLS_SAVE, callData, password),
  loadCall: (filePath, password) => ipcRenderer.invoke(IPC.CALLS_LOAD, filePath, password),
  decryptCall: (filePath, password) => ipcRenderer.invoke('calls:decrypt', filePath, password),
  decryptMultipleCalls: (sessionIds, password) => ipcRenderer.invoke('calls:decrypt-multiple', sessionIds, password),

  // Settings
  getSettings: () => ipcRenderer.invoke(IPC.SETTINGS_GET),
  saveSettings: (newSettings) => ipcRenderer.invoke(IPC.SETTINGS_SAVE, newSettings),
  selectDirectory: () => ipcRenderer.invoke('settings:select-directory'),
  getDefaultPrompts: () => ipcRenderer.invoke('settings:get-default-prompts'),

  // Software updates
  checkForUpdates: (options) => ipcRenderer.invoke('updates:check', options),
  installUpdate: () => ipcRenderer.invoke('updates:install'),
  getUpdateStatus: () => ipcRenderer.invoke('updates:get-status'),
  onUpdateStatus: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('updates:on-status', subscription);
    return () => ipcRenderer.removeListener('updates:on-status', subscription);
  },

  // Live Pause & Resume / Resume Past Sessions
  pauseRecording: () => ipcRenderer.invoke('audio:pause-recording'),
  resumeRecording: () => ipcRenderer.invoke('audio:resume-recording'),
  resumeCallTranscription: (sessionId) => ipcRenderer.invoke('calls:resume-transcription', sessionId),

  // Timeline Tasks
  getTasks: () => ipcRenderer.invoke('tasks:get'),
  toggleTask: (taskId) => ipcRenderer.invoke('tasks:toggle', taskId),
  deleteTask: (taskId) => ipcRenderer.invoke('tasks:delete', taskId),
  setTaskOmitted: (taskId, omitted) => ipcRenderer.invoke('tasks:set-omitted', taskId, omitted),
  deleteMultipleTasks: (taskIds) => ipcRenderer.invoke('tasks:delete-multiple', taskIds),

  // Call management operations
  openFileLocation: (filePath) => ipcRenderer.invoke('calls:open-file-location', filePath),
  deleteCall: (filePath) => ipcRenderer.invoke('calls:delete', filePath),
  deleteMultipleCalls: (filePaths) => ipcRenderer.invoke('calls:delete-multiple', filePaths),
  mergeCalls: (filePaths) => ipcRenderer.invoke('calls:merge', filePaths),
  exportCalls: (filePaths) => ipcRenderer.invoke('calls:export', filePaths),
  findRelatedCalls: (filePath) => ipcRenderer.invoke('calls:find-related', filePath),

  onSessionSummaryReady: (callback) => {
    const subscription = (event, session) => callback(session);
    ipcRenderer.on('calls:session-summary-ready', subscription);
    return () => ipcRenderer.removeListener('calls:session-summary-ready', subscription);
  },

  // Chat Agent
  chatQuery: (queryOrPayload, transcriptText) => {
    if (queryOrPayload && typeof queryOrPayload === 'object') {
      return ipcRenderer.invoke(IPC.CHAT_QUERY, queryOrPayload);
    }
    return ipcRenderer.invoke(IPC.CHAT_QUERY, queryOrPayload, transcriptText);
  },
  getChatRecipes: () => ipcRenderer.invoke('chat:get-recipes'),
  saveCallSilently: (callData, password) => ipcRenderer.invoke(IPC.CALLS_SAVE_SILENTLY, callData, password),
  mixEnhance: (payload) => ipcRenderer.invoke('chat:mix-enhance', payload),
  onLlmStreamChunk: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('llm:stream-chunk', subscription);
    return () => ipcRenderer.removeListener('llm:stream-chunk', subscription);
  },
  cancelLlmStream: (requestId) => ipcRenderer.invoke(IPC.LLM_CANCEL, requestId),

  getProcessingJobs: () => ipcRenderer.invoke(IPC.PROCESSING_GET_JOBS),
  retryProcessing: (sessionId) => ipcRenderer.invoke(IPC.PROCESSING_RETRY, sessionId),
  onProcessingJobsUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('processing:jobs-updated', subscription);
    return () => ipcRenderer.removeListener('processing:jobs-updated', subscription);
  },
  onProcessingTranscriptUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('processing:transcript-updated', subscription);
    return () => ipcRenderer.removeListener('processing:transcript-updated', subscription);
  },
  onNeedsEncryptionPassword: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('session:needs-encryption-password', subscription);
    return () => ipcRenderer.removeListener('session:needs-encryption-password', subscription);
  },

  // Folders & Obsidian Export (v0.3)
  getFolders: () => ipcRenderer.invoke('folders:get'),
  createFolder: (payload) => ipcRenderer.invoke('folders:create', payload),
  updateFolder: (folder) => ipcRenderer.invoke('folders:update', folder),
  deleteFolder: (id) => ipcRenderer.invoke('folders:delete', id),
  moveToFolder: (sessionId, folderId) => ipcRenderer.invoke('calls:move-to-folder', sessionId, folderId),
  exportObsidian: (folderId, exportDir) => ipcRenderer.invoke('calls:export-obsidian', folderId, exportDir),

  registerWorkflowTrigger: (eventName, trigger) => ipcRenderer.invoke('workflow:register-trigger', eventName, trigger)
});
