const path = require('path');
const { BrowserWindow, screen } = require('electron');

class WindowManager {
  constructor({ projectDir, preloadPath }) {
    this.projectDir = projectDir;
    this.preloadPath = preloadPath;
    this.hubWindow = null;
    this.meetingWindows = new Map();
    this.miniWindows = new Map();
  }

  createHubWindow() {
    if (this.hubWindow && !this.hubWindow.isDestroyed()) {
      this.hubWindow.focus();
      return this.hubWindow;
    }

    this.hubWindow = new BrowserWindow({
      width: 1100,
      height: 750,
      title: 'TrailMix',
      icon: path.join(this.projectDir, 'assets', 'logo.png'),
      webPreferences: {
        preload: this.preloadPath,
        contextIsolation: true,
        nodeIntegration: false,
        // preload.js requires ./lib/ipc-channels — sandboxed preloads can only
        // require Electron built-ins, so the sandbox must stay off or window.api
        // never gets exposed and every renderer breaks.
        sandbox: false
      }
    });

    this.hubWindow.webContents.on('console-message', (event, level, message, line, sourceId) => {
      console.log(`[Hub] ${message} (${sourceId}:${line})`);
    });

    this.hubWindow.loadFile(path.join(this.projectDir, 'renderer', 'index.html'), {
      query: { mode: 'hub' }
    });

    this.hubWindow.on('closed', () => {
      this.hubWindow = null;
    });

    return this.hubWindow;
  }

  openMeetingWindow(sessionId, options = {}) {
    const existing = this.meetingWindows.get(sessionId);
    if (existing && !existing.isDestroyed()) {
      existing.focus();
      if (options.segmentId) {
        existing.webContents.send('session:focus-segment', { segmentId: options.segmentId });
      }
      return existing;
    }

    const meetingWindow = new BrowserWindow({
      width: 1100,
      height: 760,
      minWidth: 960,
      minHeight: 600,
      title: 'TrailMix Meeting',
      icon: path.join(this.projectDir, 'assets', 'logo.png'),
      webPreferences: {
        preload: this.preloadPath,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false,
        additionalArguments: [`sessionId=${sessionId}`]
      }
    });

    meetingWindow.webContents.on('console-message', (event, level, message, line, sourceId) => {
      console.log(`[Meeting ${sessionId}] ${message} (${sourceId}:${line})`);
    });

    const query = { sessionId };
    if (options.segmentId) query.segmentId = options.segmentId;

    meetingWindow.loadFile(path.join(this.projectDir, 'renderer', 'meeting.html'), {
      query
    });

    meetingWindow.on('closed', () => {
      this.meetingWindows.delete(sessionId);
      this.destroyMiniWindow(sessionId);
    });

    this.meetingWindows.set(sessionId, meetingWindow);
    return meetingWindow;
  }

  getMeetingWindow(sessionId) {
    const win = this.meetingWindows.get(sessionId);
    if (win && !win.isDestroyed()) return win;
    return null;
  }

  getHubWindow() {
    if (this.hubWindow && !this.hubWindow.isDestroyed()) return this.hubWindow;
    return null;
  }

  listMeetingSessionIds() {
    return [...this.meetingWindows.keys()];
  }

  rekeyMeetingWindow(oldSessionId, newSessionId) {
    if (!oldSessionId || !newSessionId || oldSessionId === newSessionId) return null;
    const existing = this.getMeetingWindow(oldSessionId);
    if (!existing) return null;
    this.meetingWindows.delete(oldSessionId);
    this.meetingWindows.set(newSessionId, existing);
    const mini = this.miniWindows.get(oldSessionId);
    if (mini) {
      this.miniWindows.delete(oldSessionId);
      this.miniWindows.set(newSessionId, mini);
    }
    return existing;
  }

  broadcastToMeeting(sessionId, channel, payload) {
    const win = this.getMeetingWindow(sessionId);
    if (win) win.webContents.send(channel, payload);
  }

  broadcastToHub(channel, payload) {
    const win = this.getHubWindow();
    if (win) win.webContents.send(channel, payload);
  }

  broadcastToActiveMeetings(channel, payload, exceptSessionId = null) {
    this.meetingWindows.forEach((win, sessionId) => {
      if (sessionId === exceptSessionId) return;
      if (!win.isDestroyed()) win.webContents.send(channel, payload);
    });
  }

  createMiniWindow(sessionId) {
    if (this.miniWindows.get(sessionId)) return;

    const meetingWindow = this.getMeetingWindow(sessionId);
    if (!meetingWindow) return;

    const miniWindow = new BrowserWindow({
      width: 260,
      height: 90,
      frame: false,
      resizable: false,
      alwaysOnTop: true,
      skipTaskbar: true,
      webPreferences: {
        preload: this.preloadPath,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false
      }
    });

    miniWindow.loadFile(path.join(this.projectDir, 'renderer', 'index.html'), {
      query: { mode: 'mini', sessionId }
    });

    const primaryDisplay = screen.getPrimaryDisplay();
    const { width, height } = primaryDisplay.workAreaSize;
    miniWindow.setPosition(width - 280, height - 110);

    miniWindow.on('closed', () => {
      this.miniWindows.delete(sessionId);
    });

    this.miniWindows.set(sessionId, miniWindow);
  }

  destroyMiniWindow(sessionId) {
    const mini = this.miniWindows.get(sessionId);
    if (mini && !mini.isDestroyed()) mini.close();
    this.miniWindows.delete(sessionId);
  }

  restoreMeetingFromMini(sessionId) {
    const meetingWindow = this.getMeetingWindow(sessionId);
    if (meetingWindow) {
      meetingWindow.restore();
      meetingWindow.focus();
    }
    this.destroyMiniWindow(sessionId);
  }
}

module.exports = {
  WindowManager
};
