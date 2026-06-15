# TrailMix Module Development Guide

TrailMix v0.5 introduces a **plugin-style module registry** for expandable functionality. Each domain (audio, sessions, chat, etc.) is a self-contained module that registers capabilities into a shared `AppContext`.

## Architecture

```
main/
├── index.js              # Bootstrap entry
├── ModuleRegistry.js     # Plugin loader
├── context/AppContext.js # Dependency injection surface
├── core/runtime.js       # Business logic + services
├── ipc/register-all.js   # IPC handler wiring (split per-module over time)
├── lifecycle.js          # App boot / quit hooks
└── modules/              # Domain modules
    ├── audio.js
    ├── sessions.js
    └── ...
```

## Adding a New Module

Create `main/modules/my-feature.js`:

```javascript
module.exports = {
  id: 'my-feature',
  version: '1.0.0',
  dependencies: ['sessions'],  // optional
  channels: ['my-feature:action'],
  register(ctx) {
    ctx.myFeature = { /* expose API to other modules */ };
  },
  registerIpc(ctx) {
    ctx.ipcMain.handle('my-feature:action', async () => { /* ... */ });
  },
  onReady(ctx) { /* post-boot init */ },
  onBeforeQuit(ctx) { /* cleanup */ }
};
```

Register it in `main/modules/index.js`:

```javascript
const myFeature = require('./my-feature');
// add to BUILTIN_MODULES array
```

Add channel constants to `lib/ipc-channels.js` and expose via `preload.js`.

## Recovery

The v0.4.0 codebase is fully preserved:

- **Git tag:** `v0.4.0`
- **Branch:** `legacy/v0.4.0`
- **File copy:** `legacy/main.v0.4.0.js`

To restore: `git checkout v0.4.0` or `git checkout legacy/v0.4.0`
