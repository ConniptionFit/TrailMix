const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  // App controls
  minimize: () => ipcRenderer.send('app:minimize'),
  relaunch: () => ipcRenderer.send('app:relaunch'),
  
  // Audio devices & Recording
  getAudioDevices: () => ipcRenderer.invoke('audio:get-devices'),
  startRecording: () => ipcRenderer.invoke('audio:start-recording'),
  stopRecording: () => ipcRenderer.invoke('audio:stop-recording'),
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
  onCallListUpdated: (callback) => {
    const subscription = (event) => callback();
    ipcRenderer.on('calls:list-updated', subscription);
    return () => ipcRenderer.removeListener('calls:list-updated', subscription);
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
  chatQuery: (query, transcriptText) => ipcRenderer.invoke('chat:query', query, transcriptText),
  saveCallSilently: (callData) => ipcRenderer.invoke('calls:save-silently', callData),
  mixEnhance: (jots, transcriptText) => ipcRenderer.invoke('chat:mix-enhance', jots, transcriptText),

  // Folders & Obsidian Export (v0.3)
  getFolders: () => ipcRenderer.invoke('folders:get'),
  createFolder: (name) => ipcRenderer.invoke('folders:create', name),
  deleteFolder: (id) => ipcRenderer.invoke('folders:delete', id),
  moveToFolder: (sessionId, folderId) => ipcRenderer.invoke('calls:move-to-folder', sessionId, folderId),
  exportObsidian: (folderId, exportDir) => ipcRenderer.invoke('calls:export-obsidian', folderId, exportDir)
});
