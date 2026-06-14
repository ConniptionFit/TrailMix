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
  onCallListUpdated: (callback) => {
    const subscription = (event) => callback();
    ipcRenderer.on('calls:list-updated', subscription);
    return () => ipcRenderer.removeListener('calls:list-updated', subscription);
  },

  // Hub / meeting windows
  createMeeting: () => ipcRenderer.invoke('meetings:new'),
  openMeeting: (sessionId) => ipcRenderer.invoke('meetings:open', sessionId),
  focusMeeting: (sessionId) => ipcRenderer.invoke('meetings:focus', sessionId),
  loadMeetingSession: (sessionId) => ipcRenderer.invoke('session:load-meeting', sessionId),
  setSessionTitle: (sessionId, title, userEdited = true) =>
    ipcRenderer.invoke('session:set-title', sessionId, title, userEdited),
  onSessionUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('session:updated', subscription);
    return () => ipcRenderer.removeListener('session:updated', subscription);
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
  getCallList: () => ipcRenderer.invoke('calls:get-list'),
  saveCall: (callData, password) => ipcRenderer.invoke('calls:save', callData, password),
  loadCall: (filePath, password) => ipcRenderer.invoke('calls:load', filePath, password),
  decryptCall: (filePath, password) => ipcRenderer.invoke('calls:decrypt', filePath, password),

  // Settings
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (newSettings) => ipcRenderer.invoke('settings:save', newSettings),
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
  resumeCallTranscription: (filePath) => ipcRenderer.invoke('calls:resume-transcription', filePath),

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
      return ipcRenderer.invoke('chat:query', queryOrPayload);
    }
    return ipcRenderer.invoke('chat:query', queryOrPayload, transcriptText);
  },
  getChatRecipes: () => ipcRenderer.invoke('chat:get-recipes'),
  saveCallSilently: (callData) => ipcRenderer.invoke('calls:save-silently', callData),
  mixEnhance: (payload) => ipcRenderer.invoke('chat:mix-enhance', payload),
  onLlmStreamChunk: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on('llm:stream-chunk', subscription);
    return () => ipcRenderer.removeListener('llm:stream-chunk', subscription);
  },
  cancelLlmStream: (requestId) => ipcRenderer.invoke('llm:cancel', requestId),

  getProcessingJobs: () => ipcRenderer.invoke('processing:get-jobs'),
  retryProcessing: (sessionId) => ipcRenderer.invoke('processing:retry', sessionId),
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

  // Folders & Obsidian Export (v0.3)
  getFolders: () => ipcRenderer.invoke('folders:get'),
  createFolder: (payload) => ipcRenderer.invoke('folders:create', payload),
  updateFolder: (folder) => ipcRenderer.invoke('folders:update', folder),
  deleteFolder: (id) => ipcRenderer.invoke('folders:delete', id),
  moveToFolder: (sessionId, folderId) => ipcRenderer.invoke('calls:move-to-folder', sessionId, folderId),
  exportObsidian: (folderId, exportDir) => ipcRenderer.invoke('calls:export-obsidian', folderId, exportDir),

  registerWorkflowTrigger: (eventName, trigger) => ipcRenderer.invoke('workflow:register-trigger', eventName, trigger)
});
