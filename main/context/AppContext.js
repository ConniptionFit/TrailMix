/**
 * Shared application context — single DI surface for all modules.
 */
const { ModuleRegistry } = require('../ModuleRegistry');

function createAppContext(runtime) {
  const registry = new ModuleRegistry();

  const ctx = {
    runtime,
    registry,
    get settings() { return runtime.settings; },
    set settings(v) { runtime.settings = v; },
    get ipcMain() { return runtime.ipcMain; },
    get app() { return runtime.app; },
    services: {
      audio: runtime.audioCaptureService,
      transcription: runtime.transcriptionService,
      llm: runtime.llmService,
      enhance: runtime.enhanceNotesService,
      processing: runtime.sessionProcessingService,
      updates: runtime.updateService
    },
    paths: {
      project: runtime.PROJECT_DIR,
      data: runtime.DATA_DIR,
      temp: runtime.TEMP_DIR,
      settings: runtime.SETTINGS_FILE,
      whisper: runtime.WHISPER_DIR
    }
  };

  return ctx;
}

module.exports = { createAppContext };
