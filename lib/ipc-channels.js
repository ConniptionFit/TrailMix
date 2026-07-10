/**
 * Centralized IPC channel names for main ↔ renderer communication.
 * Use these constants to keep contracts stable across refactors.
 */
const IPC = {
  // App
  APP_MINIMIZE: 'app:minimize',
  APP_RELAUNCH: 'app:relaunch',
  APP_GET_VERSION: 'app:get-version',

  // Audio & recording
  AUDIO_GET_DEVICES: 'audio:get-devices',
  AUDIO_START_RECORDING: 'audio:start-recording',
  AUDIO_STOP_RECORDING: 'audio:stop-recording',
  AUDIO_PAUSE_RECORDING: 'audio:pause-recording',
  AUDIO_RESUME_RECORDING: 'audio:resume-recording',
  AUDIO_GET_RECORDING_STATUS: 'audio:get-recording-status',
  AUDIO_ON_TRANSCRIPTION_UPDATE: 'audio:on-transcription-update',
  AUDIO_ON_TRANSCRIPTION_CORRECTION: 'audio:on-transcription-correction',
  AUDIO_ON_RECORDING_STATUS: 'audio:on-recording-status',
  AUDIO_ON_LEVELS: 'audio:on-levels',
  AUDIO_ON_SPEAKER_LABELS: 'audio:on-speaker-labels-updated',
  AUDIO_ON_QUEUE_PRESSURE: 'audio:on-queue-pressure',
  AUDIO_ON_TRANSCRIPTION_ERROR: 'audio:on-transcription-error',

  // Settings
  SETTINGS_GET: 'settings:get',
  SETTINGS_SAVE: 'settings:save',
  SETTINGS_GET_DEFAULT_PROMPTS: 'settings:get-default-prompts',
  SETTINGS_SELECT_DIRECTORY: 'settings:select-directory',

  // Sessions & meetings
  SESSION_LOAD_MEETING: 'session:load-meeting',
  SESSION_SET_TITLE: 'session:set-title',
  SESSION_UPDATED: 'session:updated',
  SESSION_FOCUS_SEGMENT: 'session:focus-segment',
  MEETINGS_NEW: 'meetings:new',
  MEETINGS_OPEN: 'meetings:open',
  MEETINGS_FOCUS: 'meetings:focus',

  // Calls archive
  CALLS_GET_LIST: 'calls:get-list',
  SEARCH_SESSIONS: 'search:sessions',
  CALLS_SAVE: 'calls:save',
  CALLS_SAVE_SILENTLY: 'calls:save-silently',
  CALLS_LOAD: 'calls:load',
  CALLS_DECRYPT: 'calls:decrypt',
  CALLS_DECRYPT_MULTIPLE: 'calls:decrypt-multiple',
  CALLS_DELETE: 'calls:delete',
  CALLS_DELETE_MULTIPLE: 'calls:delete-multiple',
  CALLS_MERGE: 'calls:merge',
  CALLS_EXPORT: 'calls:export',
  CALLS_EXPORT_OBSIDIAN: 'calls:export-obsidian',
  CALLS_FIND_RELATED: 'calls:find-related',
  CALLS_OPEN_FILE_LOCATION: 'calls:open-file-location',
  CALLS_RESUME_TRANSCRIPTION: 'calls:resume-transcription',
  CALLS_MOVE_TO_FOLDER: 'calls:move-to-folder',
  CALLS_LIST_UPDATED: 'calls:list-updated',
  CALLS_SESSION_SUMMARY_READY: 'calls:session-summary-ready',

  // Chat & LLM
  CHAT_QUERY: 'chat:query',
  CHAT_MIX_ENHANCE: 'chat:mix-enhance',
  CHAT_GET_RECIPES: 'chat:get-recipes',
  LLM_STREAM_CHUNK: 'llm:stream-chunk',
  LLM_CANCEL: 'llm:cancel',

  // Folders
  FOLDERS_GET: 'folders:get',
  FOLDERS_CREATE: 'folders:create',
  FOLDERS_UPDATE: 'folders:update',
  FOLDERS_DELETE: 'folders:delete',

  // Tasks
  TASKS_GET: 'tasks:get',
  TASKS_TOGGLE: 'tasks:toggle',
  TASKS_DELETE: 'tasks:delete',
  TASKS_SET_OMITTED: 'tasks:set-omitted',
  TASKS_DELETE_MULTIPLE: 'tasks:delete-multiple',

  // Processing pipeline
  PROCESSING_GET_JOBS: 'processing:get-jobs',
  PROCESSING_RETRY: 'processing:retry',
  PROCESSING_JOBS_UPDATED: 'processing:jobs-updated',
  PROCESSING_TRANSCRIPT_UPDATED: 'processing:transcript-updated',

  // Models
  MODELS_GET_SPECS: 'models:get-specs',
  MODELS_GET_OLLAMA: 'models:get-ollama-models',
  MODELS_DOWNLOAD_WHISPER: 'models:download-whisper',
  MODELS_ON_DOWNLOAD_PROGRESS: 'models:on-download-progress',

  // Updates
  UPDATES_CHECK: 'updates:check',
  UPDATES_INSTALL: 'updates:install',
  UPDATES_GET_STATUS: 'updates:get-status',
  UPDATES_ON_STATUS: 'updates:on-status',

  // Workflow
  WORKFLOW_REGISTER: 'workflow:register-trigger',
  WORKFLOW_EMIT: 'workflow:emit'
};

module.exports = { IPC };
