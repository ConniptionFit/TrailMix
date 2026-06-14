const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, screen, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { exec, execSync, spawn } = require('child_process');
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
        if (mainWindow) {
          mainWindow.webContents.send('calls:list-updated');
        }
      }
    });
  } catch (err) {
    console.error(`Error watching directory ${targetDir}:`, err);
  }
}

// State Variables
let mainWindow = null;
let miniWindow = null;
let tray = null;
let recordingProcess = null;
let isRecording = false;
let isPaused = false;
let sessionChunkOffset = 0;
let chunkWatcher = null;
let diarizationInterval = null;
let processedChunks = new Set();
let activeSession = null;
let decryptionKeys = new Map(); // In-memory cache for decrypted session keys

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
        name TEXT NOT NULL
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

async function migrateOldData() {
  try {
    // 1. Setup default folders if empty
    const existingFolders = await dbAll("SELECT * FROM folders");
    if (existingFolders.length === 0) {
      await dbRun("INSERT INTO folders (id, name) VALUES (?, ?)", ["work", "Work"]);
      await dbRun("INSERT INTO folders (id, name) VALUES (?, ?)", ["personal", "Personal"]);
      await dbRun("INSERT INTO folders (id, name) VALUES (?, ?)", ["drafts", "Drafts"]);
    }

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

async function syncDatabaseWithFiles() {
  try {
    const callsDir = getCallsDir();
    if (!fs.existsSync(callsDir)) return;
    const files = fs.readdirSync(callsDir);
    const existingIds = new Set(
      files
        .filter(f => f.endsWith('.trail') || f.endsWith('.trail.bak'))
        .map(f => f.replace('.trail.bak', '').replace('.trail', ''))
    );
    
    // Get all sessions from DB
    const sessions = await dbAll("SELECT id FROM sessions");
    for (const session of sessions) {
      if (session.id !== 'live' && !existingIds.has(session.id)) {
        console.log(`Pruning session ${session.id} from database because file does not exist in ${callsDir}`);
        await dbRun("DELETE FROM sessions WHERE id = ?", [session.id]);
      }
    }
  } catch (err) {
    console.error("Error syncing database with files:", err);
  }
}


// ----------------------------------------------------
// VAD & Secure File Shredding (v0.2 Compliance)
// ----------------------------------------------------

function checkVoiceActivity(wavPath) {
  try {
    if (!fs.existsSync(wavPath)) return false;
    const buffer = fs.readFileSync(wavPath);
    if (buffer.length <= 44) return false;
    
    const numChannels = buffer.readUInt16LE(22);
    const bytesPerFrame = numChannels * 2;
    
    let sum = 0;
    let count = 0;
    // Read 16-bit PCM samples
    for (let i = 44; i < buffer.length; i += bytesPerFrame) {
      if (i + 1 < buffer.length) {
        const sampleL = buffer.readInt16LE(i);
        sum += sampleL * sampleL;
        count++;
      }
    }
    if (count === 0) return false;
    const rms = Math.sqrt(sum / count);
    console.log(`VAD check [${path.basename(wavPath)}]: RMS energy = ${Math.round(rms)}`);
    // Threshold of 100 RMS represents standard silence noise gate
    return rms > 100;
  } catch (err) {
    console.error("VAD check failed, treating as active", err);
    return true;
  }
}

function secureShredFile(filePath) {
  try {
    if (!fs.existsSync(filePath)) return;
    const stats = fs.statSync(filePath);
    if (stats.isFile()) {
      const size = stats.size;
      const crypto = require('crypto');
      const randomData = crypto.randomBytes(size);
      fs.writeFileSync(filePath, randomData);
      fs.unlinkSync(filePath);
      console.log(`Secured shredded file: ${path.basename(filePath)}`);
    }
  } catch (err) {
    console.error(`Shred failed for ${filePath}, attempting direct deletion`, err);
    try {
      if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
    } catch (e) {}
  }
}

// ----------------------------------------------------
// Window & Tray Management
// ----------------------------------------------------

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 750,
    title: 'TrailMix',
    icon: path.join(PROJECT_DIR, 'assets', 'logo.png'),
    webPreferences: {
      preload: path.join(PROJECT_DIR, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.webContents.on('console-message', (event, level, message, line, sourceId) => {
    console.log(`[Renderer Console] ${message} (${sourceId}:${line})`);
  });

  mainWindow.loadFile(path.join(PROJECT_DIR, 'renderer', 'index.html'));

  mainWindow.on('minimize', () => {
    if (isRecording) {
      createMiniWindow();
    }
  });

  mainWindow.on('restore', () => {
    destroyMiniWindow();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    destroyMiniWindow();
  });
}

function createMiniWindow() {
  if (miniWindow) return;

  miniWindow = new BrowserWindow({
    width: 260,
    height: 90,
    frame: false,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    webPreferences: {
      preload: path.join(PROJECT_DIR, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  // Load renderer index.html with query param to only render the mini widget
  miniWindow.loadFile(path.join(PROJECT_DIR, 'renderer', 'index.html'), { query: { mode: 'mini' } });

  // Position at bottom-right of screen
  const primaryDisplay = screen.getPrimaryDisplay();
  const { width, height } = primaryDisplay.workAreaSize;
  miniWindow.setPosition(width - 280, height - 110);
}

function destroyMiniWindow() {
  if (miniWindow) {
    miniWindow.close();
    miniWindow = null;
  }
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
        if (mainWindow) {
          mainWindow.restore();
          mainWindow.focus();
        } else {
          createMainWindow();
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
    if (mainWindow) {
      mainWindow.restore();
      mainWindow.focus();
    } else {
      createMainWindow();
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
        1, payload, session.folder_id || 'work', mtimeMs, tagsStr, suggestedTagsStr
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
        0, payload, session.folder_id || 'work', mtimeMs, tagsStr, suggestedTagsStr
      ]);
    }
    
    // Write file to save location directory as .trail.bak
    const callsDir = getCallsDir();
    if (!fs.existsSync(callsDir)) {
      fs.mkdirSync(callsDir, { recursive: true });
    }
    const filePath = path.join(callsDir, `${session.id}.trail.bak`);
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

const whisperQueue = [];
let isWhisperRunning = false;

function enqueueWhisperChunk(chunkPath, chunkIdx) {
  whisperQueue.push({ chunkPath, chunkIdx });
  processWhisperQueue();
}

async function processWhisperQueue() {
  if (isWhisperRunning || whisperQueue.length === 0) return;
  
  isWhisperRunning = true;
  const { chunkPath, chunkIdx } = whisperQueue.shift();
  
  try {
    await runWhisperOnChunkPromise(chunkPath, chunkIdx);
  } catch (err) {
    console.error('Whisper chunk processing failed', err);
  } finally {
    isWhisperRunning = false;
    processWhisperQueue();
  }
}

function runWhisperOnChunkPromise(chunkWavPath, chunkIndex) {
  return new Promise(async (resolve) => {
    const whisperCli = path.join(WHISPER_DIR, 'build', 'bin', 'whisper-cli');
    const whisperModel = path.join(WHISPER_DIR, 'models', settings.selectedModel);
    
    if (!fs.existsSync(whisperCli)) {
      console.error('Whisper.cpp binary not found at', whisperCli);
      secureShredFile(chunkWavPath);
      resolve();
      return;
    }
    if (!fs.existsSync(whisperModel)) {
      console.error('Whisper model not found at', whisperModel);
      secureShredFile(chunkWavPath);
      resolve();
      return;
    }

    const leftWavPath = chunkWavPath.replace('.wav', '_left.wav');
    const rightWavPath = chunkWavPath.replace('.wav', '_right.wav');
    
    // Extract Left channel (System Monitor / "Them") and Right channel (Microphone / "You")
    try {
      execSync(`ffmpeg -y -i "${chunkWavPath}" -af "pan=mono|c0=c0" "${leftWavPath}"`);
      execSync(`ffmpeg -y -i "${chunkWavPath}" -af "pan=mono|c0=c1" "${rightWavPath}"`);
    } catch (err) {
      console.error('Ffmpeg channel splitting failed', err);
      secureShredFile(chunkWavPath);
      resolve();
      return;
    }
    
    // Check Voice Activity Detection (VAD) noise gate for both channels
    const hasLeftVoice = checkVoiceActivity(leftWavPath);
    const hasRightVoice = checkVoiceActivity(rightWavPath);
    
    if (!hasLeftVoice && !hasRightVoice) {
      console.log(`VAD noise gate: Chunk ${chunkIndex} Left & Right are silent. Dropping chunk.`);
      secureShredFile(chunkWavPath);
      secureShredFile(leftWavPath);
      secureShredFile(rightWavPath);
      resolve();
      return;
    }
    
    const allSegments = [];
    
    // Helper to transcribe a mono channel file
    const transcribeMonoFile = (monoWavPath, speakerName) => {
      return new Promise((resolveTranscribe) => {
        const outputBase = monoWavPath.replace('.wav', '_trans');
        const jsonPath = outputBase + '.json';
        const threads = Math.min(8, Math.max(4, require('os').cpus().length - 2));
        const whisperCmd = `"${whisperCli}" -m "${whisperModel}" -f "${monoWavPath}" -t ${threads} -oj -of "${outputBase}"`;
        
        exec(whisperCmd, (error) => {
          if (error) {
            console.error(`Whisper execution failed for ${speakerName}`, error);
            resolveTranscribe([]);
            return;
          }
          
          if (!fs.existsSync(jsonPath)) {
            console.error(`Whisper JSON file not found for ${speakerName}`, jsonPath);
            resolveTranscribe([]);
            return;
          }
          
          try {
            const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
            const segments = data.transcription || [];
            const results = [];
            
            segments.forEach((seg) => {
              const text = seg.text.trim();
              if (!text) return;
              
              if (/^\[blank_audio\]$/i.test(text) || text.toUpperCase().includes('BLANK_AUDIO')) {
                return;
              }
              
              results.push({
                fromMs: seg.offsets.from,
                toMs: seg.offsets.to,
                speaker: speakerName,
                text: text
              });
            });
            
            try {
              if (fs.existsSync(jsonPath)) fs.unlinkSync(jsonPath);
            } catch (e) {}
            
            resolveTranscribe(results);
          } catch (e) {
            console.error(`Error parsing whisper JSON output for ${speakerName}`, e);
            resolveTranscribe([]);
          }
        });
      });
    };
    
    // Run transcriptions sequentially to keep CPU/RAM usage low and optimized
    if (hasLeftVoice) {
      const leftSegs = await transcribeMonoFile(leftWavPath, 'Speaker 1');
      allSegments.push(...leftSegs);
    } else {
      secureShredFile(leftWavPath);
    }
    
    if (hasRightVoice) {
      const rightSegs = await transcribeMonoFile(rightWavPath, 'You');
      allSegments.push(...rightSegs);
    } else {
      secureShredFile(rightWavPath);
    }
    
    // Merge and sort segments chronologically by offset start time
    allSegments.sort((a, b) => a.fromMs - b.fromMs);
    
    allSegments.forEach((seg) => {
      const sessionOffsetMs = (sessionChunkOffset + chunkIndex) * 2000 + seg.fromMs;
      const timestampStr = formatTimestamp(sessionOffsetMs);
      
      const transcriptSegment = {
        id: `${activeSession.id}_${sessionChunkOffset + chunkIndex}_${seg.speaker.replace(/\s+/g, '_')}_${seg.fromMs}`,
        timestampMs: sessionOffsetMs,
        timestamp: timestampStr,
        speaker: seg.speaker,
        text: seg.text,
        chunkIndex: sessionChunkOffset + chunkIndex,
        wallTimeMs: Date.now()
      };
      
      activeSession.transcript.push(transcriptSegment);
      
      if (mainWindow) {
        mainWindow.webContents.send('audio:on-transcription-update', transcriptSegment);
      }
      if (miniWindow) {
        miniWindow.webContents.send('audio:on-transcription-update', transcriptSegment);
      }
    });
    
    if (allSegments.length > 0) {
      saveSessionToFileSilently(activeSession);
    }
    
    // Clean up temporary files
    secureShredFile(chunkWavPath);
    secureShredFile(leftWavPath);
    secureShredFile(rightWavPath);
    
    resolve();
  });
}

function formatTimestamp(ms) {
  const totalSecs = Math.floor(ms / 1000);
  const m = Math.floor(totalSecs / 60);
  const s = totalSecs % 60;
return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}

// ----------------------------------------------------
// Recording Controls
// ----------------------------------------------------

function startRecordingHandler() {
  if (isRecording) return;
  
  const devices = queryAudioDevices();
  let sink = devices.sink;
  let source = devices.source;
  
  // Resolve microphone input device
  if (settings.selectedMic && settings.selectedMic !== 'default') {
    source = settings.selectedMic;
  }
  // Resolve system monitor output device
  if (settings.selectedSink && settings.selectedSink !== 'default') {
    sink = settings.selectedSink;
  }
  
  if (!sink || !source) {
    console.error('No audio devices found to transcribe.');
    return;
  }
  
  const sinkMonitor = sink.endsWith('.monitor') ? sink : `${sink}.monitor`;
  console.log(`Starting transcription. Sink monitor: ${sinkMonitor}, Source: ${source}`);
  
  // Clean up temporary recording folder unless we are resuming/paused
  if (!isPaused) {
    if (fs.existsSync(TEMP_DIR)) {
      fs.rmSync(TEMP_DIR, { recursive: true, force: true });
    }
    fs.mkdirSync(TEMP_DIR, { recursive: true });
    processedChunks.clear();
    whisperQueue.length = 0;
    isWhisperRunning = false;
    
    // Check if we are resuming a loaded session or starting a new one
    if (!activeSession || activeSession.id === 'live') {
      activeSession = {
        id: 'call_' + Date.now(),
        date: new Date().toLocaleString(),
        title: 'New Session ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
        transcript: [],
        summary: '',
        actionItems: '',
        encrypted: settings.encryptByDefault
      };
      sessionChunkOffset = 0;
    }
  } else {
    // If paused, we clean the temp folder so ffmpeg can write chunk_000.wav freshly
    if (fs.existsSync(TEMP_DIR)) {
      fs.rmSync(TEMP_DIR, { recursive: true, force: true });
    }
    fs.mkdirSync(TEMP_DIR, { recursive: true });
  }
  
  isRecording = true;
  isPaused = false;
  updateTray();
  
  // Start LLM Speaker Diarization interval (runs every 25 seconds)
  if (diarizationInterval) {
    clearInterval(diarizationInterval);
  }
  diarizationInterval = setInterval(() => {
    runSpeakerDiarizationLLM();
  }, 25000);
  
  // Send recording status update to renderer
  if (mainWindow) {
    mainWindow.webContents.send('audio:on-recording-status', { isRecording: true, isPaused: false });
  }
  
  // Spawn ffmpeg to record in 3-second stereo WAV chunks.
  // Left channel is system output monitor (inbound).
  // Right channel is local microphone source (outbound).
  // Use robust pan + amerge filter to support mono mic and stereo system mix cleanly.
  const ffmpegArgs = [
    '-y',
    '-f', 'pulse', '-i', sinkMonitor,
    '-f', 'pulse', '-i', source,
    '-filter_complex', '[0:a]pan=mono|c0=c0[left]; [1:a]pan=mono|c0=c0[right]; [left][right]amerge=inputs=2[a]',
    '-map', '[a]',
    '-f', 'segment',
    '-segment_time', '2',
    '-segment_format', 'wav',
    '-c:a', 'pcm_s16le',
    '-ar', '16000',
    '-ac', '2',
    path.join(TEMP_DIR, 'chunk_%03d.wav')
  ];
  
  recordingProcess = spawn('ffmpeg', ffmpegArgs);
  
  recordingProcess.on('error', (err) => {
    console.error('Ffmpeg recording error', err);
    stopRecordingHandler();
  });

  // Watch for new files in the temp directory
  chunkWatcher = setInterval(() => {
    if (!fs.existsSync(TEMP_DIR)) return;
    
    fs.readdir(TEMP_DIR, (err, files) => {
      if (err) return;
      
      const wavChunks = files
        .filter(f => f.startsWith('chunk_') && f.endsWith('.wav') && !f.includes('_mono') && !f.includes('_left') && !f.includes('_right') && !f.includes('_trans'))
        .sort();
      
      // If we have at least 2 chunks, the previous ones are complete.
      if (wavChunks.length >= 2) {
        for (let i = 0; i < wavChunks.length - 1; i++) {
          const chunkFile = wavChunks[i];
          const chunkPath = path.join(TEMP_DIR, chunkFile);
          const chunkIdx = parseInt(chunkFile.match(/\d+/)[0], 10);
          
          if (!processedChunks.has(chunkPath)) {
            processedChunks.add(chunkPath);
            enqueueWhisperChunk(chunkPath, chunkIdx);
          }
        }
      }
    });
  }, 1000);
}

function pauseRecordingHandler() {
  if (!isRecording || isPaused) return;
  
  isRecording = false;
  isPaused = true;
  updateTray();
  
  if (mainWindow) {
    mainWindow.webContents.send('audio:on-recording-status', { isRecording: false, isPaused: true });
  }
  
  if (chunkWatcher) {
    clearInterval(chunkWatcher);
    chunkWatcher = null;
  }
  
  if (diarizationInterval) {
    clearInterval(diarizationInterval);
    diarizationInterval = null;
  }
  
  // Clean close ffmpeg to finalize the currently writing chunk
  if (recordingProcess) {
    try {
      recordingProcess.stdin.write('q');
    } catch (e) {
      try {
        recordingProcess.kill('SIGINT');
      } catch (e2) {}
    }
    recordingProcess = null;
  }
  
  // Transcribe any remaining chunks in the temp directory
  setTimeout(() => {
    if (fs.existsSync(TEMP_DIR)) {
      const files = fs.readdirSync(TEMP_DIR);
      const wavChunks = files
        .filter(f => f.startsWith('chunk_') && f.endsWith('.wav') && !f.includes('_mono') && !f.includes('_left') && !f.includes('_right') && !f.includes('_trans'))
        .sort();
        
      let processedInThisRun = wavChunks.length;
      
      wavChunks.forEach((chunkFile) => {
        const chunkPath = path.join(TEMP_DIR, chunkFile);
        const chunkIdx = parseInt(chunkFile.match(/\d+/)[0], 10);
        
        if (!processedChunks.has(chunkPath)) {
          processedChunks.add(chunkPath);
          enqueueWhisperChunk(chunkPath, chunkIdx);
        }
      });
      
      // Update sessionChunkOffset for subsequent recording run segments
      sessionChunkOffset += processedInThisRun;
    }
  }, 1000);
}

function stopRecordingHandler() {
  if (!isRecording && !isPaused) return;
  
  isRecording = false;
  isPaused = false;
  updateTray();
  
  if (mainWindow) {
    mainWindow.webContents.send('audio:on-recording-status', { isRecording: false, isPaused: false });
  }
  
  // Clear file watcher
  if (chunkWatcher) {
    clearInterval(chunkWatcher);
    chunkWatcher = null;
  }

  if (diarizationInterval) {
    clearInterval(diarizationInterval);
    diarizationInterval = null;
  }
  
  // Kill ffmpeg process cleanly
  if (recordingProcess) {
    try {
      recordingProcess.stdin.write('q');
    } catch (e) {
      try {
        recordingProcess.kill('SIGINT');
      } catch (e2) {
        recordingProcess.kill('SIGKILL');
      }
    }
    recordingProcess = null;
  }
  
  destroyMiniWindow();
  
  // Transcribe any remaining chunks left in the temp directory
  setTimeout(() => {
    if (fs.existsSync(TEMP_DIR)) {
      const files = fs.readdirSync(TEMP_DIR);
      const wavChunks = files
        .filter(f => f.startsWith('chunk_') && f.endsWith('.wav') && !f.includes('_mono') && !f.includes('_left') && !f.includes('_right') && !f.includes('_trans'))
        .sort();
        
      let pendingTranscriptions = 0;
      
      wavChunks.forEach((chunkFile) => {
        const chunkPath = path.join(TEMP_DIR, chunkFile);
        const chunkIdx = parseInt(chunkFile.match(/\d+/)[0], 10);
        
        if (!processedChunks.has(chunkPath)) {
          processedChunks.add(chunkPath);
          pendingTranscriptions++;
          enqueueWhisperChunk(chunkPath, chunkIdx);
        }
      });
      
      const waitInterval = setInterval(() => {
        if (!isWhisperRunning && whisperQueue.length === 0) {
          clearInterval(waitInterval);
          finalizeAndSaveSession();
        }
      }, 500);
      
      setTimeout(() => clearInterval(waitInterval), 10000);
    } else {
      finalizeAndSaveSession();
    }
  }, 1000);
}

async function resumeCallTranscriptionHandler(sessionId) {
  try {
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
    
    // Calculate sessionChunkOffset based on the last segment timestamp
    if (activeSession.transcript && activeSession.transcript.length > 0) {
      const lastSeg = activeSession.transcript[activeSession.transcript.length - 1];
      sessionChunkOffset = Math.ceil(lastSeg.timestampMs / 2000);
    } else {
      sessionChunkOffset = 0;
    }
    
    isPaused = true;
    startRecordingHandler();
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
    const response = await queryOllama(prompt, model, systemPrompt);
    const mapping = parseLlmJsonResponse(response);
    console.log('Diarization LLM: Successfully mapped speakers:', mapping);
    applySpeakerLabelMapping(activeSession.transcript, mapping);
    notifySpeakerLabelsUpdated(mainWindow, mapping);
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

  queryOllama(prompt, model, systemPrompt)
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

async function finalizeAndSaveSession() {
  if (!activeSession) return;
  
  // Sort transcripts chronologically
  activeSession.transcript.sort((a, b) => a.timestampMs - b.timestampMs);
  
  // Run final speaker diarization
  await diarizeSpeakersPromise();
  
  const textContent = activeSession.transcript.map(t => `[${t.timestamp}] ${t.speaker}: ${t.text}`).join('\n');
  
  if (textContent.trim()) {
    triggerOllamaSummary(textContent, (summary, actionItems) => {
      activeSession.summary = summary;
      activeSession.actionItems = actionItems;
      
      triggerOllamaContextRename(textContent, (title, description, tags) => {
        const oldId = activeSession.id;
        const callsDir = getCallsDir();
        const oldFilePath = path.join(callsDir, `${oldId}.trail`);
        
        activeSession.title = title;
        activeSession.description = description;
        activeSession.tags = [];
        activeSession.suggestedTags = tags;
        
        // Auto-extract tasks for Timeline
        extractTasksFromActionItems(activeSession);
        
        // Save session first
        saveSessionToFile(activeSession);
        
        try {
          const slug = title.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
          const newId = `${slug}_${oldId}`;
          const newFilePath = path.join(callsDir, `${newId}.trail`);
          
          if (fs.existsSync(oldFilePath)) {
            activeSession.id = newId;
            activeSession.filePath = newFilePath;
            
            // Re-save session with updated ID
            saveSessionToFile(activeSession);
            
            // Delete old file
            fs.unlinkSync(oldFilePath);
          }
        } catch (renameErr) {
          console.error("Failed to rename call file based on context", renameErr);
        }
        
        // Broadcast that summary and rename are fully completed!
        if (mainWindow) {
          mainWindow.webContents.send('calls:session-summary-ready', activeSession);
        }
      });
    });
  } else {
    saveSessionToFile(activeSession);
    if (mainWindow) {
      mainWindow.webContents.send('calls:session-summary-ready', activeSession);
    }
  }
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
  if (mainWindow) {
    mainWindow.webContents.send('calls:list-updated');
  }
  
  // Compliance: Secure file shredding of temporary recordings
  if (fs.existsSync(TEMP_DIR)) {
    try {
      const files = fs.readdirSync(TEMP_DIR);
      files.forEach(f => secureShredFile(path.join(TEMP_DIR, f)));
    } catch (e) {}
    fs.rmSync(TEMP_DIR, { recursive: true, force: true });
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
  queryOllama(summaryPrompt, model)
    .then(summary => {
      // Request action items
      queryOllama(actionItemsPrompt, model)
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
  try {
    const response = await fetch('http://localhost:11434/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: model,
        prompt: prompt,
        system: systemPrompt,
        stream: false
      })
    });
    
    if (!response.ok) {
      throw new Error(`HTTP error ${response.status}`);
    }
    
    const data = await response.json();
    return data.response;
  } catch (error) {
    console.error('Failed to communicate with local Ollama service', error);
    throw error;
  }
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
  if (!mainWindow) return null;
  const { filePaths } = await dialog.showOpenDialog(mainWindow, {
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
      const choice = await dialog.showMessageBox(mainWindow, {
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
      filesToMove.forEach(file => {
        const src = path.join(oldStoragePath, file);
        const dest = path.join(newStoragePath, file);
        try {
          fs.renameSync(src, dest);
        } catch (e) {
          console.error(`Failed to move ${file}:`, e);
        }
      });
    }
  }
  
  settings = { ...settings, ...newSettings };
  saveSettings();
  
  // Start watcher on the new directory
  watchCallsDirectory();
  
  // Sync the database with the directory state
  await syncDatabaseWithFiles();
  
  // Reload call list on frontend
  if (mainWindow) {
    mainWindow.webContents.send('calls:list-updated');
  }
  return true;
});

ipcMain.handle('audio:start-recording', () => {
  startRecordingHandler();
  return activeSession ? activeSession.id : 'live';
});

ipcMain.handle('audio:stop-recording', () => {
  stopRecordingHandler();
  return true;
});

ipcMain.handle('audio:pause-recording', () => {
  pauseRecordingHandler();
  return true;
});

ipcMain.handle('audio:resume-recording', () => {
  isRecording = true;
  isPaused = false;
  updateTray();
  if (mainWindow) {
    mainWindow.webContents.send('audio:on-recording-status', { isRecording: true, isPaused: false });
  }
  startRecordingHandler();
  return true;
});

ipcMain.handle('calls:resume-transcription', async (event, sessionId) => {
  return await resumeCallTranscriptionHandler(sessionId);
});

ipcMain.handle('chat:query', async (event, query, activeTranscriptText) => {
  try {
    const model = settings.selectedLlm;
    const rows = await dbAll("SELECT title, date, summary, actionItems, mixNotes, enhancedNotes FROM sessions");
    
    let historyContext = "Here is the context of past calls:\n";
    rows.forEach(r => {
      historyContext += `- [${r.date}] Title: ${r.title}\n`;
      if (r.summary) historyContext += `  Summary: ${r.summary}\n`;
      if (r.actionItems) historyContext += `  Action Items: ${r.actionItems}\n`;
      historyContext += `\n`;
    });
    
    const systemPrompt = `You are an offline AI meeting assistant. You have access to the active call's transcript and the history of all past calls.
Use this context to answer the user's question accurately.
Be concise, helpful, and write your responses using clean Markdown format (headers, bold, list items, etc.) for great readability in the chat panel. Do not include loose asterisks.`;
    
    const userPrompt = `${historyContext}
Active Call Transcript:
${activeTranscriptText || 'No active transcript.'}

User Question:
${query}`;
    
    return await queryOllama(userPrompt, model, systemPrompt);
  } catch (err) {
    console.error("Chat query error", err);
    return "Could not query local AI model. Please verify Ollama is running.";
  }
});

ipcMain.handle('chat:mix-enhance', async (event, jots, transcriptText) => {
  try {
    const model = settings.selectedLlm;
    const systemPrompt = settings.notePromptTemplate || DEFAULT_PROMPTS.executive;
    
    const userPrompt = `User's Rough Jots:
${jots || 'No jots provided.'}

Raw Transcript:
${transcriptText || 'No transcript text available.'}

Enhanced Notes:`;
    
    return await queryOllama(userPrompt, model, systemPrompt);
  } catch (err) {
    console.error("Mix & Enhance error", err);
    return "Failed to run Mix & Enhance. Please ensure Ollama is running and the model is loaded.";
  }
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
      if (mainWindow) mainWindow.webContents.send('models:on-download-progress', output);
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

ipcMain.handle('calls:get-list', async () => {
  try {
    await migrateOldData();
    await syncDatabaseWithFiles();
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
        summary: row.summary,
        description: row.description || 'No description available.',
        tags: tags,
        suggestedTags: suggestedTags,
        folder_id: row.folder_id,
        mtimeMs: row.mtimeMs
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
    if (mainWindow) mainWindow.webContents.send('calls:list-updated');
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
    if (mainWindow) mainWindow.webContents.send('calls:list-updated');
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
    if (mainWindow) mainWindow.webContents.send('calls:list-updated');
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
    if (mainWindow) mainWindow.webContents.send('calls:list-updated');
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

    const { filePath } = await dialog.showSaveDialog(mainWindow, {
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
        mdContent += `## Jots (Manual Notes)\n${session.mixNotes || 'No manual jots.'}\n\n`;
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

// Folders Management (v0.2)
ipcMain.handle('folders:get', async () => {
  try {
    return await dbAll("SELECT * FROM folders");
  } catch (err) {
    console.error(err);
    return [];
  }
});

ipcMain.handle('folders:create', async (event, name) => {
  try {
    const id = 'folder_' + Date.now();
    await dbRun("INSERT INTO folders (id, name) VALUES (?, ?)", [id, name]);
    return { success: true, folder: { id, name } };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('folders:delete', async (event, id) => {
  try {
    await dbRun("DELETE FROM folders WHERE id = ?", [id]);
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle('calls:move-to-folder', async (event, sessionId, folderId) => {
  try {
    await dbRun("UPDATE sessions SET folder_id = ? WHERE id = ?", [folderId, sessionId]);
    if (mainWindow) mainWindow.webContents.send('calls:list-updated');
    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
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
  if (mainWindow) mainWindow.minimize();
});

ipcMain.on('app:relaunch', () => {
  if (mainWindow) {
    mainWindow.restore();
    mainWindow.focus();
  }
  destroyMiniWindow();
});

// App Lifecycles
app.whenReady().then(async () => {
  await initDatabase();
  createMainWindow();
  setupTray();
  watchCallsDirectory();
  
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
