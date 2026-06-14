/**
 * Centralized IPC channel names for main ↔ renderer communication.
 * Use these constants to keep contracts stable across refactors.
 */
const IPC = {
  AUDIO_GET_DEVICES: 'audio:get-devices',
  AUDIO_START_RECORDING: 'audio:start-recording',
  AUDIO_STOP_RECORDING: 'audio:stop-recording',
  AUDIO_PAUSE_RECORDING: 'audio:pause-recording',
  AUDIO_RESUME_RECORDING: 'audio:resume-recording',
  AUDIO_ON_TRANSCRIPTION_UPDATE: 'audio:on-transcription-update',
  AUDIO_ON_RECORDING_STATUS: 'audio:on-recording-status',
  AUDIO_ON_LEVELS: 'audio:on-levels',
  AUDIO_ON_SPEAKER_LABELS: 'audio:on-speaker-labels-updated',

  CHAT_QUERY: 'chat:query',
  CHAT_MIX_ENHANCE: 'chat:mix-enhance',
  CHAT_GET_RECIPES: 'chat:get-recipes',

  FOLDERS_GET: 'folders:get',
  FOLDERS_CREATE: 'folders:create',
  FOLDERS_UPDATE: 'folders:update',
  FOLDERS_DELETE: 'folders:delete',

  LLM_STREAM_CHUNK: 'llm:stream-chunk',
  LLM_CANCEL: 'llm:cancel',

  WORKFLOW_REGISTER: 'workflow:register-trigger',
  WORKFLOW_EMIT: 'workflow:emit'
};

module.exports = { IPC };
