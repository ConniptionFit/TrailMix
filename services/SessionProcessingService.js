const { splitTranscriptIntoBlocks, batchBlocks } = require('../lib/processing-blocks');
const {
  getPrecisionDiarizationSystemPrompt,
  getContextualIdentificationSystemPrompt,
  buildPrecisionPrompt,
  buildIdentificationPrompt,
  applyClusterMappingToBlock,
  applyGlobalSpeakerMap
} = require('../lib/speaker-identification');
const { buildDiarizationTurns } = require('../lib/speaker-diarization');
const { parseLlmJsonResponse } = require('../lib/llm-utils');

const PROCESSING_STATUS = {
  DRAFT_FROZEN: 'draft_frozen',
  PRECISION_QUEUED: 'precision_queued',
  PRECISION_RUNNING: 'precision_running',
  IDENTIFICATION_QUEUED: 'identification_queued',
  IDENTIFICATION_RUNNING: 'identification_running',
  ENRICHMENT_RUNNING: 'enrichment_running',
  COMPLETE: 'complete',
  FAILED: 'failed'
};

const STATUS_LABELS = {
  [PROCESSING_STATUS.PRECISION_RUNNING]: 'Sifting the Mix…',
  [PROCESSING_STATUS.IDENTIFICATION_RUNNING]: 'Sorting the Rations…',
  [PROCESSING_STATUS.ENRICHMENT_RUNNING]: 'Packaging the Trail…',
  [PROCESSING_STATUS.FAILED]: 'Processing paused'
};

class SessionProcessingService {
  constructor() {
    this.dbRun = null;
    this.dbGet = null;
    this.dbAll = null;
    this.llmService = null;
    this.getSettings = null;
    this.loadSessionPayload = null;
    this.saveSessionPayload = null;
    this.onProgress = null;
    this.onTranscriptUpdated = null;
    this.onComplete = null;
    this.runEnrichment = null;
    this.queue = [];
    this.activeJobId = null;
    this.isProcessing = false;
  }

  configure(deps) {
    Object.assign(this, deps);
  }

  async initSchema() {
    await this.dbRun('PRAGMA journal_mode=WAL;');
    await this.dbRun(`CREATE TABLE IF NOT EXISTS session_processing_jobs (
      session_id TEXT PRIMARY KEY,
      status TEXT NOT NULL,
      pass INTEGER NOT NULL DEFAULT 1,
      block_index INTEGER NOT NULL DEFAULT 0,
      block_total INTEGER NOT NULL DEFAULT 0,
      speaker_map_json TEXT,
      clusters_json TEXT,
      error_message TEXT,
      started_at TEXT,
      updated_at TEXT NOT NULL,
      completed_at TEXT,
      FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
    )`);
    await this.dbRun(`CREATE TABLE IF NOT EXISTS session_processing_breadcrumbs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id TEXT NOT NULL,
      pass INTEGER NOT NULL,
      block_index INTEGER NOT NULL,
      payload_json TEXT NOT NULL,
      created_at TEXT NOT NULL,
      FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
    )`);
    await this.dbRun(`CREATE INDEX IF NOT EXISTS idx_breadcrumbs_session
      ON session_processing_breadcrumbs(session_id, pass, block_index)`);
  }

  async recoverInterruptedJobs() {
    const rows = await this.dbAll(
      `SELECT session_id, status FROM session_processing_jobs
       WHERE status NOT IN (?, ?)`,
      [PROCESSING_STATUS.COMPLETE, PROCESSING_STATUS.DRAFT_FROZEN]
    );

    for (const row of rows) {
      const resumeStatus = row.status.includes('identification')
        ? PROCESSING_STATUS.IDENTIFICATION_QUEUED
        : PROCESSING_STATUS.PRECISION_QUEUED;
      await this.dbRun(
        `UPDATE session_processing_jobs SET status = ?, updated_at = ? WHERE session_id = ?`,
        [resumeStatus, new Date().toISOString(), row.session_id]
      );
      this.enqueue(row.session_id);
    }
  }

  async enqueue(sessionId) {
    if (!this.queue.includes(sessionId)) {
      this.queue.push(sessionId);
    }
    this.pumpQueue();
  }

  async createJob(sessionId, blockTotal) {
    const now = new Date().toISOString();
    await this.dbRun(
      `INSERT OR REPLACE INTO session_processing_jobs
       (session_id, status, pass, block_index, block_total, speaker_map_json, clusters_json, started_at, updated_at)
       VALUES (?, ?, 2, 0, ?, '{}', '{}', ?, ?)`,
      [sessionId, PROCESSING_STATUS.PRECISION_QUEUED, blockTotal, now, now]
    );
    this.emitProgress(sessionId);
    await this.enqueue(sessionId);
  }

  pumpQueue() {
    if (this.isProcessing || this.queue.length === 0) return;
    const sessionId = this.queue.shift();
    this.processJob(sessionId).catch((err) => {
      console.error('Session processing failed', sessionId, err);
    });
  }

  async processJob(sessionId) {
    if (this.isProcessing) {
      this.queue.unshift(sessionId);
      return;
    }

    this.isProcessing = true;
    this.activeJobId = sessionId;

    try {
      const job = await this.dbGet(
        'SELECT * FROM session_processing_jobs WHERE session_id = ?',
        [sessionId]
      );
      if (!job || job.status === PROCESSING_STATUS.COMPLETE) return;

      const session = await this.loadSessionPayload(sessionId);
      if (!session?.transcript?.length) {
        await this.markComplete(sessionId);
        return;
      }

      const blocks = splitTranscriptIntoBlocks(session.transcript);
      if (job.block_total !== blocks.length) {
        await this.dbRun(
          'UPDATE session_processing_jobs SET block_total = ? WHERE session_id = ?',
          [blocks.length, sessionId]
        );
        job.block_total = blocks.length;
      }

      let startBlock = job.block_index || 0;
      let speakerMap = {};
      try {
        speakerMap = JSON.parse(job.speaker_map_json || '{}');
      } catch (_) {
        speakerMap = {};
      }

      let identificationStartBatch = 0;

      if ([PROCESSING_STATUS.PRECISION_QUEUED, PROCESSING_STATUS.PRECISION_RUNNING, PROCESSING_STATUS.DRAFT_FROZEN].includes(job.status)) {
        startBlock = await this.runPrecisionPass(sessionId, session, blocks, startBlock);
        speakerMap = await this.getSpeakerMapFromTranscript(session.transcript);
        await this.dbRun(
          `UPDATE session_processing_jobs SET status = ?, pass = 3, block_index = 0, speaker_map_json = ?, updated_at = ?
           WHERE session_id = ?`,
          [PROCESSING_STATUS.IDENTIFICATION_QUEUED, JSON.stringify(speakerMap), new Date().toISOString(), sessionId]
        );
        identificationStartBatch = 0;
      } else if ([PROCESSING_STATUS.IDENTIFICATION_QUEUED, PROCESSING_STATUS.IDENTIFICATION_RUNNING].includes(job.status)) {
        identificationStartBatch = job.block_index || 0;
      }

      const refreshed = await this.loadSessionPayload(sessionId);
      await this.runIdentificationPass(sessionId, refreshed, blocks, speakerMap, identificationStartBatch);
      await this.runEnrichmentPhase(sessionId);
      await this.markComplete(sessionId);
    } catch (err) {
      await this.dbRun(
        `UPDATE session_processing_jobs SET status = ?, error_message = ?, updated_at = ? WHERE session_id = ?`,
        [PROCESSING_STATUS.FAILED, err.message, new Date().toISOString(), sessionId]
      );
      this.emitProgress(sessionId);
      throw err;
    } finally {
      this.isProcessing = false;
      this.activeJobId = null;
      this.pumpQueue();
    }
  }

  async runPrecisionPass(sessionId, session, blocks, startBlock) {
    const settings = this.getSettings();
    const model = settings.selectedLlm;
    const systemPrompt = getPrecisionDiarizationSystemPrompt();
    let globalTurnOffset = 0;

    for (let i = 0; i < startBlock; i++) {
      globalTurnOffset += blocks[i].length;
    }

    await this.updateJobStatus(sessionId, PROCESSING_STATUS.PRECISION_RUNNING, 2, startBlock, blocks.length);

    for (let blockIndex = startBlock; blockIndex < blocks.length; blockIndex++) {
      const block = blocks[blockIndex].map((s) => ({ ...s }));
      const turns = buildDiarizationTurns(block).map((turn, idx) => ({
        ...turn,
        turnIndex: globalTurnOffset + idx + 1
      }));

      try {
        const response = await this.llmService.queryComplete(
          buildPrecisionPrompt(turns),
          model,
          systemPrompt
        );
        const mapping = parseLlmJsonResponse(response);
        applyClusterMappingToBlock(block, mapping, globalTurnOffset);
      } catch (err) {
        console.error(`Precision pass block ${blockIndex} failed`, err);
      }

      this.applyBlockToSession(session, blockIndex, block, blocks);
      await this.saveSessionPayload(sessionId, session);
      await this.writeBreadcrumb(sessionId, 2, blockIndex, {
        processedSegmentIds: block.map((s) => s.id),
        speakers: block.map((s) => s.speaker)
      });
      await this.dbRun(
        `UPDATE session_processing_jobs SET block_index = ?, updated_at = ? WHERE session_id = ?`,
        [blockIndex + 1, new Date().toISOString(), sessionId]
      );
      this.emitProgress(sessionId, blockIndex + 1, blocks.length);
      this.emitTranscriptUpdated(sessionId, session);

      globalTurnOffset += block.length;
      await this.yieldCpu();
    }

    return blocks.length;
  }

  async runIdentificationPass(sessionId, session, blocks, initialMap, startBatchIndex = 0) {
    const settings = this.getSettings();
    const model = settings.selectedLlm;
    const systemPrompt = getContextualIdentificationSystemPrompt(settings.userName);
    const batches = batchBlocks(blocks, 4);

    await this.updateJobStatus(sessionId, PROCESSING_STATUS.IDENTIFICATION_RUNNING, 3, 0, batches.length);

    let speakerMap = { ...initialMap };
    for (let batchIndex = startBatchIndex; batchIndex < batches.length; batchIndex++) {
      const batch = batches[batchIndex];
      const clusterSummary = batch.map((block) =>
        block.map((seg) => ({ speaker: seg.speaker, text: seg.text.slice(0, 200) }))
      );

      try {
        const response = await this.llmService.queryComplete(
          buildIdentificationPrompt(clusterSummary),
          model,
          systemPrompt
        );
        const mapping = parseLlmJsonResponse(response);
        speakerMap = { ...speakerMap, ...mapping };
        applyGlobalSpeakerMap(session.transcript, speakerMap);
      } catch (err) {
        console.error(`Identification batch ${batchIndex} failed`, err);
      }

      await this.saveSessionPayload(sessionId, session);
      await this.writeBreadcrumb(sessionId, 3, batchIndex, { speakerMap });
      await this.dbRun(
        `UPDATE session_processing_jobs SET block_index = ?, speaker_map_json = ?, updated_at = ? WHERE session_id = ?`,
        [batchIndex + 1, JSON.stringify(speakerMap), new Date().toISOString(), sessionId]
      );
      this.emitProgress(sessionId, batchIndex + 1, batches.length);
      this.emitTranscriptUpdated(sessionId, session);
      await this.yieldCpu();
    }
  }

  async runEnrichmentPhase(sessionId) {
    await this.updateJobStatus(sessionId, PROCESSING_STATUS.ENRICHMENT_RUNNING, 4, 0, 1);
    this.emitProgress(sessionId, 0, 1);
    if (this.runEnrichment) {
      await this.runEnrichment(sessionId);
    }
  }

  applyBlockToSession(session, blockIndex, processedBlock, allBlocks) {
    const ids = new Set(processedBlock.map((s) => s.id));
    let blockCursor = 0;
    session.transcript = session.transcript.map((segment) => {
      if (!ids.has(segment.id)) return segment;
      const updated = processedBlock[blockCursor];
      blockCursor += 1;
      return updated ? { ...segment, speaker: updated.speaker } : segment;
    });
  }

  getSpeakerMapFromTranscript(transcript) {
    const map = {};
    transcript.forEach((seg) => {
      if (seg.speaker && seg.speaker !== 'You' && seg.speaker.startsWith('Speaker_')) {
        map[seg.speaker] = map[seg.speaker] || null;
      }
    });
    return map;
  }

  async writeBreadcrumb(sessionId, pass, blockIndex, payload) {
    await this.dbRun(
      `INSERT INTO session_processing_breadcrumbs (session_id, pass, block_index, payload_json, created_at)
       VALUES (?, ?, ?, ?, ?)`,
      [sessionId, pass, blockIndex, JSON.stringify(payload), new Date().toISOString()]
    );
  }

  async updateJobStatus(sessionId, status, pass, blockIndex, blockTotal) {
    await this.dbRun(
      `UPDATE session_processing_jobs SET status = ?, pass = ?, block_index = ?, block_total = ?, updated_at = ?
       WHERE session_id = ?`,
      [status, pass, blockIndex, blockTotal, new Date().toISOString(), sessionId]
    );
    this.emitProgress(sessionId, blockIndex, blockTotal);
  }

  async markComplete(sessionId) {
    await this.dbRun(
      `UPDATE session_processing_jobs SET status = ?, completed_at = ?, updated_at = ? WHERE session_id = ?`,
      [PROCESSING_STATUS.COMPLETE, new Date().toISOString(), new Date().toISOString(), sessionId]
    );
    this.emitProgress(sessionId);
    if (this.onComplete) this.onComplete(sessionId);
  }

  async getJobMap() {
    const rows = await this.dbAll('SELECT * FROM session_processing_jobs');
    const map = {};
    rows.forEach((row) => {
      map[row.session_id] = {
        status: row.status,
        pass: row.pass,
        blockIndex: row.block_index,
        blockTotal: row.block_total,
        label: STATUS_LABELS[row.status] || null,
        progress: row.block_total > 0 ? Math.round((row.block_index / row.block_total) * 100) : 0,
        errorMessage: row.error_message
      };
    });
    return map;
  }

  emitProgress(sessionId, blockIndex, blockTotal) {
    if (this.onProgress) {
      this.onProgress({ sessionId, blockIndex, blockTotal });
    }
  }

  emitTranscriptUpdated(sessionId, session) {
    if (this.onTranscriptUpdated) {
      this.onTranscriptUpdated({ sessionId, session });
    }
  }

  yieldCpu() {
    return new Promise((resolve) => setImmediate(resolve));
  }
}

module.exports = {
  SessionProcessingService,
  PROCESSING_STATUS,
  STATUS_LABELS
};
