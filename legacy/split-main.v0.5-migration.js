#!/usr/bin/env node
/**
 * One-time splitter: extracts IPC + lifecycle from main.js into modular files.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const mainPath = path.join(ROOT, 'main.js');
const source = fs.readFileSync(mainPath, 'utf8');

const ipcMarker = '// ----------------------------------------------------\n// IPC Handler Bindings';
const lifecycleMarker = '// App Lifecycles';

const ipcIdx = source.indexOf(ipcMarker);
const lifecycleIdx = source.indexOf(lifecycleMarker);

if (ipcIdx === -1 || lifecycleIdx === -1) {
  console.error('Markers not found in main.js');
  process.exit(1);
}

let core = source.slice(0, ipcIdx).trimEnd();
const ipcBlock = source.slice(ipcIdx, lifecycleIdx).trimEnd();
const lifecycleBlock = source.slice(lifecycleIdx).trimEnd();

// Fix PROJECT_DIR for main/core location
core = core.replace(
  "const PROJECT_DIR = __dirname;",
  "const PROJECT_DIR = path.join(__dirname, '..', '..');"
);

// Remove ipcMain from core imports if only used in IPC - keep it for now
core += '\n\nmodule.exports = {\n';
core += '  app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, screen, shell, dialog,\n';
core += '  path, fs, os, execSync, spawn, encryption, db, dbRun, dbAll, dbGet,\n';
core += '  PROJECT_DIR, DATA_DIR, TEMP_DIR, SETTINGS_FILE, WHISPER_DIR,\n';
core += '  audioCaptureService, transcriptionService, llmService, enhanceNotesService,\n';
core += '  sessionProcessingService, updateService, windowManager, tray,\n';
core += '  isRecording, isPaused, flushingFinalChunks, sessionChunkOffset,\n';
core += '  diarizationInterval, activeSession, activeRecordingSessionId,\n';
core += '  captureMicSourceOverride, decryptionKeys, settings, DEFAULT_PROMPTS,\n';
core += '  getSession, setSession, deleteSession, createNewSession, applyAutoTitle,\n';
core += '  getHubWindow, broadcastToSession, broadcastRecordingStatus,\n';
core += '  queryAudioDevices, startRecordingHandler, pauseRecordingHandler,\n';
core += '  stopRecordingHandler, resumeCallTranscriptionHandler,\n';
core += '  loadSessionPayloadFromDb, saveSessionPayloadToDb, saveSessionToDbPromise,\n';
core += '  saveSessionToFileSilently, saveSessionToFile, resolveSessionRecord,\n';
core += '  syncDatabaseWithFiles, getCallsDir, watchCallsDirectory,\n';
core += '  createHubWindow, openMeetingWindow, setupTray, updateTray,\n';
core += '  getHardwareSpecs, initDatabase, migrateOldData, syncFilesystemFoldersToDb,\n';
core += '  saveSettings, broadcastProcessingProgress, runSessionEnrichment,\n';
core += '  extractTasksFromActionItems, CHAT_RECIPES, buildRecipePrompt,\n';
core += '  llmService, sessionProcessingService, updateService,\n';
core += '  cleanupStaleLoopbackModules, finalizeAndSaveSession,\n';
core += '  normalizeFolderIdForSave, getTasksListFromDb, saveTaskToDb,\n';
core += '  upsertSessionFromTrailFile, ensureFolderRecord, parseDeadlineDate\n';
core += '};\n';

// Extract ipc handlers body (strip marker comments)
const ipcBody = ipcBlock
  .replace(ipcMarker, '')
  .replace('// ----------------------------------------------------', '')
  .trim();

const ipcOut = `/**
 * IPC handler registration — auto-split from legacy main.js.
 * Each domain module can be further extracted incrementally.
 */
function registerLegacyIpcHandlers(ctx) {
  const {
    ipcMain, app, dialog, shell, path, fs, os,
    settings, DEFAULT_PROMPTS, isRecording, isPaused, activeRecordingSessionId,
    activeSession, windowManager, decryptionKeys, updateService,
    getHubWindow, broadcastToSession, broadcastRecordingStatus,
    queryAudioDevices, startRecordingHandler, pauseRecordingHandler,
    stopRecordingHandler, resumeCallTranscriptionHandler,
    getSession, setSession, createNewSession, deleteSession,
    loadSessionPayloadFromDb, saveSessionToDbPromise, saveSessionToFileSilently,
    saveSessionToFile, resolveSessionRecord, syncDatabaseWithFiles,
    getCallsDir, watchCallsDirectory, openMeetingWindow, saveSettings,
    getHardwareSpecs, runSessionEnrichment, extractTasksFromActionItems,
    CHAT_RECIPES, buildRecipePrompt, llmService, sessionProcessingService,
    DATA_DIR, PROJECT_DIR, encryption, dbRun, dbAll, dbGet,
    normalizeFolderIdForSave, getTasksListFromDb, saveTaskToDb,
    upsertSessionFromTrailFile, ensureFolderRecord, parseDeadlineDate,
    appEventBus, ROOT_FOLDER_ID, UNCATEGORIZED_FOLDER_ID,
    scanStorageLayout, ensureFolderDir, moveSessionFile, moveStorageContents,
    resolveSessionFilePath, relativePathFromFolderId, secureShredFile,
    formatTaskRow, formatTimestamp, documentFromSession,
    splitTranscriptIntoBlocks, PROCESSING_STATUS
  } = ctx;

${ipcBody}
}

module.exports = { registerLegacyIpcHandlers };
`;

const lifecycleOut = `/**
 * Application lifecycle hooks.
 */
function registerLifecycle(ctx) {
  const {
    app, BrowserWindow, createHubWindow, initDatabase, migrateOldData,
    syncFilesystemFoldersToDb, sessionProcessingService, updateService,
    settings, setupTray, watchCallsDirectory, broadcastProcessingProgress,
    cleanupStaleLoopbackModules, resolveWhisperCli, WHISPER_DIR, fs,
    transcriptionService, audioCaptureService, getHubWindow
  } = ctx;

${lifecycleBlock.replace('function cleanupStaleLoopbackModules', '// cleanupStaleLoopbackModules imported from core')}
}

module.exports = { registerLifecycle };
`;

fs.mkdirSync(path.join(ROOT, 'main', 'core'), { recursive: true });
fs.mkdirSync(path.join(ROOT, 'main', 'ipc'), { recursive: true });

fs.writeFileSync(path.join(ROOT, 'main', 'core', 'AppCore.js'), core);
fs.writeFileSync(path.join(ROOT, 'main', 'ipc', 'legacy-handlers.js'), ipcOut);
fs.writeFileSync(path.join(ROOT, 'main', 'lifecycle.js'), lifecycleOut);

console.log('Split complete: main/core/AppCore.js, main/ipc/legacy-handlers.js, main/lifecycle.js');
