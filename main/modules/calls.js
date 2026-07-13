/** @module calls — archive, encryption, import/export */
module.exports = {
  id: 'calls',
  version: '1.0.0',
  dependencies: ['sessions'],
  channels: [
    'calls:get-list',
    'calls:save',
    'calls:save-silently',
    'calls:load',
    'calls:decrypt',
    'calls:decrypt-multiple',
    'calls:delete',
    'calls:delete-multiple',
    'calls:merge',
    'calls:export',
    'calls:export-obsidian',
    'calls:find-related',
    'calls:open-file-location',
    'calls:resume-transcription',
    'calls:list-updated',
    'calls:session-summary-ready'
  ],
  register(ctx) {
    ctx.calls = {
      sync: ctx.runtime.syncDatabaseWithFiles,
      getDir: ctx.runtime.getCallsDir
    };
  }
};
