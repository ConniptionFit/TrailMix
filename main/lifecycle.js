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
    // migrateOldData already syncs filesystem folders + DB with trail files
    await migrateOldData();
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

    await ctx.registry.runOnReady(ctx);

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createHubWindow();
    });
  });

  app.on('before-quit', () => {
    ctx.registry.runOnBeforeQuit(ctx);
    transcriptionService.shutdown();
    audioCaptureService.stopFfmpeg().catch(() => {});
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}

module.exports = { registerLifecycle };
