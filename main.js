const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, screen, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execSync, spawn } = require('child_process');
const encryption = require('./encryption');
const { parseLlmJsonResponse, applyPromptTemplate } = require('./lib/llm-utils');
const {
  getSpeakerDiarizationSystemPrompt,
  buildDiarizationTurns,
  buildDiarizationPrompt,
  applySpeakerLabelMapping,
  notifySpeakerLabelsUpdated
} = require('./lib/speaker-diarization');
const { formatTaskRow } = require('./lib/task-db');
const { formatTimestamp } = require('./lib/format-timestamp');
const { secureShredFile } = require('./lib/secure-shred');
const { AudioCaptureService } = require('./services/AudioCaptureService');
const { TranscriptionService } = require('./services/TranscriptionService');
const { LLMInferenceService } = require('./services/LLMInferenceService');
const { EnhanceNotesService } = require('./services/EnhanceNotesService');
const { SessionProcessingService, PROCESSING_STATUS } = require('./services/SessionProcessingService');
const { documentFromSession } = require('./lib/editor-document');
const { coalesceIncomingSegments } = require('./lib/transcript-coalesce');
const { splitTranscriptIntoBlocks } = require('./lib/processing-blocks');
const { appEventBus } = require('./lib/event-bus');
const { CHAT_RECIPES, buildRecipePrompt } = require('./lib/chat-recipes');
const { WindowManager } = require('./windows/WindowManager');
const { UpdateService } = require('./services/UpdateService');
const { enableOsEchoCancellation, disableOsEchoCancellation } = require('./lib/audio-echo-os');
const {
  getSession,
  setSession,
  createNewSession,
  applyAutoTitle
} = require('./lib/session-registry');
const {
  ROOT_FOLDER_ID,
  UNCATEGORIZED_FOLDER_ID,
  scanStorageLayout,
  ensureFolderDir,
  moveSessionFile,
  moveStorageContents,
  resolveSessionFilePath,
  relativePathFromFolderId
} = require('./lib/storage-layout');
const { resolveWhisperCli } = require('./lib/resolve-whisper-cli');
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

// Application Paths
const PROJECT_DIR = __dirname;
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

  const { transcript, emitted } = coalesceIncomingSegments(activeSession.transcript, rawSegments);
  activeSession.transcript = transcript;

  emitted.forEach((segment) => {
    if (activeRecordingSessionId) {
      broadcastToSession(activeRecordingSessionId, 'audio:on-transcription-update', segment);
    }
  });

  if (emitted.length > 0) {
    saveSessionToFileSilently(activeSession);
  }
}

transcriptionService.configure({
  onSegments: handleTranscriptionSegments
});

audioCaptureService.configure({
  onChunkReady: ({ chunkPath, chunkIndex }) => {
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
  try {
    callsDirWatcher = fs.watch(targetDir, (eventType, filename) => {
      if (filename && filename.endsWith('.trail')) {
        console.log(`Directory change detected: ${eventType} on ${filename}`);
        if (getHubWindow()) {
          getHubWindow().webContents.send('calls:list-updated');
        }
      }
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
let sessionChunkOffset = 0;
let diarizationInterval = null;
let activeSession = null;
let activeRecordingSessionId = null;
let captureMicSourceOverride = null;
let decryptionKeys = new Map();

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
  encryptionPassword: '',
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

// Ensure new settings fields are populated
if (!settings.selectedNoteStyle) settings.selectedNoteStyle = 'executive';
if (!settings.notePromptTemplate) settings.notePromptTemplate = DEFAULT_PROMPTS.executive;
if (!settings.summaryPromptTemplate) settings.summaryPromptTemplate = DEFAULT_PROMPTS.summary;
if (!settings.actionPromptTemplate) settings.actionPromptTemplate = DEFAULT_PROMPTS.actionItems;
if (!settings.aecMode) settings.aecMode = 'os';
if (settings.autoUpdateEnabled === undefined) settings.autoUpdateEnabled = true;

function saveSettings() {
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2), 'utf8');
}

// ----------------------------------------------------
// Database Initialization & Migrations (v0.2)
// ----------------------------------------------------

function initDatabase() {
  return new Promise((resolve, reject) => {
    db.serialize(() => {
      db.run("PRAGMA foreign_keys = ON;");
      
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
          migrateOldData().then(resolve).catch(reject);
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

async function normalizeFolderIdForSave(folderId) {
  const normalized = folderId || UNCATEGORIZED_FOLDER_ID;
  await syncFilesystemFoldersToDb();
  const displayName = normalized === UNCATEGORIZED_FOLDER_ID
    ? 'Trail (root)'
    : normalized.replace(/^fs:/, '') || normalized;
  await ensureFolderRecord(normalized, {
    name: displayName,
    icon: normalized === UNCATEGORIZED_FOLDER_ID ? '🥣' : '📁',
    description: ''
  });
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

function openMeetingWindow(sessionId) {
  if (!windowManager) createHubWindow();
  return windowManager.openMeetingWindow(sessionId);
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

async function saveSessionToDbPromise(session) {
  if (!session) return;
  const tagsStr = JSON.stringify(session.tags || []);
  const suggestedTagsStr = JSON.stringify(session.suggestedTags || []);
  const mtimeMs = Date.now();
  const folderId = await normalizeFolderIdForSave(session.folder_id);
  session.folder_id = folderId;

  try {
    const isEncrypted = session.encrypted ? 1 : 0;
    const password = decryptionKeys.get(session.id) || settings.encryptionPassword;
    
    let payload = '';
    if (isEncrypted && password) {
      payload = encryption.encrypt(JSON.stringify(session), password);
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
    fs.writeFileSync(filePath, payload, 'utf8');
  } catch (err) {
    console.error("Failed to save session to DB", err);
  }
}

function saveSessionToFileSilently(session) {
  saveSessionToDbPromise(session).catch(err => {
    console.error("Failed to save session silently", err);
  });
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
    sessionChunkOffset = activeSession.transcript?.length ? Math.ceil(activeSession.transcript.length / 2) : 0;
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

  setTimeout(async () => {
    const chunkCount = await audioCaptureService.countChunkCandidates();
    await audioCaptureService.flushRemainingChunks();
    sessionChunkOffset += chunkCount;
  }, 1000);
}

async function stopRecordingHandler() {
  if (!isRecording && !isPaused) return;

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

  setTimeout(async () => {
    try {
      await audioCaptureService.flushRemainingChunks();
      await transcriptionService.waitForIdle();
      await finalizeAndSaveSession();
      if (stoppingSessionId) {
        const session = getSession(stoppingSessionId) || await loadSessionPayloadFromDb(stoppingSessionId);
        if (session) broadcastToSession(stoppingSessionId, 'session:updated', session);
      }
    } catch (err) {
      console.error('Error finalizing recording session:', err);
    }
  }, 1000);
}

async function resumeCallTranscriptionHandler(sessionId) {
  try {
    if (isRecording && activeRecordingSessionId && activeRecordingSessionId !== sessionId) {
      return { success: false, conflict: true, activeSessionId: activeRecordingSessionId };
    }

    const res = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!res) return { success: false, error: 'Session not found' };
    
    let session = null;
    if (res.encrypted) {
      const cachedKey = decryptionKeys.get(sessionId);
      if (!cachedKey) {
        return { success: false, requirePassword: true };
      }
      const decrypted = encryption.decrypt(res.encrypted_payload, cachedKey);
      session = JSON.parse(decrypted);
    } else {
      session = JSON.parse(res.encrypted_payload || '{}');
    }
    
    activeSession = session;
    activeRecordingSessionId = sessionId;
    
    // Calculate sessionChunkOffset based on the last segment timestamp
    if (activeSession.transcript && activeSession.transcript.length > 0) {
      const lastSeg = activeSession.transcript[activeSession.transcript.length - 1];
      sessionChunkOffset = Math.ceil(lastSeg.timestampMs / 2000);
    } else {
      sessionChunkOffset = 0;
    }
    
    isPaused = true;
    setSession(session);
    const meetingWindow = openMeetingWindow(sessionId);
    const rebroadcastStatus = () => broadcastRecordingStatus({ isNewSession: false });
    await startRecordingHandler(sessionId);
    if (meetingWindow && !meetingWindow.isDestroyed()) {
      if (meetingWindow.webContents.isLoading()) {
        meetingWindow.webContents.once('did-finish-load', rebroadcastStatus);
      } else {
        rebroadcastStatus();
      }
    } else {
      rebroadcastStatus();
    }
    return { success: true };
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
            session.id = newId;
            session.filePath = path.join(callsDir, `${newId}.trail`);
            await saveSessionPayloadToDb(newId, session);
            await dbRun('DELETE FROM sessions WHERE id = ?', [oldId]);
            const oldPath = path.join(callsDir, `${oldId}.trail.bak`);
            if (fs.existsSync(oldPath)) fs.unlinkSync(oldPath);
          }
        } catch (renameErr) {
          console.error('Failed to rename session after enrichment', renameErr);
        }

        const hub = getHubWindow();
        if (hub) hub.webContents.send('calls:session-summary-ready', session);
        broadcastToSession(session.id, 'session:updated', session);
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
    return d.toISOString().split('T')[0];
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
  
  // Request summary
  llmService.queryComplete(summaryPrompt, model)
    .then(summary => {
      llmService.queryComplete(actionItemsPrompt, model)
        .then(actionItems => {
          callback(summary, actionItems);
        })
        .catch(() => {
          callback(summary, 'Could not load action items. (Ollama error)');
        });
    })
    .catch(() => {
      callback('Could not load summary. (Ollama error)', 'Could not load action items.');
    });
}

async function queryOllama(prompt, model, systemPrompt = '') {
  return llmService.queryComplete(prompt, model, systemPrompt);
}

// ----------------------------------------------------
// IPC Handler Bindings
// ----------------------------------------------------

ipcMain.handle('audio:get-devices', () => {
  return queryAudioDevices();
});

ipcMain.handle('settings:get', () => {
  return settings;
});

ipcMain.handle('settings:get-default-prompts', () => {
  return DEFAULT_PROMPTS;
});

ipcMain.handle('settings:select-directory', async () => {
  if (!getHubWindow()) return null;
  const { filePaths } = await dialog.showOpenDialog(getHubWindow(), {
    properties: ['openDirectory', 'createDirectory']
  });
  if (filePaths && filePaths.length > 0) {
    return filePaths[0];
  }
  return null;
});

ipcMain.handle('settings:save', async (event, newSettings) => {
  const oldStoragePath = settings.customStoragePath || path.join(DATA_DIR, 'calls');
  const newStoragePath = newSettings.customStoragePath || path.join(DATA_DIR, 'calls');
  
  let moveFiles = false;
  if (oldStoragePath !== newStoragePath) {
    const oldExists = fs.existsSync(oldStoragePath);
    const filesToMove = oldExists ? fs.readdirSync(oldStoragePath).filter(f => f.endsWith('.trail') || f.endsWith('.trail.bak')) : [];
    
    if (filesToMove.length > 0) {
      const choice = await dialog.showMessageBox(getHubWindow(), {
        type: 'question',
        buttons: ['Yes', 'No'],
        defaultId: 0,
        title: 'Move Existing Transcripts',
        message: 'Would you like to move your existing transcripts to the new location?',
        detail: `This will move ${filesToMove.length} files to ${newStoragePath}.`
      });
      if (choice.response === 0) {
        moveFiles = true;
      }
    }
    
    if (!fs.existsSync(newStoragePath)) {
      fs.mkdirSync(newStoragePath, { recursive: true });
    }
    
    if (moveFiles) {
      for (const entry of fs.readdirSync(oldStoragePath)) {
        const src = path.join(oldStoragePath, entry);
        const dest = path.join(newStoragePath, entry);
        try {
          if (fs.existsSync(dest)) continue;
          fs.renameSync(src, dest);
        } catch (e) {
          console.error(`Failed to move ${entry}:`, e);
        }
      }
    }
  }
  
  settings = { ...settings, ...newSettings };
  saveSettings();

  if (settings.autoUpdateEnabled !== false) {
    void updateService.checkForUpdates({ manual: false });
  }
  
  // Start watcher on the new directory
  watchCallsDirectory();
  
  // Sync the database with the directory state
  await syncDatabaseWithFiles();
  
  // Reload call list on frontend
  if (getHubWindow()) {
    getHubWindow().webContents.send('calls:list-updated');
  }
  return true;
});

ipcMain.handle('audio:start-recording', async (event, sessionId) => {
  return await startRecordingHandler(sessionId);
});

ipcMain.handle('audio:stop-recording', async () => {
  try {
    await stopRecordingHandler();
    return { success: true };
  } catch (err) {
    console.error('Stop recording failed:', err);
    return { success: false, error: err.message };
  }
});

ipcMain.handle('audio:pause-recording', () => {
  pauseRecordingHandler();
  return true;
});

ipcMain.handle('audio:resume-recording', async (event, sessionId) => {
  if (!isPaused) return false;
  return await startRecordingHandler(sessionId || activeRecordingSessionId);
});

ipcMain.handle('audio:get-recording-status', () => ({
  isRecording,
  isPaused,
  sessionId: activeRecordingSessionId
}));

ipcMain.handle('meetings:open', async (event, sessionId) => {
  openMeetingWindow(sessionId);
  return { success: true };
});

ipcMain.handle('meetings:new', async () => {
  const session = createNewSession({ encryptByDefault: settings.encryptByDefault });
  await saveSessionToDbPromise(session);
  openMeetingWindow(session.id);
  return { success: true, sessionId: session.id };
});

ipcMain.handle('meetings:focus', async (event, sessionId) => {
  let win = windowManager?.getMeetingWindow(sessionId);
  if (!win) {
    openMeetingWindow(sessionId);
    win = windowManager?.getMeetingWindow(sessionId);
  }
  if (win) {
    win.focus();
    if (activeRecordingSessionId === sessionId && isRecording) {
      broadcastRecordingStatus({ isNewSession: false });
    }
    return { success: true };
  }
  return { success: false };
});

ipcMain.handle('session:load-meeting', async (event, sessionId) => {
  let session = getSession(sessionId);
  if (!session) {
    session = await loadSessionPayloadFromDb(sessionId);
    if (session) setSession(session);
  }
  return session;
});

ipcMain.handle('session:set-title', async (event, sessionId, title, userEdited = true) => {
  let session = getSession(sessionId) || await loadSessionPayloadFromDb(sessionId);
  if (!session) return { success: false };
  session.title = title;
  session.titleUserEdited = userEdited;
  if (userEdited) session.titleAutoGenerated = false;
  setSession(session);
  await saveSessionToDbPromise(session);
  broadcastToSession(sessionId, 'session:updated', session);
  const hub = getHubWindow();
  if (hub) hub.webContents.send('calls:list-updated');
  return { success: true };
});

ipcMain.handle('processing:get-jobs', async () => {
  return sessionProcessingService.getJobMap();
});

ipcMain.handle('processing:retry', async (event, sessionId) => {
  await sessionProcessingService.createJob(sessionId, splitTranscriptIntoBlocks((await loadSessionPayloadFromDb(sessionId))?.transcript || []).length);
  return { success: true };
});

ipcMain.handle('calls:resume-transcription', async (event, sessionId) => {
  return await resumeCallTranscriptionHandler(sessionId);
});

ipcMain.handle('chat:query', async (event, payload, legacyTranscriptText) => {
  try {
    const model = settings.selectedLlm;
    const isObjectPayload = payload && typeof payload === 'object';
    const query = isObjectPayload ? payload.query : payload;
    const sessionId = isObjectPayload ? payload.sessionId : null;
    const scope = isObjectPayload ? (payload.scope || 'hub') : 'hub';
    const activeTranscriptText = isObjectPayload
      ? (payload.transcriptText || '')
      : (legacyTranscriptText || '');

    const oneWeekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const rows = await dbAll("SELECT title, date, summary, actionItems, mixNotes, enhancedNotes, mtimeMs FROM sessions");

    let historyContext = '';
    if (scope === 'meeting') {
      historyContext = 'You are answering about the CURRENT MEETING first. Past sessions are secondary context unless the user explicitly asks about history (e.g. "past week", "recent discussions").\n\n';
      const recent = rows.filter((r) => (r.mtimeMs || 0) >= oneWeekAgo);
      historyContext += 'Recent sessions (past 7 days):\n';
      recent.forEach((r) => {
        historyContext += `- [${r.date}] ${r.title}\n`;
        if (r.summary) historyContext += `  Summary: ${r.summary}\n`;
      });
    } else {
      historyContext = 'Here is the context of past calls:\n';
      rows.forEach((r) => {
        historyContext += `- [${r.date}] Title: ${r.title}\n`;
        if (r.summary) historyContext += `  Summary: ${r.summary}\n`;
        if (r.actionItems) historyContext += `  Action Items: ${r.actionItems}\n`;
        historyContext += '\n';
      });
    }

    const systemPrompt = scope === 'meeting'
      ? `You are an offline AI meeting assistant focused on the active meeting transcript. Use past session context only when the user asks about broader history or trends. Respond in clean Markdown.`
      : `You are an offline AI meeting assistant with access to session history. Respond in clean Markdown.`;

    const recipePrompt = buildRecipePrompt(query);
    const resolvedQuery = recipePrompt || query;

    const userPrompt = `${historyContext}
Current Meeting Transcript (primary):
${activeTranscriptText || 'No active transcript.'}

User Question:
${resolvedQuery}`;

    const requestId = `chat_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const targetWindow = BrowserWindow.fromWebContents(event.sender);
    void llmService.streamToWindow(targetWindow, requestId, userPrompt, model, systemPrompt).catch((err) => {
      console.error('Chat stream error', err);
    });
    return { requestId };
  } catch (err) {
    console.error('Chat query error', err);
    return {
      requestId: null,
      error: 'Could not query local AI model. Please verify Ollama is running.'
    };
  }
});

ipcMain.handle('chat:get-recipes', async () => {
  return CHAT_RECIPES;
});

ipcMain.handle('chat:mix-enhance', async (event, payload) => {
  try {
    const transcript = Array.isArray(payload?.transcript) ? payload.transcript : [];
    const editorDocument = documentFromSession({
      mixNotes: payload?.plainText || payload?.jots || '',
      editorDocument: payload?.editorDocument || null
    });

    const templateKey = payload?.template || settings.selectedNoteStyle || 'executive';
    const systemPrompt = templateKey === 'custom'
      ? (settings.notePromptTemplate || DEFAULT_PROMPTS.executive)
      : (DEFAULT_PROMPTS[templateKey] || settings.notePromptTemplate || DEFAULT_PROMPTS.executive);

    const result = await enhanceNotesService.enhanceDocument({
      editorDocument,
      fullTranscript: enhanceNotesService.buildFullTranscript(transcript),
      transcriptSegments: transcript,
      model: settings.selectedLlm,
      systemPrompt
    });

    if (!result) {
      return { success: false, error: 'Add some jots before enhancing notes.' };
    }

    return { success: true, ...result };
  } catch (err) {
    console.error("Mix & Enhance error", err);
    return {
      success: false,
      error: "Failed to run Mix & Enhance. Please ensure Ollama is running and the model is loaded."
    };
  }
});

ipcMain.handle('llm:cancel', (event, requestId) => {
  return { success: llmService.cancelStream(requestId) };
});

ipcMain.handle('models:get-specs', () => {
  return getHardwareSpecs();
});

ipcMain.handle('models:get-ollama-models', async () => {
  try {
    const res = await fetch('http://localhost:11434/api/tags');
    if (res.ok) {
      const data = await res.json();
      return data.models || [];
    }
  } catch (e) {
    console.error('Ollama is not running');
  }
  return [];
});

ipcMain.handle('models:download-whisper', async (event, modelName) => {
  return new Promise((resolve, reject) => {
    // Download Whisper GGUF Model using the download script
    const downloadScript = path.join(WHISPER_DIR, 'models', 'download-ggml-model.sh');
    if (!fs.existsSync(downloadScript)) {
      reject('Whisper models setup script not found.');
      return;
    }
    
    // Spawn script execution
    const proc = spawn('bash', [downloadScript, modelName], { cwd: path.join(WHISPER_DIR, 'models') });
    
    proc.stdout.on('data', (data) => {
      // Send download progress to frontend
      const output = data.toString();
      if (getHubWindow()) getHubWindow().webContents.send('models:on-download-progress', output);
    });
    
    proc.on('close', (code) => {
      if (code === 0) {
        resolve(true);
      } else {
        reject(`Download script exited with code ${code}`);
      }
    });
  });
});

ipcMain.handle('calls:decrypt-multiple', async (event, sessionIds, password) => {
  const unlocked = [];
  const failed = [];

  for (const sessionId of sessionIds || []) {
    try {
      const session = await dbGet('SELECT * FROM sessions WHERE id = ?', [sessionId]);
      if (!session || session.encrypted !== 1) {
        unlocked.push(sessionId);
        continue;
      }
      const decrypted = encryption.decrypt(session.encrypted_payload, password);
      const sessionData = JSON.parse(decrypted);
      sessionData.folder_id = session.folder_id;
      decryptionKeys.set(sessionData.id, password);
      await saveSessionToDbPromise(sessionData);
      unlocked.push(sessionId);
    } catch (err) {
      failed.push({ sessionId, error: err.message });
    }
  }

  if (getHubWindow()) getHubWindow().webContents.send('calls:list-updated');
  return { success: failed.length === 0, unlocked, failed };
});

ipcMain.handle('calls:get-list', async () => {
  try {
    await migrateOldData();
    await syncDatabaseWithFiles();
    const processingJobs = await sessionProcessingService.getJobMap();
    const rows = await dbAll("SELECT * FROM sessions ORDER BY mtimeMs DESC");
    return rows.map(row => {
      let tags = [];
      let suggestedTags = [];
      try { tags = JSON.parse(row.tags || '[]'); } catch (e) {}
      try { suggestedTags = JSON.parse(row.suggestedTags || '[]'); } catch (e) {}
      
      return {
        id: row.id,
        title: row.title || 'Meeting Session',
        date: row.date,
        encrypted: row.encrypted === 1,
        unlocked: row.encrypted !== 1 || decryptionKeys.has(row.id),
        filePath: row.id,
        storagePath: resolveSessionFilePath(getCallsDir(), row.id, row.folder_id || UNCATEGORIZED_FOLDER_ID),
        summary: row.summary,
        description: row.description || 'No description available.',
        tags: tags,
        suggestedTags: suggestedTags,
        folder_id: row.folder_id,
        mtimeMs: row.mtimeMs,
        processing: processingJobs[row.id] || null
      };
    });
  } catch (err) {
    console.error(err);
    return [];
  }
});

ipcMain.handle('calls:decrypt', async (event, sessionId, password) => {
  try {
    const session = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!session) return { success: false, error: 'Session not found' };
    
    const decrypted = encryption.decrypt(session.encrypted_payload, password);
    const sessionData = JSON.parse(decrypted);
    sessionData.folder_id = session.folder_id;
    
    decryptionKeys.set(sessionData.id, password);
    return { success: true, session: sessionData };
  } catch (error) {
    return { success: false, error: error.message };
  }
});

ipcMain.handle('calls:load', async (event, sessionId, password) => {
  try {
    const session = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!session) return { success: false, error: 'Session not found' };
    
    if (session.encrypted) {
      const cachedKey = password || decryptionKeys.get(sessionId) || settings.encryptionPassword;
      if (!cachedKey) {
        return { success: false, requirePassword: true };
      }
      
      const decrypted = encryption.decrypt(session.encrypted_payload, cachedKey);
      const sessionData = JSON.parse(decrypted);
      sessionData.folder_id = session.folder_id;
      decryptionKeys.set(sessionData.id, cachedKey);
      return { success: true, session: sessionData };
    } else {
      let sessionData = {};
      try {
        sessionData = JSON.parse(session.encrypted_payload);
      } catch (e) {
        sessionData = {
          id: session.id,
          date: session.date,
          title: session.title,
          description: session.description,
          summary: session.summary,
          actionItems: session.actionItems,
          mixNotes: session.mixNotes,
          enhancedNotes: session.enhancedNotes,
          encrypted: false,
          folder_id: session.folder_id,
          tags: JSON.parse(session.tags || '[]'),
          suggestedTags: JSON.parse(session.suggestedTags || '[]')
        };
      }
      return { success: true, session: sessionData };
    }
  } catch (error) {
    return { success: false, error: error.message };
  }
});

ipcMain.handle('calls:save', async (event, callData, password) => {
  try {
    const encryptionPassword = password || decryptionKeys.get(callData.id);
    if (encryptionPassword) {
      callData.encrypted = true;
      decryptionKeys.set(callData.id, encryptionPassword);
    }
    
    await saveSessionToDbPromise(callData);
    if (getHubWindow()) getHubWindow().webContents.send('calls:list-updated');
    return true;
  } catch (err) {
    console.error(err);
    return false;
  }
});

ipcMain.handle('calls:save-silently', async (event, callData) => {
  try {
    await saveSessionToDbPromise(callData);
    return true;
  } catch (err) {
    console.error(err);
    return false;
  }
});

ipcMain.handle('calls:delete', async (event, sessionId) => {
  try {
    await dbRun("DELETE FROM sessions WHERE id = ?", [sessionId]);
    if (getHubWindow()) getHubWindow().webContents.send('calls:list-updated');
    return { success: true };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle('calls:delete-multiple', async (event, sessionIds) => {
  try {
    if (sessionIds.length === 0) return { success: true };
    const placeholders = sessionIds.map(() => '?').join(',');
    await dbRun(`DELETE FROM sessions WHERE id IN (${placeholders})`, sessionIds);
    if (getHubWindow()) getHubWindow().webContents.send('calls:list-updated');
    return { success: true };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle('calls:merge', async (event, sessionIds) => {
  try {
    const sessions = [];
    for (const id of sessionIds) {
      const res = await dbGet("SELECT * FROM sessions WHERE id = ?", [id]);
      if (res) {
        if (res.encrypted) {
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            const decrypted = encryption.decrypt(res.encrypted_payload, cachedKey);
            sessions.push(JSON.parse(decrypted));
          } else {
            throw new Error(`Cannot merge encrypted session ${id} without cached password.`);
          }
        } else {
          sessions.push(JSON.parse(res.encrypted_payload || '{}'));
        }
      }
    }

    if (sessions.length < 2) {
      return { success: false, error: 'Need at least 2 sessions to merge.' };
    }

    sessions.sort((a, b) => {
      const dateA = new Date(a.date);
      const dateB = new Date(b.date);
      return dateA - dateB;
    });

    const mergedTranscript = [];
    let cumulativeTimeMs = 0;
    
    sessions.forEach((sess, idx) => {
      if (idx > 0) {
        cumulativeTimeMs += 5000;
      }
      const startOffsetMs = cumulativeTimeMs;
      
      if (sess.transcript) {
        sess.transcript.forEach((seg) => {
          mergedTranscript.push({
            ...seg,
            id: `merged_${sess.id}_${seg.id}`,
            timestampMs: startOffsetMs + seg.timestampMs,
            timestamp: formatTimestamp(startOffsetMs + seg.timestampMs)
          });
        });
      }
      
      if (sess.transcript && sess.transcript.length > 0) {
        const lastSeg = sess.transcript[sess.transcript.length - 1];
        cumulativeTimeMs += lastSeg.timestampMs + 3000;
      }
    });

    const titles = sessions.map(s => s.title).join(' & ');
    const newSession = {
      id: 'merged_' + Date.now(),
      date: new Date().toLocaleString(),
      title: `Merged: ${titles}`,
      transcript: mergedTranscript,
      summary: sessions.map(s => `--- ${s.title} ---\n${s.summary || ''}`).join('\n\n'),
      actionItems: sessions.map(s => `--- ${s.title} ---\n${s.actionItems || ''}`).join('\n\n'),
      encrypted: false,
      folder_id: 'work'
    };

    await saveSessionToDbPromise(newSession);
    if (getHubWindow()) getHubWindow().webContents.send('calls:list-updated');
    return { success: true, session: newSession };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle('calls:export', async (event, sessionIds) => {
  try {
    let combinedMarkdown = '';
    
    for (const id of sessionIds) {
      const res = await dbGet("SELECT * FROM sessions WHERE id = ?", [id]);
      if (res) {
        let session = null;
        if (res.encrypted) {
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            const decrypted = encryption.decrypt(res.encrypted_payload, cachedKey);
            session = JSON.parse(decrypted);
          }
        } else {
          session = JSON.parse(res.encrypted_payload || '{}');
        }
        
        if (session) {
          combinedMarkdown += `# ${session.title}\n`;
          combinedMarkdown += `Date: ${session.date}\n\n`;
          combinedMarkdown += `## Transcript\n`;
          if (session.transcript) {
            session.transcript.forEach((t) => {
              combinedMarkdown += `[${t.timestamp}] ${t.speaker}: ${t.text}\n`;
            });
          }
          combinedMarkdown += `\n## Highlights Summary\n${session.summary || 'No summary'}\n\n`;
          combinedMarkdown += `## Action Items\n${session.actionItems || 'No action items'}\n`;
          combinedMarkdown += `\n---\n\n`;
        }
      }
    }

    const { filePath } = await dialog.showSaveDialog(getHubWindow(), {
      title: 'Export Transcripts',
      defaultPath: path.join(app.getPath('downloads'), 'trailmix_export.md'),
      filters: [{ name: 'Markdown/Text Files', extensions: ['md', 'txt'] }]
    });

    if (filePath) {
      fs.writeFileSync(filePath, combinedMarkdown, 'utf8');
      return { success: true, filePath };
    }
    return { success: false, error: 'Export canceled' };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle('calls:find-related', async (event, sessionId) => {
  try {
    const targetRow = await dbGet("SELECT * FROM sessions WHERE id = ?", [sessionId]);
    if (!targetRow) return [];
    
    let targetSession = null;
    if (targetRow.encrypted) {
      const cachedKey = decryptionKeys.get(sessionId);
      if (cachedKey) {
        targetSession = JSON.parse(encryption.decrypt(targetRow.encrypted_payload, cachedKey));
      }
    } else {
      targetSession = JSON.parse(targetRow.encrypted_payload || '{}');
    }
    
    if (!targetSession || !targetSession.transcript) return [];

    const targetSpeakers = new Set(targetSession.transcript.map(t => t.speaker.toLowerCase()).filter(s => s !== 'you' && !s.startsWith('speaker')));
    const targetWords = new Set(targetSession.title.toLowerCase().split(/\s+/).filter(w => w.length > 4));

    const rows = await dbAll("SELECT * FROM sessions WHERE id != ?", [sessionId]);
    const related = [];

    for (const r of rows) {
      try {
        let session = null;
        if (r.encrypted) {
          const cachedKey = decryptionKeys.get(r.id);
          if (cachedKey) {
            session = JSON.parse(encryption.decrypt(r.encrypted_payload, cachedKey));
          }
        } else {
          session = JSON.parse(r.encrypted_payload || '{}');
        }

        if (session && session.transcript) {
          let score = 0;
          session.transcript.forEach(t => {
            const spk = t.speaker.toLowerCase();
            if (targetSpeakers.has(spk)) {
              score += 5;
            }
          });

          const titleWords = session.title.toLowerCase().split(/\s+/).filter(w => w.length > 4);
          titleWords.forEach(w => {
            if (targetWords.has(w)) {
              score += 2;
            }
          });

          if (score > 0) {
            related.push({
              id: session.id,
              title: session.title,
              date: session.date,
              score: score,
              filePath: session.id
            });
          }
        }
      } catch (err) {}
    }

    related.sort((a, b) => b.score - a.score);
    return related;
  } catch (e) {
    console.error(e);
    return [];
  }
});

// Obsidian vault exports (v0.2 Compatibility)
ipcMain.handle('calls:export-obsidian', async (event, folderId, exportDir) => {
  try {
    if (!fs.existsSync(exportDir)) {
      fs.mkdirSync(exportDir, { recursive: true });
    }
    
    let sql = "SELECT * FROM sessions";
    let params = [];
    if (folderId && folderId !== 'all') {
      sql = "SELECT * FROM sessions WHERE folder_id = ?";
      params = [folderId];
    }
    
    const sessions = await dbAll(sql, params);
    let count = 0;
    
    for (const sessionRow of sessions) {
      let session = null;
      if (sessionRow.encrypted) {
        const cachedKey = decryptionKeys.get(sessionRow.id);
        if (cachedKey) {
          session = JSON.parse(encryption.decrypt(sessionRow.encrypted_payload, cachedKey));
        }
      } else {
        session = JSON.parse(sessionRow.encrypted_payload || '{}');
      }
      
      if (session) {
        const cleanTitle = sessionRow.title.replace(/[^a-zA-Z0-9\s_-]/g, '').trim() || 'Session';
        const mdName = `${cleanTitle}_${sessionRow.id}.md`;
        const mdPath = path.join(exportDir, mdName);
        
        let mdContent = `# ${sessionRow.title}\n`;
        mdContent += `Date: ${sessionRow.date}\n`;
        if (session.tags && session.tags.length > 0) {
          mdContent += `Tags: ${session.tags.map(t => `#${t}`).join(' ')}\n`;
        }
        mdContent += `\n---\n\n`;
        mdContent += `## Jots (Manual Notes)\n${session.mixNotes || session.editorDocument?.plainText || 'No manual jots.'}\n\n`;
        mdContent += `## Blended Notes (AI Enhanced)\n${session.enhancedNotes || 'No enhanced notes.'}\n\n`;
        mdContent += `## Highlights Summary\n${session.summary || 'No summary.'}\n\n`;
        mdContent += `## Action Items\n${session.actionItems || 'No action items.'}\n`;
        
        fs.writeFileSync(mdPath, mdContent, 'utf8');
        count++;
      }
    }
    return { success: true, count };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

// Folders Management — filesystem-backed under save location
ipcMain.handle('folders:get', async () => {
  try {
    await syncDatabaseWithFiles();
    const { folders } = scanStorageLayout(getCallsDir());
    return folders.filter((folder) => folder.id !== ROOT_FOLDER_ID);
  } catch (err) {
    console.error(err);
    return [];
  }
});

ipcMain.handle('folders:create', async (event, payload) => {
  try {
    const name = typeof payload === 'string' ? payload : payload?.name;
    const folder = ensureFolderDir(getCallsDir(), name);
    await ensureFolderRecord(folder.id, {
      name: folder.name,
      icon: payload?.icon || '📁',
      description: payload?.description || ''
    });
    return { success: true, folder };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('folders:update', async (event, folder) => {
  try {
    if (!folder?.id?.startsWith('fs:') || folder.id === UNCATEGORIZED_FOLDER_ID) {
      return { success: true };
    }
    const relative = relativePathFromFolderId(folder.id);
    const oldPath = path.join(getCallsDir(), relative);
    const newName = (folder.name || relative).trim().replace(/[\\/]/g, '_');
    const newPath = path.join(getCallsDir(), newName);
    if (oldPath !== newPath && fs.existsSync(oldPath)) {
      fs.renameSync(oldPath, newPath);
      const sessions = await dbAll('SELECT id FROM sessions WHERE folder_id = ?', [folder.id]);
      const newFolderId = `fs:${newName}`;
      for (const session of sessions) {
        await dbRun('UPDATE sessions SET folder_id = ? WHERE id = ?', [newFolderId, session.id]);
        const file = resolveSessionFilePath(getCallsDir(), session.id, folder.id);
        const dest = resolveSessionFilePath(getCallsDir(), session.id, newFolderId);
        if (fs.existsSync(file)) {
          fs.mkdirSync(path.dirname(dest), { recursive: true });
          fs.renameSync(file, dest);
        }
      }
    }
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('folders:delete', async (event, id) => {
  try {
    if (!id?.startsWith('fs:') || id === UNCATEGORIZED_FOLDER_ID) {
      return { success: false, error: 'Cannot delete this folder' };
    }
    const relative = relativePathFromFolderId(id);
    const folderPath = path.join(getCallsDir(), relative);
    if (fs.existsSync(folderPath)) {
      const remaining = fs.readdirSync(folderPath);
      if (remaining.length > 0) {
        return { success: false, error: 'Folder is not empty. Move or delete notes first.' };
      }
      fs.rmdirSync(folderPath);
    }
    await dbRun('UPDATE sessions SET folder_id = ? WHERE folder_id = ?', [UNCATEGORIZED_FOLDER_ID, id]);
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('calls:move-to-folder', async (event, sessionId, folderId) => {
  try {
    const normalizedFolderId = folderId || UNCATEGORIZED_FOLDER_ID;
    const row = await dbGet('SELECT folder_id FROM sessions WHERE id = ?', [sessionId]);
    const fromFolderId = row?.folder_id || UNCATEGORIZED_FOLDER_ID;
    const moveResult = moveSessionFile(getCallsDir(), sessionId, fromFolderId, normalizedFolderId);
    if (!moveResult.success) {
      return moveResult;
    }
    await dbRun('UPDATE sessions SET folder_id = ? WHERE id = ?', [normalizedFolderId, sessionId]);
    if (getHubWindow()) getHubWindow().webContents.send('calls:list-updated');

    if (normalizedFolderId && normalizedFolderId !== UNCATEGORIZED_FOLDER_ID) {
      const folderName = relativePathFromFolderId(normalizedFolderId);
      const session = await dbGet('SELECT title FROM sessions WHERE id = ?', [sessionId]);
      void appEventBus.emitWorkflowEvent('note:added-to-folder', {
        sessionId,
        folderId: normalizedFolderId,
        folderName,
        sessionTitle: session?.title || null
      });
    }

    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('workflow:register-trigger', async (event, eventName, trigger) => {
  appEventBus.registerWorkflowTrigger(eventName, trigger);
  return { success: true };
});

// Tasks Management (v0.3)
async function saveTaskToDb(task) {
  await dbRun(`INSERT INTO tasks (
    id, text, assignee, completed, dueDate, omitted,
    sourceCallId, sourceCallTitle, sourceSegmentId, sourceTimestamp
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
    task.id,
    task.text,
    task.assignee || 'Unassigned',
    task.completed ? 1 : 0,
    task.dueDate || '',
    task.omitted ? 1 : 0,
    task.sourceCallId,
    task.sourceCallTitle || '',
    task.sourceSegmentId || '',
    task.sourceTimestamp || ''
  ]);
}

async function getTasksListFromDb() {
  try {
    const rows = await dbAll("SELECT * FROM tasks");
    return rows.map(formatTaskRow);
  } catch (err) {
    console.error("Error getting tasks from DB:", err);
    return [];
  }
}

ipcMain.handle('tasks:get', async () => {
  return await getTasksListFromDb();
});

ipcMain.handle('tasks:toggle', async (event, taskId) => {
  try {
    const task = formatTaskRow(await dbGet("SELECT * FROM tasks WHERE id = ?", [taskId]));
    if (task) {
      task.completed = !task.completed;
      await dbRun("UPDATE tasks SET completed = ? WHERE id = ?", [task.completed ? 1 : 0, taskId]);
      return { success: true, task };
    }
    return { success: false, error: 'Task not found' };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('tasks:delete', async (event, taskId) => {
  try {
    await dbRun("DELETE FROM tasks WHERE id = ?", [taskId]);
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('tasks:set-omitted', async (event, taskId, omitted) => {
  try {
    const omittedVal = omitted ? 1 : 0;
    await dbRun("UPDATE tasks SET omitted = ? WHERE id = ?", [omittedVal, taskId]);
    const task = formatTaskRow(await dbGet("SELECT * FROM tasks WHERE id = ?", [taskId]));
    if (task) {
      return { success: true, task };
    }
    return { success: false, error: 'Task not found' };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('tasks:delete-multiple', async (event, taskIds) => {
  try {
    if (taskIds.length === 0) return { success: true };
    const placeholders = taskIds.map(() => '?').join(',');
    await dbRun(`DELETE FROM tasks WHERE id IN (${placeholders})`, taskIds);
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('calls:open-file-location', () => {
  shell.openPath(XDG_CONFIG_DIR);
  return { success: true };
});

ipcMain.on('app:minimize', () => {
  if (getHubWindow()) getHubWindow().minimize();
});

ipcMain.on('app:relaunch', () => {
  if (getHubWindow()) {
    getHubWindow().restore();
    getHubWindow().focus();
  }
  if (windowManager && activeRecordingSessionId) {
    windowManager.restoreMeetingFromMini(activeRecordingSessionId);
  }
});

ipcMain.handle('app:get-version', () => app.getVersion());

ipcMain.handle('updates:check', async (event, options = {}) => {
  return updateService.checkForUpdates(options);
});

ipcMain.handle('updates:install', async () => {
  updateService.quitAndInstall();
  return { success: true };
});

ipcMain.handle('updates:get-status', () => {
  return updateService.getStatus(app.getVersion());
});

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

// App Lifecycles
app.whenReady().then(async () => {
  cleanupStaleLoopbackModules();
  await initDatabase();
  await migrateOldData();
  await syncFilesystemFoldersToDb();
  await sessionProcessingService.initSchema();
  await sessionProcessingService.recoverInterruptedJobs();
  createHubWindow();
  const whisperCli = resolveWhisperCli(WHISPER_DIR);
  if (!fs.existsSync(whisperCli)) {
    console.warn(
      `Whisper binary missing at ${whisperCli}. Transcription will not work until you run: ./scripts/setup-whisper.sh`
    );
  }
  updateService.configure({
    getHubWindow,
    getAutoUpdateEnabled: () => settings.autoUpdateEnabled !== false
  });
  updateService.getStatus(app.getVersion());
  if (settings.autoUpdateEnabled !== false) {
    void updateService.checkForUpdates({ manual: false });
  }
  setupTray();
  watchCallsDirectory();
  broadcastProcessingProgress();
  
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createHubWindow();
  });
});

app.on('before-quit', () => {
  transcriptionService.shutdown();
  audioCaptureService.stopFfmpeg().catch(() => {});
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
