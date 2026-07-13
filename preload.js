const { IPC } = require('./lib/ipc-channels');
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  // App controls
  minimize: () => ipcRenderer.send(IPC.APP_MINIMIZE),
  relaunch: () => ipcRenderer.send(IPC.APP_RELAUNCH),
  getAppVersion: () => ipcRenderer.invoke(IPC.APP_GET_VERSION),

  // Audio devices & Recording
  getAudioDevices: () => ipcRenderer.invoke(IPC.AUDIO_GET_DEVICES),
  startRecording: (sessionId) => ipcRenderer.invoke(IPC.AUDIO_START_RECORDING, sessionId),
  stopRecording: () => ipcRenderer.invoke(IPC.AUDIO_STOP_RECORDING),
  getRecordingStatus: () => ipcRenderer.invoke(IPC.AUDIO_GET_RECORDING_STATUS),
  onTranscriptionUpdate: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_TRANSCRIPTION_UPDATE, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_TRANSCRIPTION_UPDATE, subscription);
  },
  onTranscriptionCorrection: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_TRANSCRIPTION_CORRECTION, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_TRANSCRIPTION_CORRECTION, subscription);
  },
  onRecordingStatus: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_RECORDING_STATUS, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_RECORDING_STATUS, subscription);
  },
  onSpeakerLabelsUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_SPEAKER_LABELS, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_SPEAKER_LABELS, subscription);
  },
  onAudioLevels: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_LEVELS, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_LEVELS, subscription);
  },
  onQueuePressure: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_QUEUE_PRESSURE, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_QUEUE_PRESSURE, subscription);
  },
  onTranscriptionError: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.AUDIO_ON_TRANSCRIPTION_ERROR, subscription);
    return () => ipcRenderer.removeListener(IPC.AUDIO_ON_TRANSCRIPTION_ERROR, subscription);
  },
  onCallListUpdated: (callback) => {
    const subscription = (event) => callback();
    ipcRenderer.on(IPC.CALLS_LIST_UPDATED, subscription);
    return () => ipcRenderer.removeListener(IPC.CALLS_LIST_UPDATED, subscription);
  },

  // Hub / meeting windows
  createMeeting: () => ipcRenderer.invoke(IPC.MEETINGS_NEW),
  openMeeting: (sessionId, options) => ipcRenderer.invoke(IPC.MEETINGS_OPEN, sessionId, options || {}),
  focusMeeting: (sessionId) => ipcRenderer.invoke(IPC.MEETINGS_FOCUS, sessionId),
  loadMeetingSession: (sessionId) => ipcRenderer.invoke(IPC.SESSION_LOAD_MEETING, sessionId),
  setSessionTitle: (sessionId, title, userEdited = true) =>
    ipcRenderer.invoke(IPC.SESSION_SET_TITLE, sessionId, title, userEdited),
  onSessionUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.SESSION_UPDATED, subscription);
    return () => ipcRenderer.removeListener(IPC.SESSION_UPDATED, subscription);
  },
  onFocusSegment: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.SESSION_FOCUS_SEGMENT, subscription);
    return () => ipcRenderer.removeListener(IPC.SESSION_FOCUS_SEGMENT, subscription);
  },

  // Models & Hardware
  getSpecs: () => ipcRenderer.invoke(IPC.MODELS_GET_SPECS),
  getOllamaModels: () => ipcRenderer.invoke(IPC.MODELS_GET_OLLAMA),
  downloadWhisper: (modelName) => ipcRenderer.invoke(IPC.MODELS_DOWNLOAD_WHISPER, modelName),
  onDownloadProgress: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.MODELS_ON_DOWNLOAD_PROGRESS, subscription);
    return () => ipcRenderer.removeListener(IPC.MODELS_ON_DOWNLOAD_PROGRESS, subscription);
  },

  // File operations & Encryption
  getCallList: () => ipcRenderer.invoke(IPC.CALLS_GET_LIST),
  searchSessions: (term, options) => ipcRenderer.invoke(IPC.SEARCH_SESSIONS, term, options),
  saveCall: (callData, password) => ipcRenderer.invoke(IPC.CALLS_SAVE, callData, password),
  loadCall: (filePath, password) => ipcRenderer.invoke(IPC.CALLS_LOAD, filePath, password),
  decryptCall: (filePath, password) => ipcRenderer.invoke(IPC.CALLS_DECRYPT, filePath, password),
  decryptMultipleCalls: (sessionIds, password) => ipcRenderer.invoke(IPC.CALLS_DECRYPT_MULTIPLE, sessionIds, password),

  // Settings
  getSettings: () => ipcRenderer.invoke(IPC.SETTINGS_GET),
  saveSettings: (newSettings) => ipcRenderer.invoke(IPC.SETTINGS_SAVE, newSettings),
  selectDirectory: () => ipcRenderer.invoke(IPC.SETTINGS_SELECT_DIRECTORY),
  getDefaultPrompts: () => ipcRenderer.invoke(IPC.SETTINGS_GET_DEFAULT_PROMPTS),

  // Software updates
  checkForUpdates: (options) => ipcRenderer.invoke(IPC.UPDATES_CHECK, options),
  installUpdate: () => ipcRenderer.invoke(IPC.UPDATES_INSTALL),
  getUpdateStatus: () => ipcRenderer.invoke(IPC.UPDATES_GET_STATUS),
  onUpdateStatus: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.UPDATES_ON_STATUS, subscription);
    return () => ipcRenderer.removeListener(IPC.UPDATES_ON_STATUS, subscription);
  },

  // Live Pause & Resume / Resume Past Sessions
  pauseRecording: () => ipcRenderer.invoke(IPC.AUDIO_PAUSE_RECORDING),
  resumeRecording: () => ipcRenderer.invoke(IPC.AUDIO_RESUME_RECORDING),
  resumeCallTranscription: (sessionId) => ipcRenderer.invoke(IPC.CALLS_RESUME_TRANSCRIPTION, sessionId),

  // Timeline Tasks
  getTasks: () => ipcRenderer.invoke(IPC.TASKS_GET),
  toggleTask: (taskId) => ipcRenderer.invoke(IPC.TASKS_TOGGLE, taskId),
  deleteTask: (taskId) => ipcRenderer.invoke(IPC.TASKS_DELETE, taskId),
  setTaskOmitted: (taskId, omitted) => ipcRenderer.invoke(IPC.TASKS_SET_OMITTED, taskId, omitted),
  deleteMultipleTasks: (taskIds) => ipcRenderer.invoke(IPC.TASKS_DELETE_MULTIPLE, taskIds),

  // Call management operations
  openFileLocation: (filePath) => ipcRenderer.invoke(IPC.CALLS_OPEN_FILE_LOCATION, filePath),
  deleteCall: (filePath) => ipcRenderer.invoke(IPC.CALLS_DELETE, filePath),
  deleteMultipleCalls: (filePaths) => ipcRenderer.invoke(IPC.CALLS_DELETE_MULTIPLE, filePaths),
  mergeCalls: (filePaths) => ipcRenderer.invoke(IPC.CALLS_MERGE, filePaths),
  exportCalls: (filePaths) => ipcRenderer.invoke(IPC.CALLS_EXPORT, filePaths),
  findRelatedCalls: (filePath) => ipcRenderer.invoke(IPC.CALLS_FIND_RELATED, filePath),

  onSessionSummaryReady: (callback) => {
    const subscription = (event, session) => callback(session);
    ipcRenderer.on(IPC.CALLS_SESSION_SUMMARY_READY, subscription);
    return () => ipcRenderer.removeListener(IPC.CALLS_SESSION_SUMMARY_READY, subscription);
  },

  // Chat Agent
  chatQuery: (queryOrPayload, transcriptText) => {
    if (queryOrPayload && typeof queryOrPayload === 'object') {
      return ipcRenderer.invoke(IPC.CHAT_QUERY, queryOrPayload);
    }
    return ipcRenderer.invoke(IPC.CHAT_QUERY, queryOrPayload, transcriptText);
  },
  getChatRecipes: () => ipcRenderer.invoke(IPC.CHAT_GET_RECIPES),
  saveCallSilently: (callData, password) => ipcRenderer.invoke(IPC.CALLS_SAVE_SILENTLY, callData, password),
  mixEnhance: (payload) => ipcRenderer.invoke(IPC.CHAT_MIX_ENHANCE, payload),
  onLlmStreamChunk: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.LLM_STREAM_CHUNK, subscription);
    return () => ipcRenderer.removeListener(IPC.LLM_STREAM_CHUNK, subscription);
  },
  cancelLlmStream: (requestId) => ipcRenderer.invoke(IPC.LLM_CANCEL, requestId),

  getProcessingJobs: () => ipcRenderer.invoke(IPC.PROCESSING_GET_JOBS),
  retryProcessing: (sessionId) => ipcRenderer.invoke(IPC.PROCESSING_RETRY, sessionId),
  onProcessingJobsUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.PROCESSING_JOBS_UPDATED, subscription);
    return () => ipcRenderer.removeListener(IPC.PROCESSING_JOBS_UPDATED, subscription);
  },
  onProcessingTranscriptUpdated: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.PROCESSING_TRANSCRIPT_UPDATED, subscription);
    return () => ipcRenderer.removeListener(IPC.PROCESSING_TRANSCRIPT_UPDATED, subscription);
  },
  onNeedsEncryptionPassword: (callback) => {
    const subscription = (event, data) => callback(data);
    ipcRenderer.on(IPC.SESSION_NEEDS_ENCRYPTION_PASSWORD, subscription);
    return () => ipcRenderer.removeListener(IPC.SESSION_NEEDS_ENCRYPTION_PASSWORD, subscription);
  },

  // Obsidian Export (v0.3)
  exportObsidian: (folderId, exportDir) => ipcRenderer.invoke(IPC.CALLS_EXPORT_OBSIDIAN, folderId, exportDir),

  registerWorkflowTrigger: (eventName, trigger) => ipcRenderer.invoke(IPC.WORKFLOW_REGISTER, eventName, trigger)
});
