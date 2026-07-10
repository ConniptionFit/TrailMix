# TrailMix Module Development Guide

TrailMix v0.5 uses a **plugin-style module registry** for expandable functionality. Each domain (audio, sessions, chat, etc.) is declared as a module with channel metadata. Business logic lives in `main/core/runtime.js`; IPC handlers are wired in `main/ipc/register-all.js`.

## Architecture

```
main/
├── index.js              # Bootstrap entry
├── ModuleRegistry.js     # Plugin loader
├── context/AppContext.js # { runtime, registry } DI surface
├── core/runtime.js       # Business logic + services
├── ipc/register-all.js   # IPC handler wiring (split per-module over time)
├── lifecycle.js          # App boot / quit hooks
└── modules/              # Domain module metadata
    ├── audio.js
    ├── sessions.js
    └── ...
```

## Adding a New Module

Create `main/modules/my-feature.js`:

```javascript
const { IPC } = require('../../lib/ipc-channels');

module.exports = {
  id: 'my-feature',
  version: '1.0.0',
  channels: [IPC.MY_FEATURE_ACTION], // add constant to lib/ipc-channels.js first
  // Optional hooks when you need them:
  // register(ctx) { ... }
  // onReady(ctx) { ... }
  // onBeforeQuit(ctx) { ... }
};
```

Register it in `main/modules/index.js` (`BUILTIN_MODULES` array).

Add the channel constant to `lib/ipc-channels.js`, expose it via `preload.js`, and wire the handler in `main/ipc/register-all.js` (or a future per-module `registerIpc` extraction).

## Recovery

The v0.4.0 codebase is fully preserved:

- **Git tag:** `v0.4.0`
- **Branch:** `legacy/v0.4.0`
- **File copy:** `legacy/main.v0.4.0.js`

To restore: `git checkout v0.4.0` or `git checkout legacy/v0.4.0`
