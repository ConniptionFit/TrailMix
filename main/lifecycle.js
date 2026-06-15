/**
 * Application lifecycle — boot, activate, quit.
 */
function registerLifecycle(rt, ctx) {
  const {
    app,
    BrowserWindow,
    createHubWindow,
    initDatabase,
    migrateOldData,
    syncFilesystemFoldersToDb,
    sessionProcessingService,
    updateService,
    setupTray,
    watchCallsDirectory,
    broadcastProcessingProgress,
    cleanupStaleLoopbackModules,
    resolveWhisperCli,
    WHISPER_DIR,
    fs,
    transcriptionService,
    audioCaptureService,
    getHubWindow
  } = rt;

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
      getAutoUpdateEnabled: () => rt.settings.autoUpdateEnabled !== false
    });
    updateService.getStatus(app.getVersion());
    if (rt.settings.autoUpdateEnabled !== false) {
      void updateService.checkForUpdates({ manual: false });
    }
    setupTray();
    watchCallsDirectory();
    broadcastProcessingProgress();

    if (ctx?.registry) {
      await ctx.registry.runOnReady(rt);
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createHubWindow();
    });
  });

  app.on('before-quit', () => {
    if (ctx?.registry) {
      ctx.registry.runOnBeforeQuit(rt);
    }
    transcriptionService.shutdown();
    audioCaptureService.stopFfmpeg().catch(() => {});
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}

module.exports = { registerLifecycle };
