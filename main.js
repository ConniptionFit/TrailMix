const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, screen, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { exec, execSync, spawn } = require('child_process');
const encryption = require('./encryption');
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
  customAgents: [
    { name: 'Summary Agent', prompt: 'Summarize the meeting highlights and key decisions.' },
    { name: 'Action Items Agent', prompt: 'Extract and list actionable next steps with owners.' }
  ]
};

// Load settings
if (fs.existsSync(SETTINGS_FILE)) {
  try {
    settings = { ...settings, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) };
  } catch (e) {
    console.error('Error loading settings, using defaults', e);
  }
}

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
    
    let sum = 0;
    let count = 0;
    // Read 16-bit PCM samples
    for (let i = 44; i < buffer.length; i += 4) {
      if (i + 1 < buffer.length) {
        const sampleL = buffer.readInt16LE(i);
        sum += sampleL * sampleL;
        count++;
      }
    }
    if (count === 0) return false;
    const rms = Math.sqrt(sum / count);
    console.log(`VAD check: RMS energy = ${Math.round(rms)}`);
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
// Stereo Channel Energy Diarization
// ----------------------------------------------------

/**
 * Calculates Left and Right channel energy for a specified timestamp segment.
 * @param {string} wavPath Path to the 16kHz stereo WAV file
 * @param {number} startMs Segment start time in ms
 * @param {number} endMs Segment end time in ms
 * @returns {{rmsL: number, rmsR: number}} Left and Right RMS values
 */
function calculateChannelEnergy(wavPath, startMs, endMs) {
  try {
    const fd = fs.openSync(wavPath, 'r');
    const stats = fs.fstatSync(fd);
    
    // 16kHz stereo 16-bit PCM: 4 bytes per sample (2 channels * 2 bytes)
    // 1 second of audio = 16000 * 4 = 64000 bytes
    const bytesPerSecond = 64000;
    const headerOffset = 44; // Standard WAV PCM header size
    
    const startByte = headerOffset + Math.floor((startMs / 1000) * bytesPerSecond);
    const endByte = Math.min(stats.size, headerOffset + Math.floor((endMs / 1000) * bytesPerSecond));
    
    const length = endByte - startByte;
    if (length <= 0) return { rmsL: 0, rmsR: 0 };
    
    const buffer = Buffer.alloc(length);
    fs.readSync(fd, buffer, 0, length, startByte);
    fs.closeSync(fd);
    
    let sumL = 0;
    let sumR = 0;
    let count = 0;
    
    for (let i = 0; i < buffer.length; i += 4) {
      if (i + 3 < buffer.length) {
        const valL = buffer.readInt16LE(i);
        const valR = buffer.readInt16LE(i + 2);
        sumL += valL * valL;
        sumR += valR * valR;
        count++;
      }
    }
    
    if (count === 0) return { rmsL: 0, rmsR: 0 };
    
    return {
      rmsL: Math.sqrt(sumL / count),
      rmsR: Math.sqrt(sumR / count)
    };
  } catch (error) {
    console.error('Error analyzing channel energy', error);
    return { rmsL: 0, rmsR: 0 };
  }
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
  return new Promise((resolve) => {
    const whisperCli = path.join(WHISPER_DIR, 'build', 'bin', 'whisper-cli');
    const whisperModel = path.join(WHISPER_DIR, 'models', settings.selectedModel);
    const monoWavPath = chunkWavPath.replace('.wav', '_mono.wav');
    
    // Check Voice Activity Detection (VAD) noise gate
    const hasVoice = checkVoiceActivity(chunkWavPath);
    if (!hasVoice) {
      console.log(`VAD noise gate: Chunk ${chunkIndex} is silent (RMS < 100). Dropping chunk to save CPU/VRAM.`);
      // Shred file immediately and return
      secureShredFile(chunkWavPath);
      resolve();
      return;
    }

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

    // Downmix to 16kHz mono WAV for Whisper
    try {
      let downmixFilter = '-ac 1';
      if (settings.enableNoiseCancellation !== false) {
        downmixFilter = '-filter_complex "aeval=\'val(0)+0.75*val(1)\':c=mono"';
      }
      execSync(`ffmpeg -y -i "${chunkWavPath}" ${downmixFilter} "${monoWavPath}"`);
    } catch (err) {
      console.error('Ffmpeg downmix failed', err);
      secureShredFile(chunkWavPath);
      resolve();
      return;
    }

    // Run Whisper CLI and produce JSON output
    const outputBase = chunkWavPath.replace('.wav', '_trans');
    const threads = Math.min(8, Math.max(4, require('os').cpus().length - 2));
    const whisperCmd = `"${whisperCli}" -m "${whisperModel}" -f "${monoWavPath}" -t ${threads} -oj -of "${outputBase}"`;
    
    exec(whisperCmd, (error) => {
      if (error) {
        console.error('Whisper execution failed', error);
        secureShredFile(chunkWavPath);
        secureShredFile(monoWavPath);
        resolve();
        return;
      }
      
      const jsonPath = outputBase + '.json';
      if (!fs.existsSync(jsonPath)) {
        console.error('Whisper JSON file not found', jsonPath);
        secureShredFile(chunkWavPath);
        secureShredFile(monoWavPath);
        resolve();
        return;
      }
      
      try {
        const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
        const segments = data.transcription || [];
        
        segments.forEach((seg) => {
          const text = seg.text.trim();
          if (!text) return;
          
          if (/^\[blank_audio\]$/i.test(text) || text.toUpperCase().includes('BLANK_AUDIO')) {
            return;
          }
          
          const fromMs = seg.offsets.from;
          const toMs = seg.offsets.to;
          
          const { rmsL, rmsR } = calculateChannelEnergy(chunkWavPath, fromMs, toMs);
          const rmsMic = rmsL;
          const rmsSystem = rmsR;
          
          let effectiveRmsMic = rmsMic;
          if (settings.enableNoiseCancellation !== false) {
            effectiveRmsMic = Math.max(0, rmsMic - rmsSystem * 0.25);
          }
          
          console.log(`Diarization debug - Chunk: ${chunkIndex}, Segment: [${fromMs}ms - ${toMs}ms], RMS L (Mic): ${Math.round(rmsMic)}, RMS R (System): ${Math.round(rmsSystem)}, Eff Mic: ${Math.round(effectiveRmsMic)}`);
          
          let speaker = 'Speaker 1';
          if (effectiveRmsMic > rmsSystem && effectiveRmsMic > 150) {
            speaker = 'You';
          } else if (rmsSystem > effectiveRmsMic && rmsSystem > 150) {
            speaker = 'Speaker 1';
          } else {
            speaker = effectiveRmsMic > rmsSystem ? 'You' : 'Speaker 1';
          }
          
          const sessionOffsetMs = (sessionChunkOffset + chunkIndex) * 2000 + fromMs;
          const timestampStr = formatTimestamp(sessionOffsetMs);
          
          const transcriptSegment = {
            id: `${activeSession.id}_${sessionChunkOffset + chunkIndex}_${fromMs}`,
            timestampMs: sessionOffsetMs,
            timestamp: timestampStr,
            speaker: speaker,
            text: text,
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
        
        saveSessionToFileSilently(activeSession);
      } catch (e) {
        console.error('Error parsing whisper JSON output', e);
      }
      
      // Compliance: Secure file shredding of temporary raw WAV and mono WAV files immediately
      secureShredFile(chunkWavPath);
      secureShredFile(monoWavPath);
      try {
        if (fs.existsSync(jsonPath)) fs.unlinkSync(jsonPath);
      } catch (e) {}
      
      resolve();
    });
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
        .filter(f => f.startsWith('chunk_') && f.endsWith('.wav') && !f.includes('_mono') && !f.includes('_trans'))
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
        .filter(f => f.startsWith('chunk_') && f.endsWith('.wav') && !f.includes('_mono') && !f.includes('_trans'))
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
        .filter(f => f.startsWith('chunk_') && f.endsWith('.wav') && !f.includes('_mono') && !f.includes('_trans'))
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
        const transFiles = fs.readdirSync(TEMP_DIR).filter(f => f.endsWith('_trans.json'));
        if (transFiles.length >= wavChunks.length || pendingTranscriptions === 0) {
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

function runSpeakerDiarizationLLM() {
  if (!activeSession || !activeSession.transcript || activeSession.transcript.length === 0) return;
  
  const model = settings.selectedLlm;
  const systemPrompt = `You are a local speaker attribution assistant. Your job is to analyze the conversation turns of a transcript and differentiate/label the speakers.
The user is "You" (their name is "${settings.userName || 'You'}"). Always leave the speaker "You" as "You" (do not change it).
Other turns are currently labeled as "Speaker 1". All inbound speakers are mixed on the inbound audio channel. Differentiate them based on conversational context, flow, and text.
Assign them identifiers like "Speaker 1", "Speaker 2" if you cannot deduce their names. Limit the number of unique inbound speakers identified to a maximum of 5.
If you can deduce their actual name or role (e.g. "Sarah", "Netflix Support Agent", "BestBuy Support") from what they say in the transcript, use that descriptive name/label instead.
Output your results ONLY as a valid JSON object mapping turnIndex strings (e.g. "1", "2") to their corrected speaker labels. Do not include any reasoning, markdown formatting (like \`\`\`json), or conversational text. Output ONLY the raw JSON object.`;

  const turns = activeSession.transcript.map((t, index) => {
    return { turnIndex: index + 1, speaker: t.speaker, text: t.text };
  });
  
  const prompt = `Here is the current transcript segments list:\n\n${JSON.stringify(turns, null, 2)}\n\nAnalyze the segments and return the JSON map of speaker labels.`;
  
  console.log("Diarization LLM: Analyzing transcript for speaker names and turns...");
  queryOllama(prompt, model, systemPrompt)
    .then(response => {
      try {
        let cleanText = response.trim();
        const firstBrace = cleanText.indexOf('{');
        const lastBrace = cleanText.lastIndexOf('}');
        if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
          cleanText = cleanText.substring(firstBrace, lastBrace + 1);
        } else {
          if (cleanText.includes('```')) {
            const match = cleanText.match(/```(?:json)?\s*([\s\S]+?)\s*```/);
            if (match) cleanText = match[1];
          }
        }
        
        const mapping = JSON.parse(cleanText);
        console.log("Diarization LLM: Successfully mapped speakers:", mapping);
        
        activeSession.transcript.forEach((seg, index) => {
          let label = mapping[seg.id];
          if (!label) {
            label = mapping[(index + 1).toString()] || mapping[index + 1];
          }
          if (label) {
            if (label !== 'You' && seg.speaker === 'You') {
              // ignore mapping if it tries to overwrite You
            } else {
              seg.speaker = label;
            }
          }
        });
        
        if (mainWindow) {
          mainWindow.webContents.send('audio:on-speaker-labels-updated', mapping);
        }
      } catch (e) {
        console.error("Diarization LLM: Failed to parse JSON mapping. Raw response:", response, e);
      }
    })
    .catch(err => {
      console.error("Diarization LLM: Ollama query failed", err);
    });
}

async function diarizeSpeakersPromise() {
  if (!activeSession || !activeSession.transcript || activeSession.transcript.length === 0) return;
  
  const model = settings.selectedLlm;
  const systemPrompt = `You are a local speaker attribution assistant. Your job is to analyze the conversation turns of a transcript and differentiate/label the speakers.
The user is "You" (their name is "${settings.userName || 'You'}"). Always leave the speaker "You" as "You" (do not change it).
Other turns are currently labeled as "Speaker 1". All inbound speakers are mixed on the inbound audio channel. Differentiate them based on conversational context, flow, and text.
Assign them identifiers like "Speaker 1", "Speaker 2" if you cannot deduce their names. Limit the number of unique inbound speakers identified to a maximum of 5.
If you can deduce their actual name or role (e.g. "Sarah", "Netflix Support Agent", "BestBuy Support") from what they say in the transcript, use that descriptive name/label instead.
Output your results ONLY as a valid JSON object mapping turnIndex strings (e.g. "1", "2") to their corrected speaker labels. Do not include any reasoning, markdown formatting (like \`\`\`json), or conversational text. Output ONLY the raw JSON object.`;

  const turns = activeSession.transcript.map((t, index) => {
    return { turnIndex: index + 1, speaker: t.speaker, text: t.text };
  });
  
  const prompt = `Here is the current transcript segments list:\n\n${JSON.stringify(turns, null, 2)}\n\nAnalyze the segments and return the JSON map of speaker labels.`;
  
  try {
    const response = await queryOllama(prompt, model, systemPrompt);
    let cleanText = response.trim();
    const firstBrace = cleanText.indexOf('{');
    const lastBrace = cleanText.lastIndexOf('}');
    if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
      cleanText = cleanText.substring(firstBrace, lastBrace + 1);
    } else {
      if (cleanText.includes('```')) {
        const match = cleanText.match(/```(?:json)?\s*([\s\S]+?)\s*```/);
        if (match) cleanText = match[1];
      }
    }
    const mapping = JSON.parse(cleanText);
    
    activeSession.transcript.forEach((seg, index) => {
      let label = mapping[seg.id];
      if (!label) {
        label = mapping[(index + 1).toString()] || mapping[index + 1];
      }
      if (label) {
        if (label !== 'You' && seg.speaker === 'You') {
          // ignore
        } else {
          seg.speaker = label;
        }
      }
    });
    
    if (mainWindow) {
      mainWindow.webContents.send('audio:on-speaker-labels-updated', mapping);
    }
  } catch (e) {
    console.error("Diarization final promise failed", e);
  }
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
        let cleanText = response.trim();
        const firstBrace = cleanText.indexOf('{');
        const lastBrace = cleanText.lastIndexOf('}');
        if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
          cleanText = cleanText.substring(firstBrace, lastBrace + 1);
        }
        const meta = JSON.parse(cleanText);
        
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
const TASKS_FILE = path.join(DATA_DIR, 'tasks.json');

function getTasksList() {
  if (!fs.existsSync(TASKS_FILE)) return [];
  try {
    return JSON.parse(fs.readFileSync(TASKS_FILE, 'utf8'));
  } catch (e) {
    return [];
  }
}

function saveTasksList(tasks) {
  fs.writeFileSync(TASKS_FILE, JSON.stringify(tasks, null, 2), 'utf8');
}

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
  
  const summaryPrompt = `Based on the following meeting transcript, write a beautifully formatted markdown summary of the key discussion highlights. Feel free to use headers (##), bold text (**text**), bullet points, and moderate relevant emojis (like 📌, 💡, 🎯, ✅) for readability, but avoid loose asterisks. Do not include any conversational filler, follow-up questions, or requests for elaboration at the end:\n\n${transcriptText}`;
  const actionItemsPrompt = `Based on the following meeting transcript, extract and list ONLY the actionable next steps and tasks that require someone to take action. Do not include informational remarks or general points. Format each item starting with an assignee in brackets followed by a dash and the task, like "[Name] - Task description" (or "[You] - Task description" if assigned to the local user). If no owner/assignee is specified, label it "[Unassigned] - Task description":\n\n${transcriptText}`;
  
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
    const systemPrompt = `You are a Principal Technical Writer. Your task is to perform top-down attention filtering to enhance rough jots using raw transcript context.
You must use the user's manual "Jots" as anchor metrics. Delineate and expand upon the user's jots by extracting relevant technical details, decisions, dates, and quotes from the "Raw Transcript" that match those anchors.
CRITICAL RULE: Entirely ignore transcript tangents, side-talk, and details that the user did not jot down. Do NOT add new topics not mentioned in the user's jots.
Format the output as beautifully structured Markdown (using H2, H3, bold text, and checklists) for Notion/Apple Notes style canvas. Avoid loose asterisks.`;
    
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

// Tasks Management (v0.2)
async function getTasksListFromDb() {
  try {
    const rows = await dbAll("SELECT * FROM tasks");
    return rows.map(r => ({
      ...r,
      completed: r.completed === 1,
      omitted: r.omitted === 1
    }));
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
    const task = await dbGet("SELECT * FROM tasks WHERE id = ?", [taskId]);
    if (task) {
      const newCompleted = task.completed === 1 ? 0 : 1;
      await dbRun("UPDATE tasks SET completed = ? WHERE id = ?", [newCompleted, taskId]);
      task.completed = newCompleted === 1;
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
    const task = await dbGet("SELECT * FROM tasks WHERE id = ?", [taskId]);
    if (task) {
      task.completed = task.completed === 1;
      task.omitted = task.omitted === 1;
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
