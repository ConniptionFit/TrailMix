const fs = require('fs');
const path = require('path');
const { Worker } = require('worker_threads');

const DEFAULT_MAX_QUEUE_DEPTH = 24;

class TranscriptionService {
  constructor({ maxQueueDepth = DEFAULT_MAX_QUEUE_DEPTH } = {}) {
    this.queue = [];
    this.isProcessing = false;
    this.worker = null;
    this.pendingJobs = new Map();
    this.onSegments = null;
    this.onIdle = null;
    this.onQueuePressure = null;
    this.onChunkError = null;
    this.maxQueueDepth = maxQueueDepth;
    this.droppedChunks = 0;
  }

  configure({ onSegments, onIdle, onQueuePressure, onChunkError }) {
    this.onSegments = onSegments;
    this.onIdle = onIdle;
    this.onQueuePressure = onQueuePressure;
    this.onChunkError = onChunkError;
  }

  ensureWorker() {
    if (this.worker) return;

    this.worker = new Worker(path.join(__dirname, '../workers/transcription-worker.js'));

    this.worker.on('message', (message) => {
      if (message.type === 'chunk-complete') {
        this.handleChunkComplete(message);
      } else if (message.type === 'chunk-error') {
        this.handleChunkError(message);
      }
    });

    this.worker.on('error', (err) => {
      console.error('Transcription worker error:', err);
      this.resetWorker();
      this.finishCurrentJob();
    });

    this.worker.on('exit', (code) => {
      if (code !== 0) {
        console.error(`Transcription worker exited with code ${code}`);
      }
      // Fail any in-flight job so the queue cannot stall forever.
      if (this.pendingJobs.size > 0) {
        for (const [jobId, job] of this.pendingJobs.entries()) {
          this.pendingJobs.delete(jobId);
          if (this.onChunkError) {
            this.onChunkError({
              chunkIndex: job.chunkIndex,
              error: `Transcription worker exited with code ${code}`
            });
          }
        }
      }
      this.worker = null;
      this.isProcessing = false;
      this.processQueue();
      this.notifyIfIdle();
    });
  }

  resetWorker() {
    if (this.worker) {
      this.worker.terminate().catch(() => {});
      this.worker = null;
    }
  }

  getQueueDepth() {
    return this.queue.length + (this.isProcessing ? 1 : 0);
  }

  emitQueuePressure(extra = {}) {
    if (!this.onQueuePressure) return;
    this.onQueuePressure({
      depth: this.getQueueDepth(),
      maxDepth: this.maxQueueDepth,
      droppedChunks: this.droppedChunks,
      ...extra
    });
  }

  enqueueChunk({ chunkPath, chunkIndex, whisperDir, selectedModel, aecMode = 'off' }) {
    // Backpressure: when Whisper falls behind realtime, drop oldest queued chunks
    // (keep the newest audio) so disk/RAM stay bounded and lag does not grow forever.
    while (this.queue.length >= this.maxQueueDepth) {
      const dropped = this.queue.shift();
      this.droppedChunks += 1;
      console.warn(
        `Transcription queue full (${this.maxQueueDepth}); dropping chunk ${dropped.chunkIndex}`
      );
      if (dropped?.chunkPath) {
        fs.promises.unlink(dropped.chunkPath).catch(() => {});
      }
      this.emitQueuePressure({ droppedChunkIndex: dropped.chunkIndex });
    }

    const jobId = `${chunkIndex}_${Date.now()}`;
    this.queue.push({
      jobId,
      chunkPath,
      chunkIndex,
      whisperDir,
      selectedModel,
      aecMode
    });
    this.emitQueuePressure();
    this.processQueue();
  }

  processQueue() {
    if (this.isProcessing || this.queue.length === 0) return;

    this.ensureWorker();
    if (!this.worker) return;

    this.isProcessing = true;

    const job = this.queue.shift();
    this.pendingJobs.set(job.jobId, job);

    this.worker.postMessage({
      type: 'process-chunk',
      ...job
    });
  }

  handleChunkComplete(message) {
    const job = this.pendingJobs.get(message.jobId);
    this.pendingJobs.delete(message.jobId);
    this.isProcessing = false;

    if (job && this.onSegments) {
      this.onSegments({
        chunkIndex: message.chunkIndex,
        segments: message.segments || []
      });
    }

    this.emitQueuePressure();
    this.processQueue();
    this.notifyIfIdle();
  }

  handleChunkError(message) {
    console.error(`Transcription failed for chunk ${message.chunkIndex}:`, message.error);
    this.pendingJobs.delete(message.jobId);
    this.isProcessing = false;
    if (this.onChunkError) {
      this.onChunkError({
        chunkIndex: message.chunkIndex,
        error: message.error
      });
    }
    this.emitQueuePressure();
    this.processQueue();
    this.notifyIfIdle();
  }

  finishCurrentJob() {
    this.isProcessing = false;
    this.processQueue();
    this.notifyIfIdle();
  }

  notifyIfIdle() {
    if (!this.isProcessing && this.queue.length === 0 && this.onIdle) {
      this.onIdle();
    }
  }

  clearQueue() {
    this.queue = [];
    this.pendingJobs.clear();
    this.isProcessing = false;
    this.emitQueuePressure();
  }

  waitForIdle(timeoutMs = 10000) {
    return new Promise((resolve) => {
      const startedAt = Date.now();

      const checkIdle = () => {
        if (!this.isProcessing && this.queue.length === 0) {
          resolve(true);
          return;
        }

        if (Date.now() - startedAt >= timeoutMs) {
          resolve(false);
          return;
        }

        setTimeout(checkIdle, 250);
      };

      checkIdle();
    });
  }

  shutdown() {
    this.clearQueue();
    this.resetWorker();
  }
}

module.exports = {
  TranscriptionService
};
