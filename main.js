const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, screen, shell, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const { exec, execSync, spawn } = require('child_process');
const encryption = require('./encryption');

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

function saveSessionToFileSilently(session) {
  if (!session) return;
  const filePath = path.join(getCallsDir(), `${session.id}.trail`);
  try {
    if (session.encrypted && settings.encryptionPassword) {
      const encryptedData = encryption.encrypt(JSON.stringify(session), settings.encryptionPassword);
      fs.writeFileSync(filePath, encryptedData, 'utf8');
      decryptionKeys.set(session.id, settings.encryptionPassword);
    } else {
      fs.writeFileSync(filePath, JSON.stringify(session, null, 2), 'utf8');
    }
  } catch (err) {
    console.error('Failed to silently save session', err);
  }
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
    
    if (!fs.existsSync(whisperCli)) {
      console.error('Whisper.cpp binary not found at', whisperCli);
      resolve();
      return;
    }
    if (!fs.existsSync(whisperModel)) {
      console.error('Whisper model not found at', whisperModel);
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
        resolve();
        return;
      }
      
      const jsonPath = outputBase + '.json';
      if (!fs.existsSync(jsonPath)) {
        console.error('Whisper JSON file not found', jsonPath);
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

function resumeCallTranscriptionHandler(filePath) {
  try {
    const rawContent = fs.readFileSync(filePath, 'utf8');
    const isEncrypted = rawContent.includes('"salt"') && rawContent.includes('"iv"') && rawContent.includes('"encrypted"');
    
    let session = null;
    if (isEncrypted) {
      const id = path.basename(filePath, '.trail');
      const cachedKey = decryptionKeys.get(id);
      if (!cachedKey) {
        return { success: false, requirePassword: true };
      }
      const decrypted = encryption.decrypt(rawContent, cachedKey);
      session = JSON.parse(decrypted);
    } else {
      session = JSON.parse(rawContent);
    }
    
    // Set loaded session as active
    activeSession = session;
    
    // Calculate sessionChunkOffset based on the last segment timestamp
    if (activeSession.transcript && activeSession.transcript.length > 0) {
      const lastSeg = activeSession.transcript[activeSession.transcript.length - 1];
      sessionChunkOffset = Math.ceil(lastSeg.timestampMs / 2000);
    } else {
      sessionChunkOffset = 0;
    }
    
    // Transition to paused state first, then start transcribing
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
    const d = new Date(year, month, day);
    if (d < now && !numMatch[3]) {
      d.setFullYear(now.getFullYear() + 1);
    }
    return d.toISOString().split('T')[0];
  }

  return '';
}

function extractTasksFromActionItems(session) {
  let tasks = getTasksList();
  
  const lines = (session.actionItems || '').split('\n');
  lines.forEach((line, idx) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('-') || trimmed.startsWith('*')) {
      const text = trimmed.substring(1).trim();
      if (!text) return;
      
      let assignee = 'Unassigned';
      let cleanText = text;
      // Match "[Name] - Task description" or "[Name]: Task description"
      const match = text.match(/^\[(.*?)\]\s*[-:]\s*(.*)$/);
      if (match) {
        assignee = match[1].trim();
        cleanText = match[2].trim();
      }
      
      const exists = tasks.some(t => t.text === cleanText && t.sourceCallId === session.id);
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
        
        tasks.push({
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
  });
  
  saveTasksList(tasks);
}

function saveSessionToFile(session) {
  const filePath = path.join(getCallsDir(), `${session.id}.trail`);
  
  if (session.encrypted && settings.encryptionPassword) {
    // Encrypt the session
    const encryptedData = encryption.encrypt(JSON.stringify(session), settings.encryptionPassword);
    fs.writeFileSync(filePath, encryptedData, 'utf8');
    decryptionKeys.set(session.id, settings.encryptionPassword);
  } else {
    // Save plain text
    fs.writeFileSync(filePath, JSON.stringify(session, null, 2), 'utf8');
  }
  
  // Reload call list on frontend
  if (mainWindow) {
    mainWindow.webContents.send('calls:list-updated');
  }
  
  // Clean up temp recordings
  if (fs.existsSync(TEMP_DIR)) {
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
    const filesToMove = oldExists ? fs.readdirSync(oldStoragePath).filter(f => f.endsWith('.trail')) : [];
    
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
  
  // Reload call list on frontend
  if (mainWindow) {
    mainWindow.webContents.send('calls:list-updated');
  }
  return true;
});

ipcMain.handle('audio:start-recording', () => {
  startRecordingHandler();
  return true;
});

ipcMain.handle('audio:stop-recording', () => {
  stopRecordingHandler();
  return true;
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

ipcMain.handle('calls:get-list', () => {
  const callsDir = getCallsDir();
  if (!fs.existsSync(callsDir)) return [];
  
  const files = fs.readdirSync(callsDir).filter(f => f.endsWith('.trail'));
  return files.map((file) => {
    const filePath = path.join(callsDir, file);
    const id = file.replace('.trail', '');
    
    let mtimeMs = 0;
    try {
      const stat = fs.statSync(filePath);
      mtimeMs = stat.mtimeMs;
    } catch (statErr) {}
    
    // Check if encrypted
    try {
      const rawContent = fs.readFileSync(filePath, 'utf8');
      const isEncrypted = rawContent.includes('"salt"') && rawContent.includes('"iv"') && rawContent.includes('"encrypted"');
      
      if (isEncrypted) {
        const cachedKey = decryptionKeys.get(id);
        if (cachedKey) {
          try {
            const decrypted = encryption.decrypt(rawContent, cachedKey);
            const data = JSON.parse(decrypted);
            return {
              id: data.id,
              title: data.title || 'Meeting Session',
              date: data.date,
              encrypted: true,
              unlocked: true,
              filePath: filePath,
              summary: data.summary,
              description: data.description || 'No description available.',
              tags: data.tags || [],
              suggestedTags: data.suggestedTags || [],
              mtimeMs: mtimeMs
            };
          } catch (decErr) {}
        }
        // Return placeholder metadata
        return {
          id: id,
          title: 'Encrypted Session (Locked)',
          date: 'Unknown Date',
          encrypted: true,
          unlocked: false,
          filePath: filePath,
          mtimeMs: mtimeMs
        };
      } else {
        const data = JSON.parse(rawContent);
        return {
          id: data.id,
          title: data.title || 'Meeting Session',
          date: data.date,
          encrypted: false,
          filePath: filePath,
          summary: data.summary,
          description: data.description || 'No description available.',
          tags: data.tags || [],
          suggestedTags: data.suggestedTags || [],
          mtimeMs: mtimeMs
        };
      }
    } catch (e) {
      return { id, title: 'Corrupt Call file', date: '', encrypted: false, filePath, mtimeMs };
    }
  });
});

ipcMain.handle('calls:decrypt', (event, filePath, password) => {
  try {
    const rawContent = fs.readFileSync(filePath, 'utf8');
    const decrypted = encryption.decrypt(rawContent, password);
    const sessionData = JSON.parse(decrypted);
    
    // Cache the password securely in memory for this session ID
    decryptionKeys.set(sessionData.id, password);
    return { success: true, session: sessionData };
  } catch (error) {
    return { success: false, error: error.message };
  }
});

ipcMain.handle('calls:load', (event, filePath, password) => {
  try {
    const rawContent = fs.readFileSync(filePath, 'utf8');
    const isEncrypted = rawContent.includes('"salt"') && rawContent.includes('"iv"') && rawContent.includes('"encrypted"');
    
    if (isEncrypted) {
      if (!password) {
        // Check if we have the password cached in memory
        const id = path.basename(filePath, '.trail');
        const cachedKey = decryptionKeys.get(id);
        if (cachedKey) {
          const decrypted = encryption.decrypt(rawContent, cachedKey);
          return { success: true, session: JSON.parse(decrypted) };
        }
        return { success: false, requirePassword: true };
      }
      
      const decrypted = encryption.decrypt(rawContent, password);
      const sessionData = JSON.parse(decrypted);
      decryptionKeys.set(sessionData.id, password);
      return { success: true, session: sessionData };
    } else {
      return { success: true, session: JSON.parse(rawContent) };
    }
  } catch (error) {
    return { success: false, error: error.message };
  }
});

ipcMain.handle('calls:save', (event, callData, password) => {
  const filePath = path.join(getCallsDir(), `${callData.id}.trail`);
  
  const encryptionPassword = password || decryptionKeys.get(callData.id);
  
  if (encryptionPassword) {
    const encryptedData = encryption.encrypt(JSON.stringify(callData), encryptionPassword);
    fs.writeFileSync(filePath, encryptedData, 'utf8');
    decryptionKeys.set(callData.id, encryptionPassword);
  } else {
    fs.writeFileSync(filePath, JSON.stringify(callData, null, 2), 'utf8');
  }
  
  if (mainWindow) mainWindow.webContents.send('calls:list-updated');
  return true;
});

function extractKeywords(text) {
  if (!text) return [];
  const words = text.toLowerCase().split(/[^a-z0-9]+/i);
  const stopWords = new Set([
    'i', 'me', 'my', 'myself', 'we', 'our', 'ours', 'ourselves', 'you', 'your', 'yours', 
    'yourself', 'yourselves', 'he', 'him', 'his', 'himself', 'she', 'her', 'hers', 'herself', 
    'it', 'its', 'itself', 'they', 'them', 'their', 'theirs', 'themselves', 'what', 'which', 
    'who', 'whom', 'this', 'that', 'these', 'those', 'am', 'is', 'are', 'was', 'were', 'be', 
    'been', 'being', 'have', 'has', 'had', 'having', 'do', 'does', 'did', 'doing', 'a', 'an', 
    'the', 'and', 'but', 'if', 'or', 'because', 'as', 'until', 'while', 'of', 'at', 'by', 'for', 
    'with', 'about', 'against', 'between', 'into', 'through', 'during', 'before', 'after', 
    'above', 'below', 'to', 'from', 'up', 'down', 'in', 'out', 'on', 'off', 'over', 'under', 
    'again', 'further', 'then', 'once', 'here', 'there', 'when', 'where', 'why', 'how', 'all', 
    'any', 'both', 'each', 'few', 'more', 'most', 'other', 'some', 'such', 'no', 'nor', 'not', 
    'only', 'own', 'same', 'so', 'than', 'too', 'very', 's', 't', 'can', 'will', 'just', 'don', 
    'should', 'now', 'need', 'get', 'know', 'have', 'call', 'hour', 'meeting', 'transcription', 
    'assistant', 'trailmix', 'someone', 'ready', 'talking', 'points', 'point', 'suggest', 'suggestions'
  ]);
  return words.filter(w => w.length > 2 && !stopWords.has(w));
}

function getRelevantTranscriptSnippets(sessions, queryKeywords) {
  if (!queryKeywords || queryKeywords.length === 0) return '';
  
  let result = '';
  let matchesFound = 0;
  const maxMatches = 5;
  
  for (const s of sessions) {
    if (!s.transcript || !Array.isArray(s.transcript)) continue;
    
    const matchingIndices = [];
    s.transcript.forEach((seg, index) => {
      const textLower = seg.text.toLowerCase();
      const hasMatch = queryKeywords.some(keyword => textLower.includes(keyword));
      if (hasMatch) {
        matchingIndices.push(index);
      }
    });
    
    if (matchingIndices.length === 0) continue;
    
    const windows = [];
    matchingIndices.forEach((idx) => {
      const start = Math.max(0, idx - 1);
      const end = Math.min(s.transcript.length - 1, idx + 1);
      
      if (windows.length > 0 && start <= windows[windows.length - 1].end) {
        windows[windows.length - 1].end = Math.max(windows[windows.length - 1].end, end);
      } else {
        windows.push({ start, end });
      }
    });
    
    if (windows.length > 0) {
      result += `--- RELEVANT TRANSCRIPT SNIPPETS FROM "${s.title}" (${s.date}) ---\n`;
      windows.forEach((win) => {
        for (let i = win.start; i <= win.end; i++) {
          const seg = s.transcript[i];
          result += `[${seg.speaker}]: ${seg.text}\n`;
        }
        result += `...\n`;
      });
      result += `---------------------------------------------------\n\n`;
      matchesFound++;
      if (matchesFound >= maxMatches) break;
    }
  }
  
  return result;
}

ipcMain.handle('chat:query', async (event, query, transcriptText) => {
  const model = settings.selectedLlm;
  let systemPrompt = 'You are TrailMix Assistant. You help users understand details of their recorded transcripts. You run 100% offline. ' +
    'You have access to the current active call transcript, as well as the summaries and action items of all past calls to help the user answer general questions about their schedules, upcoming deadlines, action items, and past discussions.';
  
  let pastCallsContext = '';
  let readableSessions = [];
  try {
    const callsDir = getCallsDir();
    if (fs.existsSync(callsDir)) {
      const files = fs.readdirSync(callsDir).filter(f => f.endsWith('.trail'));
      
      files.forEach((file) => {
        const filePath = path.join(callsDir, file);
        const id = file.replace('.trail', '');
        try {
          const rawContent = fs.readFileSync(filePath, 'utf8');
          const isEncrypted = rawContent.includes('"salt"') && rawContent.includes('"iv"') && rawContent.includes('"encrypted"');
          let session = null;
          if (isEncrypted) {
            const cachedKey = decryptionKeys.get(id);
            if (cachedKey) {
              const decrypted = encryption.decrypt(rawContent, cachedKey);
              session = JSON.parse(decrypted);
            }
          } else {
            session = JSON.parse(rawContent);
          }
          
          if (session) {
            readableSessions.push(session);
          }
        } catch (e) {
          console.error('Failed to read call file for global context', e);
        }
      });
      
      if (readableSessions.length > 0) {
        pastCallsContext = "Here is the summary history of all previous call transcriptions:\n\n";
        readableSessions.forEach((s) => {
          pastCallsContext += `--- SESSION ---\n`;
          pastCallsContext += `Title: ${s.title}\n`;
          pastCallsContext += `Date: ${s.date}\n`;
          if (s.summary) pastCallsContext += `Summary: ${s.summary.trim()}\n`;
          if (s.actionItems) pastCallsContext += `Action Items:\n${s.actionItems.trim()}\n`;
          pastCallsContext += `---------------\n\n`;
        });
      }
    }
  } catch (err) {
    console.error('Error reading past calls for context', err);
  }

  let userPrompt = query;
  if (query === 'action') {
    userPrompt = `Based on the following meeting transcript, extract and list the actionable next steps and owners (if mentioned) in clear bullet points:\n\n${transcriptText}`;
  } else if (query === 'miss') {
    userPrompt = `What did I miss in the last few minutes? Summarize the latest key points and decisions in short bullet points:\n\n${transcriptText}`;
  } else {
    const keywords = extractKeywords(query);
    const relevantSnippets = getRelevantTranscriptSnippets(readableSessions, keywords);
    
    userPrompt = `You have access to past calls context (summaries and action items):\n\n${pastCallsContext}\n\n`;
    if (relevantSnippets) {
      userPrompt += `Here are the most relevant transcript segments found in call history matching search terms [${keywords.join(', ')}]:\n\n${relevantSnippets}\n\n`;
    }
    userPrompt += `And the current active session transcript:\n\n${transcriptText}\n\nAnswer the user query: ${query}`;
  }

  try {
    return await queryOllama(userPrompt, model, systemPrompt);
  } catch (error) {
    return `Error: Could not retrieve response from Ollama model "${model}". Please verify Ollama is active.`;
  }
});

// Pause / Resume and Timeline IPC handlers
ipcMain.handle('audio:pause-recording', () => {
  pauseRecordingHandler();
  return true;
});

ipcMain.handle('audio:resume-recording', () => {
  startRecordingHandler();
  return true;
});

ipcMain.handle('calls:resume-transcription', (event, filePath) => {
  return resumeCallTranscriptionHandler(filePath);
});

ipcMain.handle('tasks:get', () => {
  return getTasksList();
});

ipcMain.handle('tasks:toggle', (event, taskId) => {
  let tasks = getTasksList();
  const task = tasks.find(t => t.id === taskId);
  if (task) {
    task.completed = !task.completed;
    saveTasksList(tasks);
    return { success: true, task };
  }
  return { success: false, error: 'Task not found' };
});

ipcMain.handle('tasks:delete', (event, taskId) => {
  let tasks = getTasksList();
  const index = tasks.findIndex(t => t.id === taskId);
  if (index !== -1) {
    tasks.splice(index, 1);
    saveTasksList(tasks);
    return { success: true };
  }
  return { success: false, error: 'Task not found' };
});

ipcMain.handle('tasks:set-omitted', (event, taskId, omitted) => {
  let tasks = getTasksList();
  const task = tasks.find(t => t.id === taskId);
  if (task) {
    task.omitted = omitted;
    saveTasksList(tasks);
    return { success: true, task };
  }
  return { success: false, error: 'Task not found' };
});

ipcMain.handle('tasks:delete-multiple', (event, taskIds) => {
  let tasks = getTasksList();
  const initialLength = tasks.length;
  tasks = tasks.filter(t => !taskIds.includes(t.id));
  saveTasksList(tasks);
  return { success: tasks.length < initialLength };
});

ipcMain.handle('calls:open-file-location', (event, filePath) => {
  if (fs.existsSync(filePath)) {
    shell.showItemInFolder(filePath);
    return { success: true };
  }
  return { success: false, error: 'File not found' };
});

ipcMain.handle('calls:delete', (event, filePath) => {
  try {
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
      const id = path.basename(filePath, '.trail');
      let tasks = getTasksList();
      tasks = tasks.filter(t => t.sourceCallId !== id);
      saveTasksList(tasks);
      return { success: true };
    }
  } catch (e) {
    return { success: false, error: e.message };
  }
  return { success: false, error: 'File not found' };
});

ipcMain.handle('calls:delete-multiple', (event, filePaths) => {
  const errors = [];
  filePaths.forEach(fp => {
    try {
      if (fs.existsSync(fp)) {
        fs.unlinkSync(fp);
        const id = path.basename(fp, '.trail');
        let tasks = getTasksList();
        tasks = tasks.filter(t => t.sourceCallId !== id);
        saveTasksList(tasks);
      }
    } catch (e) {
      errors.push(`${fp}: ${e.message}`);
    }
  });
  return { success: errors.length === 0, errors };
});

ipcMain.handle('calls:merge', (event, filePaths) => {
  try {
    const sessions = [];
    filePaths.forEach(fp => {
      if (fs.existsSync(fp)) {
        const raw = fs.readFileSync(fp, 'utf8');
        const isEncrypted = raw.includes('"salt"') && raw.includes('"iv"');
        if (isEncrypted) {
          const id = path.basename(fp, '.trail');
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            const decrypted = encryption.decrypt(raw, cachedKey);
            sessions.push(JSON.parse(decrypted));
          } else {
            throw new Error(`Cannot merge encrypted session ${id} without cached password.`);
          }
        } else {
          sessions.push(JSON.parse(raw));
        }
      }
    });

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
      
      sess.transcript.forEach((seg) => {
        mergedTranscript.push({
          ...seg,
          id: `merged_${sess.id}_${seg.id}`,
          timestampMs: startOffsetMs + seg.timestampMs,
          timestamp: formatTimestamp(startOffsetMs + seg.timestampMs)
        });
      });
      
      if (sess.transcript.length > 0) {
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
      encrypted: false
    };

    saveSessionToFile(newSession);
    return { success: true, session: newSession };
  } catch (e) {
    return { success: false, error: e.message };
  }
});

ipcMain.handle('calls:export', async (event, filePaths) => {
  try {
    let combinedMarkdown = '';
    
    filePaths.forEach(fp => {
      if (fs.existsSync(fp)) {
        const raw = fs.readFileSync(fp, 'utf8');
        const isEncrypted = raw.includes('"salt"') && raw.includes('"iv"');
        let session = null;
        
        if (isEncrypted) {
          const id = path.basename(fp, '.trail');
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            const decrypted = encryption.decrypt(raw, cachedKey);
            session = JSON.parse(decrypted);
          }
        } else {
          session = JSON.parse(raw);
        }
        
        if (session) {
          combinedMarkdown += `# ${session.title}\n`;
          combinedMarkdown += `Date: ${session.date}\n\n`;
          combinedMarkdown += `## Transcript\n`;
          session.transcript.forEach((t) => {
            combinedMarkdown += `[${t.timestamp}] ${t.speaker}: ${t.text}\n`;
          });
          combinedMarkdown += `\n## Highlights Summary\n${session.summary || 'No summary'}\n\n`;
          combinedMarkdown += `## Action Items\n${session.actionItems || 'No action items'}\n`;
          combinedMarkdown += `\n---\n\n`;
        }
      }
    });

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

ipcMain.handle('calls:find-related', (event, filePath) => {
  try {
    if (!fs.existsSync(filePath)) return [];
    
    const raw = fs.readFileSync(filePath, 'utf8');
    const isEncrypted = raw.includes('"salt"') && raw.includes('"iv"');
    let targetSession = null;
    
    if (isEncrypted) {
      const id = path.basename(filePath, '.trail');
      const cachedKey = decryptionKeys.get(id);
      if (cachedKey) {
        targetSession = JSON.parse(encryption.decrypt(raw, cachedKey));
      }
    } else {
      targetSession = JSON.parse(raw);
    }
    
    if (!targetSession) return [];

    const targetSpeakers = new Set(targetSession.transcript.map(t => t.speaker.toLowerCase()).filter(s => s !== 'you' && !s.startsWith('speaker')));
    const targetWords = new Set(targetSession.title.toLowerCase().split(/\s+/).filter(w => w.length > 4));

    const callsDir = getCallsDir();
    const files = fs.readdirSync(callsDir).filter(f => f.endsWith('.trail'));
    const related = [];

    files.forEach(file => {
      const fp = path.join(callsDir, file);
      if (fp === filePath) return;

      try {
        const rawContent = fs.readFileSync(fp, 'utf8');
        const isEncryptedContent = rawContent.includes('"salt"') && rawContent.includes('"iv"');
        let session = null;

        if (isEncryptedContent) {
          const id = file.replace('.trail', '');
          const cachedKey = decryptionKeys.get(id);
          if (cachedKey) {
            session = JSON.parse(encryption.decrypt(rawContent, cachedKey));
          }
        } else {
          session = JSON.parse(rawContent);
        }

        if (session) {
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
              filePath: fp
            });
          }
        }
      } catch (err) {}
    });

    related.sort((a, b) => b.score - a.score);
    return related;
  } catch (e) {
    console.error(e);
    return [];
  }
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
app.whenReady().then(() => {
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
