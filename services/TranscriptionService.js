const fs = require('fs');
const os = require('os');
const path = require('path');
const { Worker } = require('worker_threads');

const DEFAULT_MAX_QUEUE_DEPTH = 24;

function defaultMaxWorkers() {
  const cores = os.cpus()?.length || 2;
  // Second worker only helps when there are spare cores beyond in-chunk L/R Whisper.
  return cores >= 8 ? 2 : 1;
}

class TranscriptionService {
  constructor({
    maxQueueDepth = DEFAULT_MAX_QUEUE_DEPTH,
    maxWorkers = defaultMaxWorkers()
  } = {}) {
    this.queue = [];
    this.workerSlots = [];
    this.pendingJobs = new Map();
    this.onSegments = null;
    this.onIdle = null;
    this.onQueuePressure = null;
    this.onChunkError = null;
    this.maxQueueDepth = maxQueueDepth;
    this.maxWorkers = Math.max(1, Math.min(2, maxWorkers));
    this.droppedChunks = 0;
  }

  configure({ onSegments, onIdle, onQueuePressure, onChunkError }) {
    this.onSegments = onSegments;
    this.onIdle = onIdle;
    this.onQueuePressure = onQueuePressure;
    this.onChunkError = onChunkError;
  }

  get activeCount() {
    return this.workerSlots.filter((slot) => slot.busy).length;
  }

  get isProcessing() {
    return this.activeCount > 0;
  }

  whisperThreadBudget() {
    const cores = os.cpus()?.length || 2;
    // Split cores across concurrent workers and stereo Whisper processes.
    const denom = this.maxWorkers * 2;
    return Math.min(4, Math.max(1, Math.floor((cores - 1) / denom) || 1));
  }

  ensureWorkers() {
    while (this.workerSlots.length < this.maxWorkers) {
      const slot = { worker: null, busy: false };
      const worker = new Worker(path.join(__dirname, '../workers/transcription-worker.js'));

      worker.on('message', (message) => {
        if (message.type === 'chunk-complete') {
          this.handleChunkComplete(slot, message);
        } else if (message.type === 'chunk-error') {
          this.handleChunkError(slot, message);
        }
      });

      worker.on('error', (err) => {
        console.error('Transcription worker error:', err);
        this.failSlotJobs(slot, err.message || 'Transcription worker error');
        this.resetSlot(slot);
        this.processQueue();
      });

      worker.on('exit', (code) => {
        if (code !== 0) {
          console.error(`Transcription worker exited with code ${code}`);
        }
        this.failSlotJobs(slot, `Transcription worker exited with code ${code}`);
        slot.worker = null;
        slot.busy = false;
        this.workerSlots = this.workerSlots.filter((s) => s !== slot);
        this.processQueue();
        this.notifyIfIdle();
      });

      slot.worker = worker;
      this.workerSlots.push(slot);
    }
  }

  failSlotJobs(slot, error) {
    for (const [jobId, job] of this.pendingJobs.entries()) {
      if (job.slot !== slot) continue;
      this.pendingJobs.delete(jobId);
      slot.busy = false;
      if (this.onChunkError) {
        this.onChunkError({
          chunkIndex: job.chunkIndex,
          error
        });
      }
    }
  }

  resetSlot(slot) {
    if (slot.worker) {
      slot.worker.terminate().catch(() => {});
      slot.worker = null;
    }
    slot.busy = false;
    this.workerSlots = this.workerSlots.filter((s) => s !== slot);
  }

  resetWorker() {
    for (const slot of [...this.workerSlots]) {
      this.resetSlot(slot);
    }
    this.workerSlots = [];
  }

  getQueueDepth() {
    return this.queue.length + this.activeCount;
  }

  emitQueuePressure(extra = {}) {
    if (!this.onQueuePressure) return;
    this.onQueuePressure({
      depth: this.getQueueDepth(),
      maxDepth: this.maxQueueDepth,
      droppedChunks: this.droppedChunks,
      workers: this.maxWorkers,
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
    this.ensureWorkers();

    for (const slot of this.workerSlots) {
      if (slot.busy || !slot.worker || this.queue.length === 0) continue;

      const job = this.queue.shift();
      slot.busy = true;
      this.pendingJobs.set(job.jobId, { ...job, slot });

      slot.worker.postMessage({
        type: 'process-chunk',
        threadBudget: this.whisperThreadBudget(),
        ...job
      });
    }

    this.emitQueuePressure();
  }

  handleChunkComplete(slot, message) {
    const job = this.pendingJobs.get(message.jobId);
    this.pendingJobs.delete(message.jobId);
    slot.busy = false;

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

  handleChunkError(slot, message) {
    console.error(`Transcription failed for chunk ${message.chunkIndex}:`, message.error);
    this.pendingJobs.delete(message.jobId);
    slot.busy = false;
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
    for (const slot of this.workerSlots) {
      slot.busy = false;
    }
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
