const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, screen, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execSync, spawn } = require('child_process');
const encryption = require('../../encryption');
const { parseLlmJsonResponse, applyPromptTemplate } = require('../../lib/llm-utils');
const {
  getSpeakerDiarizationSystemPrompt,
  buildDiarizationTurns,
  buildDiarizationPrompt,
  applySpeakerLabelMapping,
  notifySpeakerLabelsUpdated
} = require('../../lib/speaker-diarization');
const { formatTaskRow, createTaskDbHelpers } = require('../../lib/task-db');
const { formatTimestamp } = require('../../lib/format-timestamp');
const { secureShredFile } = require('../../lib/secure-shred');
const { AudioCaptureService } = require('../../services/AudioCaptureService');
const { TranscriptionService } = require('../../services/TranscriptionService');
const { LLMInferenceService } = require('../../services/LLMInferenceService');
const { EnhanceNotesService } = require('../../services/EnhanceNotesService');
const { SessionProcessingService, PROCESSING_STATUS } = require('../../services/SessionProcessingService');
const { documentFromSession } = require('../../lib/editor-document');
const { coalesceIncomingSegments } = require('../../lib/transcript-coalesce');
const { splitTranscriptIntoBlocks } = require('../../lib/processing-blocks');
const { appEventBus } = require('../../lib/event-bus');
const { CHAT_RECIPES, buildRecipePrompt } = require('../../lib/chat-recipes');
const { WindowManager } = require('../../windows/WindowManager');
const { UpdateService } = require('../../services/UpdateService');
const { enableOsEchoCancellation, disableOsEchoCancellation } = require('../../lib/audio-echo-os');
const {
  getSession,
  setSession,
  deleteSession,
  createNewSession,
  applyAutoTitle,
  sessionRegistry
} = require('../../lib/session-registry');
const {
  ROOT_FOLDER_ID,
  UNCATEGORIZED_FOLDER_ID,
  scanStorageLayout,
  ensureFolderDir,
  moveSessionFile,
  moveStorageContents,
  resolveSessionFilePath,
  relativePathFromFolderId,
  isTrailFile
} = require('../../lib/storage-layout');
const { resolveWhisperCli } = require('../../lib/resolve-whisper-cli');
const { correctBleedInTranscript } = require('../../lib/transcript-bleed-correction');
const { createSessionFtsHelpers } = require('../../lib/session-fts');
const sqlite3 = require('sqlite3').verbose();

// XDG Compliant Database Path
const XDG_CONFIG_DIR = path.join(os.homedir(), '.config', 'trailmix');
if (!fs.existsSync(XDG_CONFIG_DIR)) {
  fs.mkdirSync(XDG_CONFIG_DIR, { recursive: true });
}
const DB_PATH = path.join(XDG_CONFIG_DIR, 'db.sqlite');
const db = new sqlite3.Database(DB_PATH);

// Promise-based SQL Wrappers
function dbRun(sql, params = []) {
  return new Promise((resolve, reject) => {
    db.run(sql, params, function (err) {
      if (err) reject(err);
      else resolve(this);
    });
  });
}

function dbAll(sql, params = []) {
  return new Promise((resolve, reject) => {
    db.all(sql, params, (err, rows) => {
      if (err) reject(err);
      else resolve(rows);
    });
  });
}

function dbGet(sql, params = []) {
  return new Promise((resolve, reject) => {
    db.get(sql, params, (err, row) => {
      if (err) reject(err);
      else resolve(row);
    });
  });
}

const {
  ensureFtsTables,
  upsertSessionFts,
  deleteSessionFts,
  searchSessionsFts,
  rebuildAllSessionsFts
} = createSessionFtsHelpers({ dbRun, dbAll, dbGet });

const { saveTaskToDb, getTasksListFromDb } = createTaskDbHelpers({ dbRun, dbAll });

// Application Paths
const PROJECT_DIR = path.join(__dirname, '..', '..');
const DATA_DIR = path.join(PROJECT_DIR, 'data');
const TEMP_DIR = path.join(DATA_DIR, 'temp_rec');
const SETTINGS_FILE = path.join(DATA_DIR, 'settings.json');
const WHISPER_DIR = path.join(PROJECT_DIR, 'bin', 'whisper.cpp');

const audioCaptureService = new AudioCaptureService({ tempDir: TEMP_DIR });
const transcriptionService = new TranscriptionService();
const llmService = new LLMInferenceService();
const enhanceNotesService = new EnhanceNotesService(llmService);
const sessionProcessingService = new SessionProcessingService();

async function loadSessionPayloadFromDb(sessionId) {
  const row = await dbGet('SELECT * FROM sessions WHERE id = ?', [sessionId]);
  if (!row) return null;

  if (row.encrypted) {
    const cachedKey = decryptionKeys.get(sessionId) || settings.encryptionPassword;
    if (!cachedKey) return null;
    const decrypted = encryption.decrypt(row.encrypted_payload, cachedKey);
    const session = JSON.parse(decrypted);
    session.folder_id = row.folder_id;
    return session;
  }

  try {
    const session = JSON.parse(row.encrypted_payload || '{}');
    session.folder_id = row.folder_id;
    return session;
  } catch (_) {
    return null;
  }
}

async function resolveSessionRecord(sessionId) {
  if (!sessionId || sessionId === 'live') return null;

  let session = getSession(sessionId);
  if (session) return { session, canonicalId: session.id };

  const directRow = await dbGet('SELECT * FROM sessions WHERE id = ?', [sessionId]);
  if (directRow?.encrypted) {
    const cachedKey = decryptionKeys.get(sessionId) || settings.encryptionPassword;
    if (!cachedKey) {
      return { requirePassword: true };
    }
  }

  session = await loadSessionPayloadFromDb(sessionId);
  if (session) {
    setSession(session);
    return { session, canonicalId: session.id };
  }

  for (const registered of sessionRegistry.values()) {
    if (!registered?.id) continue;
    if (registered.id === sessionId || registered.id.endsWith(`_${sessionId}`)) {
      setSession(registered);
      return { session: registered, canonicalId: registered.id };
    }
  }

  await syncDatabaseWithFiles();
  session = await loadSessionPayloadFromDb(sessionId);
  if (session) {
    setSession(session);
    return { session, canonicalId: session.id };
  }

  const rows = await dbAll('SELECT id, encrypted FROM sessions');
  const renamed = rows.find((row) => row.id.endsWith(`_${sessionId}`) || row.id.includes(sessionId));
  if (renamed) {
    if (renamed.encrypted) {
      const cachedKey = decryptionKeys.get(renamed.id) || settings.encryptionPassword;
      if (!cachedKey) {
        return { requirePassword: true };
      }
    }
    session = await loadSessionPayloadFromDb(renamed.id);
    if (session) {
      setSession(session);
      return { session, canonicalId: session.id };
    }
  }

  const callsDir = getCallsDir();
  const { trailFiles } = scanStorageLayout(callsDir);
  const trailMatch = trailFiles.find((file) => (
    file.sessionId === sessionId || file.sessionId.endsWith(`_${sessionId}`)
  ));
  if (trailMatch) {
    await upsertSessionFromTrailFile(trailMatch);
    session = await loadSessionPayloadFromDb(trailMatch.sessionId);
    if (session) {
      setSession(session);
      return { session, canonicalId: session.id };
    }
  }

  return null;
}

function broadcastTranscriptCorrection(sessionId, removedSegmentIds) {
  if (!sessionId || !removedSegmentIds?.length) return;
  broadcastToSession(sessionId, 'audio:on-transcription-correction', {
    sessionId,
    removedSegmentIds
  });
}

async function saveSessionPayloadToDb(sessionId, session) {
  if (!session) return;
  session.id = sessionId;
  await saveSessionToDbPromise(session);
}

function broadcastProcessingProgress() {
  sessionProcessingService.getJobMap().then((jobs) => {
    const hub = getHubWindow();
    if (hub) hub.webContents.send('processing:jobs-updated', jobs);
  });
}

sessionProcessingService.configure({
  dbRun,
  dbGet,
  dbAll,
  llmService,
  getSettings: () => settings,
  loadSessionPayload: loadSessionPayloadFromDb,
  saveSessionPayload: saveSessionPayloadToDb,
  onProgress: () => broadcastProcessingProgress(),
  onTranscriptUpdated: ({ sessionId, session }) => {
    setSession(session);
    broadcastToSession(sessionId, 'session:updated', session);
    broadcastProcessingProgress();
  },
  onComplete: (sessionId) => {
    broadcastProcessingProgress();
    const hub = getHubWindow();
    if (hub) hub.webContents.send('calls:list-updated');
  },
  runEnrichment: (sessionId) => runSessionEnrichment(sessionId)
});

function handleTranscriptionSegments({ chunkIndex, segments }) {
  if (!activeSession) return;
  if (!activeSession.transcript) activeSession.transcript = [];

  const rawSegments = segments.map((segment) => {
    const sessionOffsetMs = (sessionChunkOffset + chunkIndex) * 2000 + segment.fromMs;
    return {
      id: `${activeSession.id}_${sessionChunkOffset + chunkIndex}_${segment.speaker.replace(/\s+/g, '_')}_${segment.fromMs}`,
      timestampMs: sessionOffsetMs,
      timestamp: formatTimestamp(sessionOffsetMs),
      speaker: segment.speaker,
      text: segment.text,
      chunkIndex: sessionChunkOffset + chunkIndex,
      wallTimeMs: Date.now()
    };
  });

  const { transcript: mergedTranscript, emitted } = coalesceIncomingSegments(activeSession.transcript, rawSegments);
  const candidateSegmentIds = emitted
    .filter((segment) => {
      const speaker = String(segment.speaker || '').toLowerCase();
      return speaker === 'you' || speaker === 'me';
    })
    .map((segment) => segment.id);
  const { transcript: correctedTranscript, removedSegmentIds } = correctBleedInTranscript(mergedTranscript, {
    candidateSegmentIds
  });
  activeSession.transcript = correctedTranscript;

  const visibleEmissions = emitted.filter((segment) => !removedSegmentIds.includes(segment.id));

  visibleEmissions.forEach((segment) => {
    if (activeRecordingSessionId) {
      broadcastToSession(activeRecordingSessionId, 'audio:on-transcription-update', segment);
    }
  });

  if (removedSegmentIds.length > 0 && activeRecordingSessionId) {
    broadcastTranscriptCorrection(activeRecordingSessionId, removedSegmentIds);
  }

  if (visibleEmissions.length > 0 || removedSegmentIds.length > 0) {
    scheduleSilentSessionSave(activeSession);
  }
}

transcriptionService.configure({
  onSegments: handleTranscriptionSegments,
  onQueuePressure: (pressure) => {
    if (!activeRecordingSessionId) return;
    broadcastToSession(activeRecordingSessionId, 'audio:on-queue-pressure', pressure);
  },
  onChunkError: ({ chunkIndex, error }) => {
    if (!activeRecordingSessionId) return;
    broadcastToSession(activeRecordingSessionId, 'audio:on-transcription-error', {
      chunkIndex,
      error: String(error || 'Transcription failed')
    });
  }
});

audioCaptureService.configure({
  onChunkReady: ({ chunkPath, chunkIndex }) => {
    if (!isRecording && !isPaused && !flushingFinalChunks) return;
    transcriptionService.enqueueChunk({
      chunkPath,
      chunkIndex,
      whisperDir: WHISPER_DIR,
      selectedModel: settings.selectedModel,
      aecMode: (() => {
        if (!settings.enableNoiseCancellation) return 'off';
        if (settings.aecMode === 'app') return 'app';
        return 'guard';
      })()
    });
  },
  onError: () => {
    stopRecordingHandler();
  },
  onLevels: (levels) => {
    if (activeRecordingSessionId) {
      broadcastToSession(activeRecordingSessionId, 'audio:on-levels', levels);
    }
  }
});

// Ensure directories exist
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

function getCallsDir() {
  if (settings && settings.customStoragePath) {
    if (!fs.existsSync(settings.customStoragePath)) {
      try {
        fs.mkdirSync(settings.customStoragePath, { recursive: true });
      } catch (e) {
        console.error(`Failed to create custom directory: ${settings.customStoragePath}`, e);
      }
    }
    if (fs.existsSync(settings.customStoragePath)) {
      return settings.customStoragePath;
    }
  }
  const defaultPath = path.join(DATA_DIR, 'calls');
  if (!fs.existsSync(defaultPath)) {
    fs.mkdirSync(defaultPath, { recursive: true });
  }
  return defaultPath;
}

let callsDirWatcher = null;

function watchCallsDirectory() {
  if (callsDirWatcher) {
    callsDirWatcher.close();
    callsDirWatcher = null;
  }
  
  const targetDir = getCallsDir();
  let debounceTimer = null;
  try {
    callsDirWatcher = fs.watch(targetDir, { recursive: true }, (eventType, filename) => {
      if (!filename || !isTrailFile(filename)) return;
      console.log(`Directory change detected: ${eventType} on ${filename}`);
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        if (getHubWindow()) {
          getHubWindow().webContents.send('calls:list-updated');
        }
      }, 350);
    });
  } catch (err) {
    console.error(`Error watching directory ${targetDir}:`, err);
  }
}

// State Variables
let windowManager = null;
let updateService = new UpdateService();
let tray = null;
let isRecording = false;
let isPaused = false;
let flushingFinalChunks = false;
let sessionChunkOffset = 0;
let diarizationInterval = null;
let activeSession = null;
let activeRecordingSessionId = null;
let captureMicSourceOverride = null;
let decryptionKeys = new Map();
// Reuse salt across incremental encrypted saves for the same session (avoids PBKDF2 every tick).
const encryptionEnvelopes = new Map();

function getHubWindow() {
  return windowManager?.getHubWindow() || null;
}

function broadcastToSession(sessionId, channel, payload) {
  windowManager?.broadcastToMeeting(sessionId, channel, payload);
}

function broadcastRecordingStatus(extra = {}) {
  const sessionId = extra.sessionId ?? activeRecordingSessionId;
  const payload = {
    isRecording,
    isPaused,
    sessionId,
    ...extra
  };
  if (sessionId) {
    broadcastToSession(sessionId, 'audio:on-recording-status', payload);
  }
}

function getAecModeForCapture() {
  if (!settings.enableNoiseCancellation) return 'off';
  return settings.aecMode === 'app' ? 'app' : 'os';
}

function resolveAecMicSource(baseSource, sinkName) {
  if (!settings.enableNoiseCancellation || settings.aecMode !== 'os') {
    return baseSource;
  }
  const result = enableOsEchoCancellation({ micSource: baseSource, sinkName });
  if (result.ok && result.virtualSource) {
    captureMicSourceOverride = result.virtualSource;
    return result.virtualSource;
  }
  console.warn('OS echo cancellation unavailable, falling back:', result.error);
  return baseSource;
}

const DEFAULT_PROMPTS = {
  executive: `Act as an elite executive assistant and structural document compiler. Your task is to process a messy stream of real-time human shorthand ("User Jots") and an unedited text transcription of a meeting room, transforming them into a beautifully typeset, high-signal Markdown document.

### 1. Core Synthesis Philosophy (Augmented Notepad Framework)
- Human Intent is King: The User Jots serve as top-down attention filters. If a topic, side-track, or tangent exists in the raw transcript but was NOT anchored by a phrase, abbreviation, or word in the User Jots, you must completely suppress it. Do not generate information overload.
- Anchor-Based Expansion: For every line item or concept inside the User Jots, find its corresponding temporal or thematic window in the Raw Transcript. Expand the user's brief shorthand into explicit, polished technical mechanics, key quantitative metrics, and verified operational deadlines mentioned in that part of the conversation.
- Phonetic & Semantic Error Correction: Clean up human typos, misspelled names, or clunky abbreviations found in the User Jots by cross-referencing the phonetics and context of the surrounding transcript (e.g., if jots say "auth via okta workflows" and transcript talks about "Okta Workflows SCIM provisioning pipelines", use the accurate technical string).

### 2. Mandatory Structural Layout (Markdown Template)
Your final output must strictly mirror this format, using crisp bolding, native headers, and clean spacing. Do not include empty placeholders if a section yields no content based on the jots.

## [Meeting Title / Objective]
*Provide a concise 1-2 sentence context paragraph restating the primary operational purpose of the meeting, using customer/technical terms extracted from the text.*

### Key Discussion Points
*Convert the messy user jots into highly polished, structured, nested bullet points. Bold the leading concept of each primary bullet point.*
- **[Core Concept Area Name]:** Clear, factual summary detailing the exact mechanical update or status. Use precise quotes or direct metrics where helpful.
  - *Supporting Detail:* Sub-bullets showing dependencies or exact conditions discussed.

### Decisions & Agreements
*A definitive list of every resolution, alignment, architectural choice, or direction confirmed during the call.*
- **[Decision]:** Brief description of what was chosen + the rationale/owner.

### Action Items
*A strict task list. Every bullet item must have an owner, a clear deliverable, and a deadline if discussed.*
- [ ] **[Owner Name]** to [Specific Task/Deliverable] — **Deadline:** [Date/Month or "ASAP"]

### Open Questions & Risks
*A log of project risks, hedge language, unconfirmed dependencies, or questions that were raised but explicitly parked for later.*
- **[Unresolved Thread]:** Describe the blocker or outstanding risk.

---

### 3. Strict Style & Token Constraints
- Never use fluffy or conversational opening filler text like "Here is your summary" or "Based on your notes." Begin immediately with the \`##\` Title.
- Write with professional, clear, and direct language. Avoid passive phrasing.
- If the user provided a customized template layout or configuration block, prioritize those section headings exactly over the default layout rules.`,

  technical: `Act as a Principal Software Architect and systems engineer. Your goal is to process the user's rough jots and the raw transcript into a technical specifications document.

Focus heavily on:
- Architectural decisions, API endpoints, data models, and database changes.
- Exact technology choices, protocols, library names, and performance metrics mentioned.
- Ignore general small-talk or administrative details unless specifically mentioned in the user's jots.

Structure the output as follows:
## [Technical System Update / Architecture Review]
*Brief technical objective of the architecture or system change.*

### System Architecture & Tech Stack
- **[Component/Module]:** Tech stack details, protocol, database modifications, or dependencies.

### Key API & Data Model Contracts
- **[Contract/Endpoint]:** Inputs, outputs, schema, formats, or parameters.

### Decisions & Trade-Offs
- **[Decision]:** What was chosen, why it was chosen, and alternative considerations.

### Action Items / Engineering Tasks
- [ ] **[Developer]** - [Technical task description] — **Deadline:** [Due date]`,

  action: `Act as a results-driven Project Manager. Your task is to extract clear deliverables, assignees, deadlines, and project risks from the raw transcript using the user's jots as areas of concern.

Structure the output strictly for task management:
## [Project Status & Deliverables Summary]

### Action Checklist
- [ ] **[Owner]** - [Clear, actionable deliverable] — **Deadline:** [Date/Month or "ASAP"]

### Project Risks & Impediments
- **[Risk Item]:** Description of blocker, owner responsible for resolution, and status.

### Alignments & Approved Changes
- **[Alignment]:** What was approved, when it will be deployed, and who was aligned.`,

  minutes: `Act as a professional corporate scribe. Your goal is to synthesize the user's rough notes and transcript into standard meeting minutes.

Structure:
## [Meeting Minutes: Subject Title]

### Attendees & Speaker Alignment
- Brief note of key speakers and who discussed what.

### Discussed Agenda Items
- **[Topic]:** Summary of status, considerations, and opinions shared.

### Summary of Next Steps
- [ ] **[Assignee]** - [Next step] — **Deadline:** [Date]`,

  summary: `Based on the following meeting transcript, write a beautifully formatted markdown summary of the key discussion highlights. Feel free to use headers (##), bold text (**text**), bullet points, and moderate relevant emojis (like 📌, 💡, 🎯, ✅) for readability, but avoid loose asterisks. Do not include any conversational filler, follow-up questions, or requests for elaboration at the end:\n\n{transcriptText}`,

  actionItems: `Based on the following meeting transcript, extract and list ONLY the actionable next steps and tasks that require someone to take action. Do not include informational remarks or general points. Format each item starting with an assignee in brackets followed by a dash and the task, like "[Name] - Task description" (or "[You] - Task description" if assigned to the local user). If no owner/assignee is specified, label it "[Unassigned] - Task description":\n\n{transcriptText}`
};

// Default Settings
let settings = {
  selectedModel: 'ggml-base.bin',
  selectedLlm: 'gemma3:1b',
  encryptByDefault: false,
  selectedMic: 'default',
  selectedSink: 'default',
  colorCodeDeadlines: false,
  userName: '',
  enableNoiseCancellation: true,
  aecMode: 'os',
  autoUpdateEnabled: true,
  customStoragePath: '',
  selectedNoteStyle: 'executive',
  notePromptTemplate: DEFAULT_PROMPTS.executive,
  summaryPromptTemplate: DEFAULT_PROMPTS.summary,
  actionPromptTemplate: DEFAULT_PROMPTS.actionItems
};

// Load settings
if (fs.existsSync(SETTINGS_FILE)) {
  try {
    settings = { ...settings, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) };
  } catch (e) {
    console.error('Error loading settings, using defaults', e);
  }
}

// Never persist encryption passwords on disk — unlock keys stay in-memory only.
if (Object.prototype.hasOwnProperty.call(settings, 'encryptionPassword')) {
  delete settings.encryptionPassword;
}

// Ensure new settings fields are populated
if (!settings.selectedNoteStyle) settings.selectedNoteStyle = 'executive';
if (!settings.notePromptTemplate) settings.notePromptTemplate = DEFAULT_PROMPTS.executive;
if (!settings.summaryPromptTemplate) settings.summaryPromptTemplate = DEFAULT_PROMPTS.summary;
if (!settings.actionPromptTemplate) settings.actionPromptTemplate = DEFAULT_PROMPTS.actionItems;
if (!settings.aecMode) settings.aecMode = 'os';
if (settings.autoUpdateEnabled === undefined) settings.autoUpdateEnabled = true;

function saveSettings() {
  const { encryptionPassword, ...safeSettings } = settings;
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(safeSettings, null, 2), 'utf8');
}

// ----------------------------------------------------
// Database Initialization & Migrations (v0.2)
// ----------------------------------------------------

function initDatabase() {
  return new Promise((resolve, reject) => {
    db.serialize(() => {
      db.run("PRAGMA foreign_keys = ON;");
      db.run("PRAGMA journal_mode = WAL;");
      db.run("PRAGMA busy_timeout = 5000;");
      db.run("PRAGMA synchronous = NORMAL;");
      
      // Folders Table
      db.run(`CREATE TABLE IF NOT EXISTS folders (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        icon TEXT DEFAULT '📁',
        description TEXT DEFAULT ''
      )`, (err) => { if (err) console.error("Error creating folders table", err); });

      // Sessions Table
      db.run(`CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        date TEXT,
        title TEXT,
        description TEXT,
        summary TEXT,
        actionItems TEXT,
        mixNotes TEXT,
        enhancedNotes TEXT,
        encrypted INTEGER,
        encrypted_payload TEXT,
        folder_id TEXT,
        mtimeMs REAL,
        tags TEXT,
        suggestedTags TEXT,
        FOREIGN KEY(folder_id) REFERENCES folders(id) ON DELETE SET NULL
      )`, (err) => { if (err) console.error("Error creating sessions table", err); });

      // Tasks Table
      db.run(`CREATE TABLE IF NOT EXISTS tasks (
        id TEXT PRIMARY KEY,
        text TEXT,
        assignee TEXT,
        completed INTEGER,
        dueDate TEXT,
        omitted INTEGER,
        sourceCallId TEXT,
        sourceCallTitle TEXT,
        sourceSegmentId TEXT,
        sourceTimestamp TEXT,
        FOREIGN KEY(sourceCallId) REFERENCES sessions(id) ON DELETE CASCADE
      )`, (err) => { 
        if (err) console.error("Error creating tasks table", err);
        else {
          db.run('CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(dueDate)');
          db.run('CREATE INDEX IF NOT EXISTS idx_tasks_source ON tasks(sourceCallId)');
          db.run('CREATE INDEX IF NOT EXISTS idx_sessions_mtime ON sessions(mtimeMs DESC)');
          migrateOldData()
            .then(async () => {
              await ensureFtsTables();
              try {
                await rebuildAllSessionsFts(async (id) => loadSessionPayloadFromDb(id));
              } catch (ftsErr) {
                console.error('FTS rebuild skipped:', ftsErr.message);
              }
              resolve();
            })
            .catch(reject);
        }
      });
    });
  });
}

async function ensureFolderRecord(folderId, { name, icon, description } = {}) {
  if (!folderId) return null;
  await dbRun(
    'INSERT OR IGNORE INTO folders (id, name, icon, description) VALUES (?, ?, ?, ?)',
    [folderId, name || folderId, icon || '📁', description || '']
  );
  return folderId;
}

async function syncFilesystemFoldersToDb() {
  const callsDir = getCallsDir();
  const { folders } = scanStorageLayout(callsDir);
  for (const folder of folders) {
    if (folder.id === ROOT_FOLDER_ID) continue;
    await ensureFolderRecord(folder.id, {
      name: folder.name,
      icon: folder.icon,
      description: folder.description
    });
  }
  const legacyFolders = [
    { id: 'work', name: 'Work', icon: '💼', description: 'Professional meetings' },
    { id: 'personal', name: 'Personal', icon: '🏠', description: 'Personal notes' },
    { id: 'drafts', name: 'Drafts', icon: '📝', description: 'Draft notes' }
  ];
  for (const folder of legacyFolders) {
    await ensureFolderRecord(folder.id, folder);
  }
}

const ensuredFolderIds = new Set();

async function normalizeFolderIdForSave(folderId, { syncFilesystem = false } = {}) {
  const normalized = folderId || UNCATEGORIZED_FOLDER_ID;
  if (syncFilesystem) {
    await syncFilesystemFoldersToDb();
  }
  if (!ensuredFolderIds.has(normalized)) {
    const displayName = normalized === UNCATEGORIZED_FOLDER_ID
      ? 'Trail (root)'
      : normalized.replace(/^fs:/, '') || normalized;
    await ensureFolderRecord(normalized, {
      name: displayName,
      icon: normalized === UNCATEGORIZED_FOLDER_ID ? '🥣' : '📁',
      description: ''
    });
    ensuredFolderIds.add(normalized);
  }
  return normalized;
}

async function migrateFolderColumns() {
  const columns = await dbAll("PRAGMA table_info(folders)");
  const columnNames = columns.map((column) => column.name);

  if (!columnNames.includes('icon')) {
    await dbRun("ALTER TABLE folders ADD COLUMN icon TEXT DEFAULT '📁'");
  }
  if (!columnNames.includes('description')) {
    await dbRun("ALTER TABLE folders ADD COLUMN description TEXT DEFAULT ''");
  }
}

async function migrateOldData() {
  try {
    // 1. Setup default folders if empty
    const existingFolders = await dbAll("SELECT * FROM folders");
    if (existingFolders.length === 0) {
      await dbRun("INSERT INTO folders (id, name, icon, description) VALUES (?, ?, ?, ?)", ["work", "Work", "💼", "Professional meetings and work sessions"]);
      await dbRun("INSERT INTO folders (id, name, icon, description) VALUES (?, ?, ?, ?)", ["personal", "Personal", "🏠", "Private personal notes"]);
      await dbRun("INSERT INTO folders (id, name, icon, description) VALUES (?, ?, ?, ?)", ["drafts", "Drafts", "📝", "In-progress and unsorted notes"]);
    }

    await migrateFolderColumns();
    await syncFilesystemFoldersToDb();

    // 2. Tasks migration
    const TASKS_FILE = path.join(DATA_DIR, 'tasks.json');
    if (fs.existsSync(TASKS_FILE)) {
      try {
        const tasks = JSON.parse(fs.readFileSync(TASKS_FILE, 'utf8'));
        if (Array.isArray(tasks)) {
          console.log(`Migrating ${tasks.length} tasks to SQLite...`);
          await dbRun("PRAGMA foreign_keys = OFF;");
          for (const task of tasks) {
            try {
              await dbRun(`INSERT OR IGNORE INTO tasks (
                id, text, assignee, completed, dueDate, omitted, 
                sourceCallId, sourceCallTitle, sourceSegmentId, sourceTimestamp
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
                task.id, task.text, task.assignee || 'Unassigned', task.completed ? 1 : 0, 
                task.dueDate || '', task.omitted ? 1 : 0, task.sourceCallId, 
                task.sourceCallTitle || '', task.sourceSegmentId || '', task.sourceTimestamp || ''
              ]);
            } catch (insertErr) {
              console.error(`Failed to insert task ${task.id}:`, insertErr);
            }
          }
          await dbRun("PRAGMA foreign_keys = ON;");
        }
        fs.renameSync(TASKS_FILE, TASKS_FILE + '.bak');
      } catch (err) {
        console.error("Failed to migrate tasks.json", err);
      }
    }

    // 3. Sessions migration from data/calls/
    const callsDir = getCallsDir();
    if (fs.existsSync(callsDir)) {
      const files = fs.readdirSync(callsDir).filter(f => f.endsWith('.trail'));
      if (files.length > 0) {
        console.log(`Migrating ${files.length} .trail files to SQLite...`);
        for (const file of files) {
          const filePath = path.join(callsDir, file);
          const id = file.replace('.trail', '');
          
          try {
            const rawContent = fs.readFileSync(filePath, 'utf8');
            const isEncrypted = rawContent.includes('"salt"') && rawContent.includes('"iv"') && rawContent.includes('"encrypted"');
            
            let data = null;
            if (isEncrypted) {
              const cachedKey = decryptionKeys.get(id) || settings.encryptionPassword;
              if (cachedKey) {
                try {
                  const decrypted = encryption.decrypt(rawContent, cachedKey);
                  data = JSON.parse(decrypted);
                } catch (decErr) {}
              }
            } else {
              data = JSON.parse(rawContent);
            }

            if (data) {
              const stat = fs.statSync(filePath);
              const mtimeMs = stat.mtimeMs;
              
              if (isEncrypted) {
                await dbRun(`INSERT OR IGNORE INTO sessions (
                  id, date, title, description, encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
                  data.id || id, data.date || '', 'Encrypted Session (Locked)', 
                  'This session is encrypted. Enter credentials to unlock.', 1, rawContent, 'work', mtimeMs,
                  JSON.stringify(data.tags || []), JSON.stringify(data.suggestedTags || [])
                ]);
              } else {
                await dbRun(`INSERT OR IGNORE INTO sessions (
                  id, date, title, description, summary, actionItems, 
                  mixNotes, enhancedNotes, encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
                  data.id || id, data.date || '', data.title || 'Meeting Session', 
                  data.description || 'No description available.', data.summary || '', 
                  data.actionItems || '', data.mixNotes || '', data.enhancedNotes || '',
                  0, JSON.stringify(data), 'work', mtimeMs,
                  JSON.stringify(data.tags || []), JSON.stringify(data.suggestedTags || [])
                ]);
              }
            }
            fs.renameSync(filePath, filePath + '.bak');
          } catch (migrateErr) {
            console.error(`Failed to migrate file ${file}`, migrateErr);
          }
        }
      }
    }
    await syncDatabaseWithFiles();
  } catch (err) {
    console.error("Migration error", err);
  }
}

async function upsertSessionFromTrailFile({ sessionId, filePath, folderId }) {
  const normalizedFolderId = await normalizeFolderIdForSave(folderId);
  const rawContent = fs.readFileSync(filePath, 'utf8');
  const isEncrypted = rawContent.includes('"salt"') && rawContent.includes('"iv"');
  const stat = fs.statSync(filePath);
  const mtimeMs = stat.mtimeMs;
  const tagsStr = '[]';
  const suggestedTagsStr = '[]';

  if (isEncrypted) {
    const cachedKey = decryptionKeys.get(sessionId) || settings.encryptionPassword;
    if (cachedKey) {
      try {
        const decrypted = encryption.decrypt(rawContent, cachedKey);
        const data = JSON.parse(decrypted);
        await dbRun(`INSERT OR REPLACE INTO sessions (
          id, date, title, description, summary, actionItems, mixNotes, enhancedNotes,
          encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
          data.id || sessionId,
          data.date || '',
          data.title || 'Meeting Session',
          data.description || 'No description available.',
          data.summary || '',
          data.actionItems || '',
          data.mixNotes || '',
          data.enhancedNotes || '',
          0,
          JSON.stringify(data),
          normalizedFolderId,
          mtimeMs,
          JSON.stringify(data.tags || []),
          JSON.stringify(data.suggestedTags || [])
        ]);
        decryptionKeys.set(sessionId, cachedKey);
        return;
      } catch (_) {
        // Fall through to locked state.
      }
    }

    await dbRun(`INSERT OR REPLACE INTO sessions (
      id, date, title, description, summary, actionItems, mixNotes, enhancedNotes,
      encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
      sessionId,
      '',
      'Encrypted Session (Locked)',
      'This session is encrypted. Enter credentials to unlock.',
      '', '', '', '',
      1,
      rawContent,
      normalizedFolderId,
      mtimeMs,
      tagsStr,
      suggestedTagsStr
    ]);
    return;
  }

  let data = {};
  try {
    data = JSON.parse(rawContent);
  } catch (_) {
    return;
  }

  await dbRun(`INSERT OR REPLACE INTO sessions (
    id, date, title, description, summary, actionItems, mixNotes, enhancedNotes,
    encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
    data.id || sessionId,
    data.date || '',
    data.title || 'Meeting Session',
    data.description || 'No description available.',
    data.summary || '',
    data.actionItems || '',
    data.mixNotes || '',
    data.enhancedNotes || '',
    0,
    JSON.stringify(data),
    normalizedFolderId,
    mtimeMs,
    JSON.stringify(data.tags || []),
    JSON.stringify(data.suggestedTags || [])
  ]);
}

async function syncDatabaseWithFiles() {
  try {
    await syncFilesystemFoldersToDb();
    const callsDir = getCallsDir();
    const { trailFiles } = scanStorageLayout(callsDir);
    const existingIds = new Set(trailFiles.map((file) => file.sessionId));

    for (const file of trailFiles) {
      await upsertSessionFromTrailFile(file);
    }

    const sessions = await dbAll('SELECT id FROM sessions');
    for (const session of sessions) {
      if (session.id !== 'live' && !existingIds.has(session.id)) {
        console.log(`Pruning session ${session.id} from database because file does not exist in ${callsDir}`);
        await dbRun('DELETE FROM sessions WHERE id = ?', [session.id]);
        await deleteSessionFts(session.id);
      }
    }
  } catch (err) {
    console.error('Error syncing database with files:', err);
  }
}


// ----------------------------------------------------
// Secure File Shredding (v0.2 Compliance)
// ----------------------------------------------------
// Async shredding lives in lib/secure-shred.js

// ----------------------------------------------------
// Window & Tray Management
// ----------------------------------------------------

function createHubWindow() {
  if (!windowManager) {
    windowManager = new WindowManager({
      projectDir: PROJECT_DIR,
      preloadPath: path.join(PROJECT_DIR, 'preload.js')
    });
  }
  return windowManager.createHubWindow();
}

function openMeetingWindow(sessionId, options = {}) {
  if (!windowManager) createHubWindow();
  return windowManager.openMeetingWindow(sessionId, options);
}

function updateTray() {
  if (!tray) return;

  const contextMenu = Menu.buildFromTemplate([
    {
      label: isRecording ? '🔴 Stop Transcribing' : '🟢 Start Transcribing',
      click: () => {
        if (isRecording) {
          stopRecordingHandler();
        } else {
          startRecordingHandler();
        }
      }
    },
    { type: 'separator' },
    {
      label: 'Open TrailMix',
      click: () => {
        const hub = getHubWindow();
        if (hub) {
          hub.restore();
          hub.focus();
        } else {
          createHubWindow();
        }
      }
    },
    {
      label: 'Quit',
      click: () => {
        stopRecordingHandler();
        app.quit();
      }
    }
  ]);

  tray.setContextMenu(contextMenu);
  tray.setToolTip(isRecording ? 'TrailMix (Transcribing...)' : 'TrailMix (Idle)');
}

function setupTray() {
  const icon = nativeImage.createFromPath(path.join(PROJECT_DIR, 'assets', 'logo.png')).resize({ width: 22, height: 22 });
  tray = new Tray(icon);
  updateTray();

  tray.on('double-click', () => {
    const hub = getHubWindow();
    if (hub) {
      hub.restore();
      hub.focus();
    } else {
      createHubWindow();
    }
  });
}

// ----------------------------------------------------
// Hardware specs and recommendations
// ----------------------------------------------------

function getHardwareSpecs() {
  let cpuInfo = 'Unknown CPU';
  let totalRamGb = 8;
  let gpuInfo = 'None';
  let recommendedModel = 'gemma3:1b';

  try {
    const cpuLine = execSync("lscpu | grep 'Model name'").toString();
    cpuInfo = cpuLine.split(':')[1].trim();
  } catch (e) {}

  try {
    const memLine = execSync('free -g | grep Mem').toString();
    totalRamGb = parseInt(memLine.split(/\s+/)[1], 10);
  } catch (e) {}

  try {
    const gpuLines = execSync('lspci | grep -i -E "vga|3d|display"').toString();
    if (gpuLines.toLowerCase().includes('nvidia')) {
      gpuInfo = 'NVIDIA GPU';
      recommendedModel = 'llama3:latest';
    } else if (gpuLines.toLowerCase().includes('amd') || gpuLines.toLowerCase().includes('radeon')) {
      gpuInfo = 'AMD Radeon GPU';
      recommendedModel = 'llama3:latest';
    }
  } catch (e) {}

  // System suggestions
  if (totalRamGb >= 32) {
    recommendedModel = 'qwen3.6:latest';
  } else if (totalRamGb >= 16) {
    recommendedModel = 'llama3:latest';
  } else {
    recommendedModel = 'gemma3:1b';
  }

  return { cpuInfo, totalRamGb, gpuInfo, recommendedModel };
}

// ----------------------------------------------------
// Audio Capture & Device Query
// ----------------------------------------------------

function queryAudioDevices() {
  let sink = '';
  let source = '';
  const microphones = [];
  const outputs = [];
  
  try {
    sink = execSync('pactl get-default-sink').toString().trim();
    source = execSync('pactl get-default-source').toString().trim();
  } catch (e) {
    console.error('pactl failed to get defaults', e);
  }

  try {
    const rawList = execSync('pactl list sources').toString();
    const blocks = rawList.split(/Source #\d+/);
    
    blocks.forEach((block) => {
      if (!block.trim()) return;
      
      const nameMatch = block.match(/^\s*Name:\s*([^\s]+)/m);
      const descMatch = block.match(/^\s*Description:\s*([^\n]+)/m);
      
      if (nameMatch && descMatch) {
        const name = nameMatch[1];
        const description = descMatch[1].trim();
        
        if (name.includes('.monitor')) {
          // Monitor source of a sink (System output playback monitor)
          const cleanDesc = description.replace(/^Monitor of\s+/i, '');
          outputs.push({
            id: name.replace('.monitor', ''),
            name: cleanDesc
          });
        } else {
          // Input source (microphone)
          microphones.push({
            id: name,
            name: description
          });
        }
      }
    });
  } catch (e2) {
    console.error('Failed to parse sources list', e2);
  }

  // Find descriptions for default active devices
  let defaultSinkDesc = sink;
  let defaultSourceDesc = source;
  
  const activeSinkObj = outputs.find(o => o.id === sink || `${o.id}.monitor` === sink);
  if (activeSinkObj) {
    defaultSinkDesc = activeSinkObj.name;
  }
  
  const activeSourceObj = microphones.find(m => m.id === source);
  if (activeSourceObj) {
    defaultSourceDesc = activeSourceObj.name;
  }
  
  return { 
    sink, 
    source, 
    sinkDesc: defaultSinkDesc, 
    sourceDesc: defaultSourceDesc, 
    microphones, 
    outputs 
  };
}

// ----------------------------------------------------
// Whisper & Transcription Runner
// ----------------------------------------------------

async function saveSessionToDbPromise(session, options = {}) {
  if (!session) return;
  const tagsStr = JSON.stringify(session.tags || []);
  const suggestedTagsStr = JSON.stringify(session.suggestedTags || []);
  const mtimeMs = Date.now();
  const folderId = await normalizeFolderIdForSave(session.folder_id, {
    syncFilesystem: Boolean(options.syncFilesystem)
  });
  session.folder_id = folderId;

  try {
    const isEncrypted = session.encrypted ? 1 : 0;
    const password = decryptionKeys.get(session.id) || settings.encryptionPassword;
    
    let payload = '';
    if (isEncrypted && password) {
      let reuseEnvelope = encryptionEnvelopes.get(session.id) || null;
      if (!reuseEnvelope) {
        try {
          const existing = await dbGet('SELECT encrypted_payload FROM sessions WHERE id = ? AND encrypted = 1', [session.id]);
          if (existing?.encrypted_payload) {
            reuseEnvelope = JSON.parse(existing.encrypted_payload);
          }
        } catch (err) {
          reuseEnvelope = null;
        }
      }
      payload = encryption.encrypt(JSON.stringify(session), password, reuseEnvelope);
      try {
        encryptionEnvelopes.set(session.id, JSON.parse(payload));
      } catch (err) {
        // Ignore envelope cache failures.
      }
      await dbRun(`INSERT OR REPLACE INTO sessions (
        id, date, title, description, summary, actionItems, mixNotes, enhancedNotes, 
        encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
        session.id, session.date || '', session.title || 'Meeting Session',
        session.description || 'No description available.', '', '', '', '',
        1, payload, folderId, mtimeMs, tagsStr, suggestedTagsStr
      ]);
      decryptionKeys.set(session.id, password);
    } else {
      payload = JSON.stringify(session);
      await dbRun(`INSERT OR REPLACE INTO sessions (
        id, date, title, description, summary, actionItems, mixNotes, enhancedNotes, 
        encrypted, encrypted_payload, folder_id, mtimeMs, tags, suggestedTags
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
        session.id, session.date || '', session.title || 'Meeting Session',
        session.description || 'No description available.', session.summary || '',
        session.actionItems || '', session.mixNotes || '', session.enhancedNotes || '',
        0, payload, folderId, mtimeMs, tagsStr, suggestedTagsStr
      ]);
    }

    const callsDir = getCallsDir();
    if (!fs.existsSync(callsDir)) {
      fs.mkdirSync(callsDir, { recursive: true });
    }
    const filePath = resolveSessionFilePath(callsDir, session.id, folderId);
    const fileDir = path.dirname(filePath);
    if (!fs.existsSync(fileDir)) {
      fs.mkdirSync(fileDir, { recursive: true });
    }
    await fs.promises.writeFile(filePath, payload, 'utf8');

    // Index plaintext for offline search. Never index locked ciphertext.
    try {
      if (isEncrypted && !decryptionKeys.get(session.id)) {
        await deleteSessionFts(session.id);
      } else {
        await upsertSessionFts(session);
      }
    } catch (ftsErr) {
      console.error('FTS upsert failed', ftsErr);
    }
  } catch (err) {
    console.error("Failed to save session to DB", err);
  }
}

const silentSaveTimers = new Map();
const SILENT_SAVE_DEBOUNCE_MS = 2500;

function saveSessionToFileSilently(session) {
  if (!session) return;
  // Flush any pending debounced save for this session first.
  const pending = silentSaveTimers.get(session.id);
  if (pending) {
    clearTimeout(pending);
    silentSaveTimers.delete(session.id);
  }
  saveSessionToDbPromise(session).catch(err => {
    console.error("Failed to save session silently", err);
  });
}

function scheduleSilentSessionSave(session, delayMs = SILENT_SAVE_DEBOUNCE_MS) {
  if (!session?.id) return;
  const existing = silentSaveTimers.get(session.id);
  if (existing) clearTimeout(existing);
  const timer = setTimeout(() => {
    silentSaveTimers.delete(session.id);
    saveSessionToDbPromise(session).catch((err) => {
      console.error('Failed to save session silently', err);
    });
  }, delayMs);
  silentSaveTimers.set(session.id, timer);
}

function flushSilentSessionSave(session) {
  if (!session?.id) return Promise.resolve();
  const existing = silentSaveTimers.get(session.id);
  if (existing) {
    clearTimeout(existing);
    silentSaveTimers.delete(session.id);
  }
  return saveSessionToDbPromise(session);
}

// ----------------------------------------------------
// Recording Controls
// ----------------------------------------------------

async function startRecordingHandler(requestedSessionId = null) {
  if (requestedSessionId && activeRecordingSessionId && activeRecordingSessionId !== requestedSessionId) {
    return { conflict: true, activeSessionId: activeRecordingSessionId };
  }

  if (isRecording && !isPaused) {
    if (requestedSessionId && requestedSessionId === activeRecordingSessionId) {
      broadcastRecordingStatus({ isNewSession: false });
    }
    return { sessionId: activeRecordingSessionId };
  }

  const resumingFromPause = isPaused;
  const devices = queryAudioDevices();
  let sink = devices.sink;
  let source = devices.source;

  if (settings.selectedMic && settings.selectedMic !== 'default') {
    source = settings.selectedMic;
  }
  if (settings.selectedSink && settings.selectedSink !== 'default') {
    sink = settings.selectedSink;
  }

  if (!sink || !source) {
    console.error('No audio devices found to transcribe.');
    return { error: 'No audio devices found' };
  }

  const sinkMonitor = sink.endsWith('.monitor') ? sink : `${sink}.monitor`;
  source = resolveAecMicSource(source, sink);
  console.log(`Starting transcription. Sink monitor: ${sinkMonitor}, Source: ${source}, AEC: ${getAecModeForCapture()}`);

  if (!resumingFromPause) {
    transcriptionService.clearQueue();
    audioCaptureService.resetProcessedChunks();

    if (requestedSessionId) {
      activeSession = getSession(requestedSessionId) || await loadSessionPayloadFromDb(requestedSessionId);
      if (activeSession) setSession(activeSession);
    }

    if (!activeSession || (requestedSessionId && activeSession.id !== requestedSessionId)) {
      activeSession = createNewSession({ encryptByDefault: settings.encryptByDefault });
    }

    if (!activeSession.title) activeSession.title = 'New Meeting';
    if (activeSession.titleAutoGenerated === undefined) activeSession.titleAutoGenerated = true;
    if (activeSession.titleUserEdited === undefined) activeSession.titleUserEdited = false;

    activeRecordingSessionId = activeSession.id;
    setSession(activeSession);
    if (activeSession.transcript?.length) {
      const lastSeg = activeSession.transcript[activeSession.transcript.length - 1];
      sessionChunkOffset = Math.ceil((lastSeg.timestampMs || 0) / 2000);
    } else {
      sessionChunkOffset = 0;
    }
  } else {
    await audioCaptureService.prepareTempDir(true);
    if (requestedSessionId) {
      activeRecordingSessionId = requestedSessionId;
    } else if (activeSession?.id) {
      activeRecordingSessionId = activeSession.id;
    }
  }

  isRecording = true;
  isPaused = false;
  updateTray();
  broadcastRecordingStatus({
    isNewSession: !resumingFromPause && (!activeSession?.transcript?.length)
  });

  try {
    await audioCaptureService.start({ sinkMonitor, source });
    return { sessionId: activeRecordingSessionId };
  } catch (err) {
    console.error('Failed to start audio capture', err);
    isRecording = false;
    activeRecordingSessionId = null;
    disableOsEchoCancellation();
    updateTray();
    return { error: err.message };
  }
}

async function pauseRecordingHandler() {
  if (!isRecording || isPaused) return;

  isRecording = false;
  isPaused = true;
  updateTray();

  broadcastRecordingStatus();

  if (diarizationInterval) {
    clearInterval(diarizationInterval);
    diarizationInterval = null;
  }

  await audioCaptureService.stopFfmpeg();
  if (activeSession) {
    void flushSilentSessionSave(activeSession);
  }

  setTimeout(async () => {
    const chunkCount = await audioCaptureService.countChunkCandidates();
    await audioCaptureService.flushRemainingChunks();
    sessionChunkOffset += chunkCount;
  }, 1000);
}

async function stopRecordingHandler() {
  if (!isRecording && !isPaused && !audioCaptureService.hasActiveProcess()) return;

  const stoppingSessionId = activeRecordingSessionId;

  isRecording = false;
  isPaused = false;
  updateTray();

  disableOsEchoCancellation();
  captureMicSourceOverride = null;
  activeRecordingSessionId = null;
  broadcastRecordingStatus({ sessionId: stoppingSessionId });

  if (diarizationInterval) {
    clearInterval(diarizationInterval);
    diarizationInterval = null;
  }

  await audioCaptureService.stopFfmpeg();
  if (stoppingSessionId) {
    windowManager?.destroyMiniWindow(stoppingSessionId);
  }

  flushingFinalChunks = true;
  try {
    await audioCaptureService.flushRemainingChunks();
    await transcriptionService.waitForIdle(60000);
    if (activeSession) {
      await flushSilentSessionSave(activeSession);
    }
    await finalizeAndSaveSession();
    if (stoppingSessionId) {
      const session = getSession(stoppingSessionId) || await loadSessionPayloadFromDb(stoppingSessionId);
      if (session) broadcastToSession(stoppingSessionId, 'session:updated', session);
    }
  } catch (err) {
    console.error('Error finalizing recording session:', err);
  } finally {
    flushingFinalChunks = false;
  }
}

async function resumeCallTranscriptionHandler(sessionId) {
  try {
    if (isRecording && activeRecordingSessionId && activeRecordingSessionId !== sessionId) {
      return { success: false, conflict: true, activeSessionId: activeRecordingSessionId };
    }

    if (isRecording && activeRecordingSessionId === sessionId) {
      const meetingWindow = openMeetingWindow(sessionId);
      const rebroadcastStatus = () => broadcastRecordingStatus({ isNewSession: false });
      if (meetingWindow && !meetingWindow.isDestroyed()) {
        if (meetingWindow.webContents.isLoading()) {
          meetingWindow.webContents.once('did-finish-load', rebroadcastStatus);
        } else {
          rebroadcastStatus();
        }
      } else {
        rebroadcastStatus();
      }
      return { success: true, alreadyRecording: true };
    }

    const resolved = await resolveSessionRecord(sessionId);
    if (!resolved) return { success: false, error: 'Session not found' };
    if (resolved.requirePassword) {
      return { success: false, requirePassword: true };
    }

    const { session, canonicalId } = resolved;
    if (canonicalId !== sessionId) {
      deleteSession(sessionId);
      windowManager?.rekeyMeetingWindow(sessionId, canonicalId);
    }

    activeSession = session;
    activeRecordingSessionId = canonicalId;
    
    // Calculate sessionChunkOffset based on the last segment timestamp
    if (activeSession.transcript && activeSession.transcript.length > 0) {
      const lastSeg = activeSession.transcript[activeSession.transcript.length - 1];
      sessionChunkOffset = Math.ceil(lastSeg.timestampMs / 2000);
    } else {
      sessionChunkOffset = 0;
    }
    
    isPaused = true;
    setSession(session);
    const meetingWindow = openMeetingWindow(canonicalId);
    const rebroadcastStatus = () => broadcastRecordingStatus({ isNewSession: false });
    await startRecordingHandler(canonicalId);
    if (meetingWindow && !meetingWindow.isDestroyed()) {
      if (meetingWindow.webContents.isLoading()) {
        meetingWindow.webContents.once('did-finish-load', rebroadcastStatus);
      } else {
        rebroadcastStatus();
      }
    } else {
      rebroadcastStatus();
    }
    return { success: true, sessionId: canonicalId };
  } catch (e) {
    return { success: false, error: e.message };
  }
}

async function runSpeakerDiarizationLLM() {
  if (!activeSession?.transcript?.length) return;

  const model = settings.selectedLlm;
  const systemPrompt = getSpeakerDiarizationSystemPrompt(settings.userName);
  const turns = buildDiarizationTurns(activeSession.transcript);
  const prompt = buildDiarizationPrompt(turns);

  console.log('Diarization LLM: Analyzing transcript for speaker names and turns...');

  try {
    const response = await llmService.queryComplete(prompt, model, systemPrompt);
    const mapping = parseLlmJsonResponse(response);
    console.log('Diarization LLM: Successfully mapped speakers:', mapping);
    applySpeakerLabelMapping(activeSession.transcript, mapping);
    if (activeRecordingSessionId) {
      broadcastToSession(activeRecordingSessionId, 'audio:on-speaker-labels-updated', mapping);
    }
  } catch (error) {
    console.error('Diarization LLM failed:', error);
  }
}

async function diarizeSpeakersPromise() {
  await runSpeakerDiarizationLLM();
}

function triggerOllamaContextRename(transcriptText, callback) {
  const model = settings.selectedLlm;
  const systemPrompt = `You are a local metadata generation assistant.
Output your results ONLY as a valid JSON object with keys "title" (a very short tagline of 3-5 words), "description" (a longer summary description of 1-2 sentences), and "suggestedTags" (an array of 2-3 broad tags representing call categories like "projects", "vendor calls", "sales", "company meetings", "support", "hiring", etc.).
Do not include any reasoning, markdown formatting, or conversational text. Output ONLY the raw JSON object.`;
  const prompt = `Based on the following meeting transcript, generate the title, description, and suggested tags:\n\n${transcriptText}\n\nReturn the JSON object.`;

  llmService.queryComplete(prompt, model, systemPrompt)
    .then(response => {
      try {
        const meta = parseLlmJsonResponse(response);
        
        let tags = meta.suggestedTags || [];
        if (!Array.isArray(tags)) tags = [];
        tags = tags.map(t => typeof t === 'string' ? t.toLowerCase().replace(/[^a-z0-9]/g, '') : '').filter(t => t.length > 0);
        
        if (tags.length === 0) {
          const words = (meta.title || '').toLowerCase().split(/\s+/).filter(w => w.length > 4 && !['meeting', 'session', 'calls', 'about', 'their'].includes(w));
          tags = words.slice(0, 2);
        }
        
        callback(meta.title || 'Meeting Session', meta.description || 'No description available.', tags);
      } catch (e) {
        console.error("Context Rename LLM parse error:", e);
        callback('Meeting Session', 'No description available.', []);
      }
    })
    .catch(err => {
      console.error("Context Rename LLM query failed:", err);
      callback('Meeting Session', 'No description available.', []);
    });
}

async function runEnhanceNotesForSession(session) {
  if (!session) return null;

  const editorDocument = documentFromSession(session);
  if (!editorDocument.plainText.trim()) {
    return null;
  }

  const fullTranscript = enhanceNotesService.buildFullTranscript(session.transcript);
  const systemPrompt = settings.notePromptTemplate || DEFAULT_PROMPTS.executive;

  try {
    return await enhanceNotesService.enhanceDocument({
      editorDocument,
      fullTranscript,
      transcriptSegments: session.transcript || [],
      model: settings.selectedLlm,
      systemPrompt
    });
  } catch (err) {
    console.error('Enhance notes failed:', err);
    return null;
  }
}

async function runSessionEnrichment(sessionId) {
  const session = await loadSessionPayloadFromDb(sessionId);
  if (!session) return;

  session.transcript.sort((a, b) => a.timestampMs - b.timestampMs);

  const enhanceResult = await runEnhanceNotesForSession(session);
  if (enhanceResult) {
    session.editorDocument = enhanceResult.editorDocument;
    session.enhancedNotes = enhanceResult.enhancedNotes;
  }

  const textContent = session.transcript.map((t) => `[${t.timestamp}] ${t.speaker}: ${t.text}`).join('\n');
  if (!textContent.trim()) {
    await saveSessionPayloadToDb(sessionId, session);
    const hub = getHubWindow();
    if (hub) hub.webContents.send('calls:session-summary-ready', session);
    broadcastToSession(sessionId, 'session:updated', session);
    return;
  }

  await new Promise((resolve) => {
    triggerOllamaSummary(textContent, (summary, actionItems) => {
      session.summary = summary;
      session.actionItems = actionItems;

      triggerOllamaContextRename(textContent, async (title, description, tags) => {
        applyAutoTitle(session, title, description, tags);
        setSession(session);

        extractTasksFromActionItems(session);
        await saveSessionPayloadToDb(sessionId, session);

        try {
          const callsDir = getCallsDir();
          const oldId = session.id;
          const slug = (session.title || 'meeting').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
          const newId = `${slug}_${oldId}`;
          if (newId !== oldId && !session.titleUserEdited) {
            session.previousId = oldId;
            session.id = newId;
            session.filePath = path.join(callsDir, `${newId}.trail`);
            await saveSessionPayloadToDb(newId, session);
            await dbRun('DELETE FROM sessions WHERE id = ?', [oldId]);
            const oldPath = path.join(callsDir, `${oldId}.trail.bak`);
            if (fs.existsSync(oldPath)) fs.unlinkSync(oldPath);
            deleteSession(oldId);
            setSession(session);
            windowManager?.rekeyMeetingWindow(oldId, newId);
          }
        } catch (renameErr) {
          console.error('Failed to rename session after enrichment', renameErr);
        }

        const hub = getHubWindow();
        if (hub) hub.webContents.send('calls:session-summary-ready', session);
        broadcastToSession(session.id, 'session:updated', session);
        if (session.previousId && session.previousId !== session.id) {
          broadcastToSession(session.previousId, 'session:updated', session);
        }
        resolve();
      });
    });
  });
}

async function finalizeAndSaveSession() {
  if (!activeSession) return;

  activeSession.transcript.sort((a, b) => a.timestampMs - b.timestampMs);
  const sessionId = activeSession.id;

  saveSessionToFile(activeSession);

  const blocks = splitTranscriptIntoBlocks(activeSession.transcript);
  await sessionProcessingService.createJob(sessionId, blocks.length);

  const hub = getHubWindow();
  if (hub) hub.webContents.send('calls:list-updated');
  broadcastProcessingProgress();

  const finishedSession = activeSession;
  setSession(finishedSession);
  activeSession = null;
  return finishedSession;
}

// ----------------------------------------------------
// Timeline Tasks Manager
// ----------------------------------------------------

function parseDeadlineDate(text) {
  const lowercase = text.toLowerCase();
  const now = new Date();
  
  // 1. "tomorrow"
  if (lowercase.includes('tomorrow')) {
    const d = new Date(now);
    d.setDate(d.getDate() + 1);
    return d.toISOString().split('T')[0];
  }
  
  // 2. "in X weeks"
  const weeksMatch = lowercase.match(/in (\d+) weeks?/);
  if (weeksMatch) {
    const weeks = parseInt(weeksMatch[1], 10);
    const d = new Date(now);
    d.setDate(d.getDate() + weeks * 7);
    return d.toISOString().split('T')[0];
  }
  
  // 3. "in X days"
  const daysMatch = lowercase.match(/in (\d+) days?/);
  if (daysMatch) {
    const days = parseInt(daysMatch[1], 10);
    const d = new Date(now);
    d.setDate(d.getDate() + days);
    return d.toISOString().split('T')[0];
  }
  
  // 4. "next week"
  if (lowercase.includes('next week')) {
    const d = new Date(now);
    d.setDate(d.getDate() + 7);
    return d.toISOString().split('T')[0];
  }

  // 5. "by [Weekday]" or "next [Weekday]"
  const weekdays = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  for (let i = 0; i < weekdays.length; i++) {
    if (lowercase.includes(`by ${weekdays[i]}`) || lowercase.includes(`next ${weekdays[i]}`)) {
      const d = new Date(now);
      const currentDay = d.getDay();
      let targetDay = i;
      let daysToAdd = targetDay - currentDay;
      if (daysToAdd <= 0) daysToAdd += 7;
      d.setDate(d.getDate() + daysToAdd);
      return d.toISOString().split('T')[0];
    }
  }

  // 6. Month name + Day: e.g., "June 15" or "Jun 15th"
  const monthNames = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
  const monthRegex = new RegExp(`\\b(${monthNames.join('|')})[a-z]*\\s+(\\d+)(?:st|nd|rd|th)?`, 'i');
  const monthMatch = lowercase.match(monthRegex);
  if (monthMatch) {
    const monthStr = monthMatch[1];
    const day = parseInt(monthMatch[2], 10);
    const monthIdx = monthNames.indexOf(monthStr.substring(0, 3));
    
    const d = new Date(now.getFullYear(), monthIdx, day);
    if (d < now) {
      d.setFullYear(now.getFullYear() + 1);
    }
    return d.toISOString().split('T')[0];
  }

  // 7. Numerical date: e.g. 6/20/2026 or 06/20
  const numMatch = lowercase.match(/(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?/);
  if (numMatch) {
    const month = parseInt(numMatch[1], 10) - 1;
    const day = parseInt(numMatch[2], 10);
    let year = numMatch[3] ? parseInt(numMatch[3], 10) : now.getFullYear();
    if (numMatch[3] && numMatch[3].length === 2) {
      year += 2000;
    }
    const d = new Date(year, month, day);
    if (!Number.isNaN(d.getTime())) {
      return d.toISOString().split('T')[0];
    }
  }

  return '';
}

async function extractTasksFromActionItems(session) {
  const existingTasks = await getTasksListFromDb();
  const lines = (session.actionItems || '').split('\n');
  
  for (let idx = 0; idx < lines.length; idx++) {
    const line = lines[idx];
    const trimmed = line.trim();
    if (trimmed.startsWith('-') || trimmed.startsWith('*')) {
      const text = trimmed.substring(1).trim();
      if (!text) continue;
      
      let assignee = 'Unassigned';
      let cleanText = text;
      const match = text.match(/^\[(.*?)\]\s*[-:]\s*(.*)$/);
      if (match) {
        assignee = match[1].trim();
        cleanText = match[2].trim();
      }
      
      const exists = existingTasks.some(t => t.text === cleanText && t.sourceCallId === session.id);
      if (!exists) {
        const dueDate = parseDeadlineDate(cleanText);
        let sourceSegmentId = '';
        let sourceTimestamp = '00:00';
        if (session.transcript) {
          const words = cleanText.toLowerCase().split(/\s+/).filter(w => w.length > 4);
          for (let seg of session.transcript) {
            const segTextLower = seg.text.toLowerCase();
            const matchesCount = words.filter(word => segTextLower.includes(word)).length;
            if (matchesCount >= 2) {
              sourceSegmentId = seg.id;
              sourceTimestamp = seg.timestamp;
              break;
            }
          }
        }
        
        await saveTaskToDb({
          id: `task_${session.id}_${idx}_${Date.now()}`,
          text: cleanText,
          assignee: assignee,
          completed: false,
          dueDate: dueDate,
          omitted: false,
          sourceCallId: session.id,
          sourceCallTitle: session.title,
          sourceSegmentId: sourceSegmentId,
          sourceTimestamp: sourceTimestamp
        });
      }
    }
  }
}

async function saveSessionToFile(session) {
  await saveSessionToDbPromise(session);
  
  // Reload call list on frontend
  if (getHubWindow()) {
    getHubWindow().webContents.send('calls:list-updated');
  }
  
  // Compliance: Secure file shredding of temporary recordings
  if (fs.existsSync(TEMP_DIR)) {
    try {
      const files = await fs.promises.readdir(TEMP_DIR);
      await Promise.all(files.map((file) => secureShredFile(path.join(TEMP_DIR, file))));
    } catch (e) {}
    await fs.promises.rm(TEMP_DIR, { recursive: true, force: true });
  }
}

// ----------------------------------------------------
// Ollama API Integrations (Local LLM)
// ----------------------------------------------------

function triggerOllamaSummary(transcriptText, callback) {
  const model = settings.selectedLlm;
  const summaryPrompt = applyPromptTemplate(
    settings.summaryPromptTemplate || DEFAULT_PROMPTS.summary,
    transcriptText
  );
  const actionItemsPrompt = applyPromptTemplate(
    settings.actionPromptTemplate || DEFAULT_PROMPTS.actionItems,
    transcriptText
  );

  Promise.all([
    llmService.queryComplete(summaryPrompt, model).catch(() => 'Could not load summary. (Ollama error)'),
    llmService.queryComplete(actionItemsPrompt, model).catch(() => 'Could not load action items. (Ollama error)')
  ]).then(([summary, actionItems]) => {
    callback(summary, actionItems);
  }).catch(() => {
    callback('Could not load summary. (Ollama error)', 'Could not load action items.');
  });
}

function cleanupStaleLoopbackModules() {
  try {
    const listing = execSync('pactl list short modules 2>/dev/null', { encoding: 'utf8' });
    const loopbackIds = listing
      .split('\n')
      .filter((line) => /module-loopback/i.test(line))
      .map((line) => line.split('\t')[0])
      .filter(Boolean);

    loopbackIds.forEach((moduleId) => {
      try {
        execSync(`pactl unload-module ${moduleId}`);
        console.log(`Unloaded stale module-loopback #${moduleId} (leftover from prior capture bug)`);
      } catch (err) {
        // Module may have been removed already.
      }
    });
  } catch (err) {
    // pactl not available — skip cleanup.
  }
}

const runtime = {
  app,
  BrowserWindow,
  ipcMain,
  Tray,
  Menu,
  nativeImage,
  screen,
  shell,
  dialog,
  path,
  fs,
  os,
  execSync,
  spawn,
  encryption,
  db,
  dbRun,
  dbAll,
  dbGet,
  PROJECT_DIR,
  DATA_DIR,
  TEMP_DIR,
  XDG_CONFIG_DIR,
  SETTINGS_FILE,
  WHISPER_DIR,
  audioCaptureService,
  transcriptionService,
  llmService,
  enhanceNotesService,
  sessionProcessingService,
  updateService,
  get windowManager() { return windowManager; },
  set windowManager(v) { windowManager = v; },
  get isRecording() { return isRecording; },
  set isRecording(v) { isRecording = v; },
  get isPaused() { return isPaused; },
  set isPaused(v) { isPaused = v; },
  get activeSession() { return activeSession; },
  set activeSession(v) { activeSession = v; },
  get activeRecordingSessionId() { return activeRecordingSessionId; },
  set activeRecordingSessionId(v) { activeRecordingSessionId = v; },
  decryptionKeys,
  get settings() { return settings; },
  set settings(v) { settings = v; },
  DEFAULT_PROMPTS,
  getSession,
  setSession,
  deleteSession,
  createNewSession,
  getHubWindow,
  broadcastToSession,
  broadcastRecordingStatus,
  queryAudioDevices,
  startRecordingHandler,
  pauseRecordingHandler,
  stopRecordingHandler,
  resumeCallTranscriptionHandler,
  loadSessionPayloadFromDb,
  saveSessionToDbPromise,
  saveSessionToFileSilently,
  saveSessionToFile,
  resolveSessionRecord,
  syncDatabaseWithFiles,
  getCallsDir,
  watchCallsDirectory,
  createHubWindow,
  openMeetingWindow,
  setupTray,
  updateTray,
  getHardwareSpecs,
  initDatabase,
  migrateOldData,
  syncFilesystemFoldersToDb,
  saveSettings,
  broadcastProcessingProgress,
  runSessionEnrichment,
  extractTasksFromActionItems,
  CHAT_RECIPES,
  buildRecipePrompt,
  finalizeAndSaveSession,
  normalizeFolderIdForSave,
  getTasksListFromDb,
  saveTaskToDb,
  upsertSessionFromTrailFile,
  ensureFolderRecord,
  parseDeadlineDate,
  appEventBus,
  ROOT_FOLDER_ID,
  UNCATEGORIZED_FOLDER_ID,
  scanStorageLayout,
  ensureFolderDir,
  moveSessionFile,
  moveStorageContents,
  resolveSessionFilePath,
  relativePathFromFolderId,
  secureShredFile,
  formatTaskRow,
  formatTimestamp,
  documentFromSession,
  splitTranscriptIntoBlocks,
  PROCESSING_STATUS,
  cleanupStaleLoopbackModules,
  resolveWhisperCli,
  searchSessionsFts,
  deleteSessionFts,
  upsertSessionFts
};

module.exports = runtime;
