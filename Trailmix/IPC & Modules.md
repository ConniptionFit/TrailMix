# IPC & Modules

> Single channel registry, preload bridge, modular main stubs, and the still-central `register-all.js`.

## Channel registry

**Source of truth:** `lib/ipc-channels.js` → `IPC.*`

Domains: app, audio (+ events), settings, sessions/meetings, calls (+ FTS search), chat/LLM, folders, tasks, processing, models, updates, workflow.

**Rule:** preload invokes, register-all handles, and runtime `webContents.send` / `broadcastToSession` must all use `IPC` constants — no new raw channel strings.

## Preload

`preload.js` exposes `window.api` via `contextBridge`. Event helpers return unsubscribe functions. Partial typed wrapper exists at `renderer/shared/ipc-client.js` (`window.trailmix`) — hub/meeting still mostly use `window.api` ad hoc ([[Future Improvements]]).

## Registration

```
main/index.js
  → loadBuiltinModules(registry)
  → registerIpcHandlers(runtime)  // main/ipc/register-all.js
```

`register-all.js` destructures the runtime facade and registers ~50+ handlers. Domain modules under `main/modules/` declare `id`, `channels`, and lifecycle hooks; **handler bodies are not fully migrated yet**.

## Module API (`docs/MODULES.md`)

```js
module.exports = {
  id: 'my-feature',
  version: '1.0.0',
  dependencies: ['sessions'],
  channels: ['my-feature:action'],
  register(ctx) {},
  registerIpc(ctx) {},
  onReady(ctx) {},
  onBeforeQuit(ctx) {}
};
```

Register in `main/modules/index.js` `BUILTIN_MODULES`.

## Important events (renderer ← main)

| Event | Meaning |
|---|---|
| `audio:on-transcription-update` | New/merged segments |
| `audio:on-transcription-correction` | Bleed removals |
| `audio:on-queue-pressure` | Whisper backlog |
| `audio:on-levels` | Meters |
| `processing:jobs-updated` | Sidebar badges |
| `session:updated` / `session:focus-segment` | Meeting sync / jump |
| `session:needs-encryption-password` | Prompt unlock |
| `llm:stream-chunk` | Chat tokens |
| `calls:list-updated` | Refresh Trail |

## Related

[[Architecture]] · [[Master Prompt]] · [[Development & Packaging]]
