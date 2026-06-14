const { autoUpdater } = require('electron-updater');

class UpdateService {
  constructor() {
    this.status = {
      state: 'idle',
      currentVersion: null,
      availableVersion: null,
      progress: 0,
      error: null
    };
    this.hubWindow = null;
    this.autoUpdateEnabled = true;
    this.configured = false;
  }

  configure({ getHubWindow, getAutoUpdateEnabled }) {
    if (this.configured) return;
    this.configured = true;
    this.getHubWindow = getHubWindow;
    this.getAutoUpdateEnabled = getAutoUpdateEnabled;

    autoUpdater.autoDownload = false;
    autoUpdater.autoInstallOnAppQuit = false;

    autoUpdater.on('checking-for-update', () => {
      this.setStatus({ state: 'checking' });
    });

    autoUpdater.on('update-available', (info) => {
      this.setStatus({
        state: 'available',
        availableVersion: info.version
      });
      if (this.getAutoUpdateEnabled()) {
        void autoUpdater.downloadUpdate();
        this.setStatus({ state: 'downloading', progress: 0 });
      }
    });

    autoUpdater.on('update-not-available', () => {
      this.setStatus({ state: 'up-to-date', availableVersion: null });
    });

    autoUpdater.on('download-progress', (progress) => {
      this.setStatus({
        state: 'downloading',
        progress: Math.round(progress.percent || 0)
      });
    });

    autoUpdater.on('update-downloaded', (info) => {
      this.setStatus({
        state: 'ready',
        availableVersion: info.version,
        progress: 100
      });
    });

    autoUpdater.on('error', (err) => {
      let message = err.message || 'Update check failed';
      if (/404|could not be found|No published versions/i.test(message)) {
        message = 'No GitHub release found. Publish a release with built installers to enable updates. Private repos require a GH_TOKEN in the environment.';
      }
      this.setStatus({ state: 'error', error: message });
    });
  }

  setStatus(patch) {
    this.status = { ...this.status, ...patch };
    this.emitStatus();
  }

  emitStatus() {
    const hub = this.getHubWindow?.();
    if (hub && !hub.isDestroyed()) {
      hub.webContents.send('updates:on-status', this.status);
    }
  }

  async checkForUpdates({ manual = false } = {}) {
    if (!manual && !this.getAutoUpdateEnabled()) {
      return this.status;
    }

    try {
      const result = await autoUpdater.checkForUpdates();
      if (result?.updateInfo?.version) {
        this.status.availableVersion = result.updateInfo.version;
      }
      return this.status;
    } catch (err) {
      let message = err.message || 'Update check failed';
      if (/404|could not be found|No published versions/i.test(message)) {
        message = 'No GitHub release found. Publish a release with built installers to enable updates. Private repos require a GH_TOKEN in the environment.';
      }
      this.setStatus({ state: 'error', error: message });
      return this.status;
    }
  }

  async downloadUpdate() {
    await autoUpdater.downloadUpdate();
    return this.status;
  }

  quitAndInstall() {
    autoUpdater.quitAndInstall();
  }

  getStatus(currentVersion) {
    return { ...this.status, currentVersion };
  }
}

module.exports = {
  UpdateService
};
