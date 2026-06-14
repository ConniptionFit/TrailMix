const path = require('path');
const { Worker } = require('worker_threads');

class TranscriptionService {
  constructor() {
    this.queue = [];
    this.isProcessing = false;
    this.worker = null;
    this.pendingJobs = new Map();
    this.onSegments = null;
    this.onIdle = null;
  }

  configure({ onSegments, onIdle }) {
    this.onSegments = onSegments;
    this.onIdle = onIdle;
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
      this.worker = null;
    });
  }

  resetWorker() {
    if (this.worker) {
      this.worker.terminate().catch(() => {});
      this.worker = null;
    }
  }

  enqueueChunk({ chunkPath, chunkIndex, whisperDir, selectedModel, aecMode = 'off' }) {
    const jobId = `${chunkIndex}_${Date.now()}`;
    this.queue.push({
      jobId,
      chunkPath,
      chunkIndex,
      whisperDir,
      selectedModel,
      aecMode
    });
    this.processQueue();
  }

  processQueue() {
    if (this.isProcessing || this.queue.length === 0) return;

    this.ensureWorker();
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

    this.processQueue();
    this.notifyIfIdle();
  }

  handleChunkError(message) {
    console.error(`Transcription failed for chunk ${message.chunkIndex}:`, message.error);
    this.pendingJobs.delete(message.jobId);
    this.isProcessing = false;
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
