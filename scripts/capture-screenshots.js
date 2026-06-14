#!/usr/bin/env node
/**
 * Captures README documentation screenshots using Electron + mock preload data.
 * Run: xvfb-run -a node scripts/capture-screenshots.js
 */
const { app, BrowserWindow } = require('electron');
const path = require('path');
const fs = require('fs');

const ROOT = path.join(__dirname, '..');
const OUT_DIR = path.join(ROOT, 'docs', 'screenshots');

async function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function capture(win, filename) {
  await delay(400);
  const image = await win.webContents.capturePage();
  const png = image.toPNG();
  const outPath = path.join(OUT_DIR, filename);
  fs.writeFileSync(outPath, png);
  console.log(`Saved ${outPath}`);
}

async function clickNav(win, navId) {
  await win.webContents.executeJavaScript(`
    (function () {
      const nav = document.getElementById('${navId}');
      if (nav) nav.click();
    })();
  `);
  await delay(300);
}

async function applyTheme(win, theme) {
  await win.webContents.executeJavaScript(`
    (function () {
      document.documentElement.setAttribute('data-theme', '${theme}');
      localStorage.setItem('trailmix-theme', '${theme}');
      const darkBtn = document.getElementById('btn-theme-dark');
      const lightBtn = document.getElementById('btn-theme-light');
      if (darkBtn && lightBtn) {
        darkBtn.classList.toggle('active', '${theme}' === 'dark');
        lightBtn.classList.toggle('active', '${theme}' === 'light');
      }
    })();
  `);
  await delay(200);
}

async function populateLiveSession(win) {
  await win.webContents.executeJavaScript(`
    (async function () {
      const demo = {
        id: 'demo-live',
        title: 'Weekly Standup — Product Sync',
        summary: 'The team reviewed sprint progress, discussed API blockers, and aligned on a June launch window.',
        actionItems: '- [You] - Send revised API spec by Friday\\n- [Alex] - Schedule follow-up interviews',
        transcript: [
          { id: 's1', timestamp: '00:12', timestampMs: 12000, speaker: 'Alex', text: 'Good morning everyone. Let us start with sprint updates.' },
          { id: 's2', timestamp: '00:28', timestampMs: 28000, speaker: 'You', text: 'API integration is on track. I will share the revised spec today.' },
          { id: 's3', timestamp: '00:45', timestampMs: 45000, speaker: 'Jordan', text: 'Design mocks for the onboarding flow are ready for review.' },
          { id: 's4', timestamp: '01:02', timestampMs: 62000, speaker: 'Alex', text: 'Great. Let us target a soft launch in the third week of June.' }
        ],
        editorDocument: {
          version: 1,
          plainJots: 'Need to follow up on API spec\\nLaunch window: 3rd week of June',
          spans: [
            { origin: 'user', text: 'Need to follow up on API spec' },
            { origin: 'ai', text: ' — Alex asked for the revised API documentation during sprint review.' },
            { origin: 'user', text: 'Launch window: 3rd week of June' },
            { origin: 'ai', text: ' — The team aligned on a soft launch timeline after discussing onboarding readiness.' }
          ],
          enhancedAt: '2026-06-14T09:32:00.000Z'
        }
      };

      window.__demoSession = demo;

      const recTitle = document.getElementById('rec-title');
      const recIndicator = document.getElementById('rec-indicator');
      const recTimer = document.getElementById('rec-timer');
      const btnRecord = document.getElementById('btn-record-toggle');
      const btnPause = document.getElementById('btn-pause-toggle');
      const liveDrawer = document.getElementById('live-trail-drawer');
      const transcriptContainer = document.getElementById('transcript-container');

      if (recTitle) recTitle.textContent = 'Transcribing Live…';
      if (recIndicator) recIndicator.className = 'rec-indicator recording';
      if (recTimer) recTimer.textContent = '04:18';
      if (btnRecord) {
        btnRecord.classList.remove('start');
        btnRecord.classList.add('stop');
        btnRecord.innerHTML = '<span class="btn-icon">⏹</span> Stop Transcribing';
      }
      if (btnPause) btnPause.classList.remove('hidden');
      if (liveDrawer) liveDrawer.classList.remove('hidden');

      if (transcriptContainer) {
        transcriptContainer.innerHTML = demo.transcript.map(function (seg) {
          return '<div class="transcript-segment" data-speaker="' + seg.speaker + '">' +
            '<div class="segment-meta"><span class="segment-time">' + seg.timestamp + '</span>' +
            '<span class="segment-speaker">' + seg.speaker + '</span></div>' +
            '<div class="segment-text">' + seg.text + '</div></div>';
        }).join('');
      }

      const summaryContent = document.getElementById('summary-content');
      if (summaryContent) {
        summaryContent.innerHTML = '<p>' + demo.summary + '</p>';
        summaryContent.classList.remove('hidden');
      }

      const sidebarList = document.getElementById('sidebar-calls-list');
      if (sidebarList) {
        sidebarList.innerHTML =
          '<div class="call-list-item selected">' +
          '<div class="call-item-title">Weekly Standup — Product Sync</div>' +
          '<div class="call-item-desc">Sprint planning, API milestones, and launch timeline review.</div>' +
          '<div class="call-tags-container"><span class="tag-pill">standup</span><span class="tag-pill">product</span></div>' +
          '</div>' +
          '<div class="call-list-item">' +
          '<div class="call-item-title">User Research Interview</div>' +
          '<div class="call-item-desc">Feedback on onboarding flow and offline-first expectations.</div>' +
          '</div>' +
          '<div class="call-list-item">' +
          '<div class="call-item-title">1:1 with Alex</div>' +
          '<div class="call-item-desc">Career growth, project ownership, and Q3 goals.</div>' +
          '</div>';
      }

      const foldersList = document.getElementById('sidebar-folders-list');
      if (foldersList) {
        foldersList.innerHTML =
          '<div class="folder-list-item"><span>Work Meetings</span></div>' +
          '<div class="folder-list-item"><span>Research</span></div>';
      }
    })();
  `);
  await delay(500);
}

async function showMixTab(win) {
  await win.webContents.executeJavaScript(`
    (function () {
      const mixBtn = document.getElementById('btn-show-mix');
      const mixContent = document.getElementById('mix-content');
      const summaryContent = document.getElementById('summary-content');
      const actionContent = document.getElementById('action-content');
      const legend = document.getElementById('editor-legend');
      const root = document.getElementById('editor-component-root');

      document.querySelectorAll('.analysis-tab-btn').forEach(function (b) { b.classList.remove('active'); });
      if (mixBtn) mixBtn.classList.add('active');
      if (summaryContent) summaryContent.classList.add('hidden');
      if (actionContent) actionContent.classList.add('hidden');
      if (mixContent) mixContent.classList.remove('hidden');
      if (legend) legend.classList.remove('hidden');

      if (root) {
        root.innerHTML =
          '<div class="editor-component">' +
          '<div class="editor-status">The Mix is complete · your jots in bold, AI context in gray</div>' +
          '<div class="editor-mixed-view">' +
          '<p><strong>Need to follow up on API spec</strong><span class="ai-span"> — Alex asked for the revised API documentation during sprint review.</span></p>' +
          '<p><strong>Launch window: 3rd week of June</strong><span class="ai-span"> — The team aligned on a soft launch timeline after discussing onboarding readiness.</span></p>' +
          '</div>' +
          '<textarea class="editor-jots-input" placeholder="Jot quick thoughts here during the meeting…">Need to follow up on API spec\\nLaunch window: 3rd week of June</textarea>' +
          '</div>';
      }
    })();
  `);
  await delay(400);
}

async function run() {
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    show: false,
    backgroundColor: '#0f1115',
    webPreferences: {
      preload: path.join(__dirname, 'screenshot-preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  await win.loadFile(path.join(ROOT, 'renderer', 'index.html'));
  await delay(1500);

  await applyTheme(win, 'dark');
  await clickNav(win, 'nav-dashboard');
  await populateLiveSession(win);
  await capture(win, '01-live-session-dark.png');

  await showMixTab(win);
  await capture(win, '02-the-mix-dark.png');

  await clickNav(win, 'nav-settings');
  await capture(win, '03-nuts-and-bolts-dark.png');

  await applyTheme(win, 'light');
  await clickNav(win, 'nav-dashboard');
  await populateLiveSession(win);
  await capture(win, '04-live-session-light.png');

  await clickNav(win, 'nav-action-items');
  await win.webContents.executeJavaScript(`
    (function () {
      const list = document.getElementById('action-items-list');
      if (list) {
        list.innerHTML =
          '<label class="action-item-row"><input type="checkbox"> <span>[You] - Send revised API spec to the team by Friday</span></label>' +
          '<label class="action-item-row completed"><input type="checkbox" checked> <span>[Alex] - Schedule follow-up user interviews</span></label>' +
          '<label class="action-item-row"><input type="checkbox"> <span>[Unassigned] - Update README with install screenshots</span></label>';
      }
    })();
  `);
  await capture(win, '05-action-items-light.png');

  app.quit();
}

app.whenReady().then(run).catch((err) => {
  console.error(err);
  app.quit();
  process.exit(1);
});
