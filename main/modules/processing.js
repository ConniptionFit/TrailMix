/** @module processing — multi-pass transcription pipeline + breadcrumbs */
module.exports = {
  id: 'processing',
  version: '1.0.0',
  dependencies: ['sessions'],
  channels: [
    'processing:get-jobs',
    'processing:retry',
    'processing:jobs-updated'
  ],
  register(ctx) {
    ctx.processing = {
      service: ctx.runtime.sessionProcessingService,
      enrich: ctx.runtime.runSessionEnrichment
    };
  },
  onReady(ctx) {
    ctx.runtime.broadcastProcessingProgress();
  }
};
