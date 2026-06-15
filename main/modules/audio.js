/** @module audio — capture, transcription stream, recording controls */
module.exports = {
  id: 'audio',
  version: '1.0.0',
  channels: [
    'audio:get-devices',
    'audio:start-recording',
    'audio:stop-recording',
    'audio:pause-recording',
    'audio:resume-recording',
    'audio:get-recording-status',
    'audio:on-transcription-update',
    'audio:on-transcription-correction',
    'audio:on-recording-status',
    'audio:on-levels',
    'audio:on-speaker-labels-updated'
  ],
  register(ctx) {
    ctx.recording = {
      start: ctx.runtime.startRecordingHandler,
      stop: ctx.runtime.stopRecordingHandler,
      pause: ctx.runtime.pauseRecordingHandler,
      resume: ctx.runtime.resumeCallTranscriptionHandler,
      getDevices: ctx.runtime.queryAudioDevices
    };
  }
};
