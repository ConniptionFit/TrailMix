# Architecture

> Main-process layout, data paths, session persistence, and how the modular shell fits together. See [[IPC & Modules]] for channel ownership; [[Live Capture & Transcription]] for the hot path.

## Bootstrap

```
main.js
  → main/index.js
      → load runtime (main/core/runtime.js)
      → AppContext + ModuleRegistry (main/modules/*)
      → registerIpcHandlers(runtime)  // main/ipc/register-all.js
      → lifecycle (ready → DB → tray → watcher → quit)
```

| Piece | Path | Role |
|---|---|---|
| Runtime | `main/core/runtime.js` | Services, SQLite, paths, capture wiring, enrichment (~2k LOC) |
| IPC | `main/ipc/register-all.js` | All `ipcMain.handle` registrations (migration to modules ongoing) |
| Modules | `main/modules/*.js` | Domain stubs: `channels[]`, hooks; handlers mostly still in register-all |
| Windows | `windows/WindowManager.js` | Hub + meeting `Map` + mini factory |
| Preload | `preload.js` | `window.api` ↔ `IPC.*` |

## Directory map (repo)

| Tree | Owns |
|---|---|
| `main/` | Electron main bootstrap, runtime, IPC, modules |
| `lib/` | Shared pure-ish helpers (FTS, bleed, shred, storage, IPC constants) |
| `services/` | AudioCapture, Transcription, LLM, SessionProcessing, EnhanceNotes, Update |
| `workers/` | `transcription-worker.js` (ffmpeg + whisper.cpp) |
| `renderer/` | Hub, meeting, EditorComponent, themes, shared toast/confirm |
| `windows/` | WindowManager |
| `bin/whisper.cpp` | Packaged STT binary + models (extraResources) |
| `legacy/` | Pre-modular snapshot |
| `Trailmix/` | **This Obsidian vault pack** (sync into vault root as `Trailmix/`) |

## Paths

| Symbol | Resolution |
|---|---|
| `PROJECT_DIR` | App root |
| `DATA_DIR` | `{PROJECT_DIR}/data` |
| `SETTINGS_FILE` | `{DATA_DIR}/settings.json` |
| `TEMP_DIR` | `{DATA_DIR}/temp_rec` |
| `WHISPER_DIR` | `{PROJECT_DIR}/bin/whisper.cpp` |
| `XDG_CONFIG_DIR` | `~/.config/trailmix` |
| `DB_PATH` | `~/.config/trailmix/db.sqlite` |
| Calls dir | `{DATA_DIR}/calls` or `settings.customStoragePath` |

## SQLite (WAL)

### `sessions`

| Column | Notes |
|---|---|
| `id` | TEXT PK (`call_{ts}` → optional `{slug}_{oldId}` after enrich) |
| `date`, `title`, `description` | Metadata |
| `summary`, `actionItems`, `mixNotes`, `enhancedNotes` | Text when encrypted |
| `encrypted` | 0/1 |
| `encrypted_payload` | Full JSON or AES envelope |
| `folder_id` | FK / `fs:` layout |
| `mtimeMs` | Sort key |
| `tags`, `suggestedTags` | JSON arrays |

### Other tables

- `folders` — Campfire Preserves (FS-synced)
- `tasks` — Action Items / Calendar (`sourceCallId`, `sourceSegmentId`, `dueDate`, `omitted`)
- `session_processing_jobs` / `session_processing_breadcrumbs` — pipeline checkpoints
- `sessions_fts` + `session_fts_map` — FTS5 (plaintext only; encrypted excluded)

## Session file layout

| Pattern | Meaning |
|---|---|
| `{callsDir}/{id}.trail.bak` | Canonical on-disk session |
| `{callsDir}/{Folder}/{id}.trail.bak` | Folder ID `fs:Folder` |
| Legacy `.trail` | Migrated into SQLite on boot |

Payload = plaintext session JSON **or** `{ salt, iv, tag, encrypted }` envelope. See [[Encryption & Privacy]].

## Save pipeline

| Path | Behavior |
|---|---|
| Live capture | `scheduleSilentSessionSave` (~2.5s debounce), **`skipFts: true`** |
| Pause / stop / flush | `flushSilentSessionSave` → full save **with FTS** |
| Explicit `calls:save` | Immediate DB + file + FTS |
| Silent IPC (`calls:save-silently`) | Debounced schedule; meeting `beforeunload` flushes via `saveCall` |
| Encrypted w/o password | **No write**; `needsPassword` / broadcast |

Also: salt/key reuse via `encryptionEnvelopes`; folder FS sync skipped on every autosave (cache folder IDs).

## Directory watcher

`fs.watch(callsDir, { recursive: true })` on `.trail` / `.trail.bak` → debounced **`syncDatabaseWithFiles()`** then `calls:list-updated`. Fixes list drift after one-time sync flag.

## In-memory session registry

`lib/session-registry.js` — `getSession` / `setSession` / `createNewSession({ encryptByDefault })`. Meeting windows load via `session:load-meeting`.

## Related

[[Master Prompt]] · [[IPC & Modules]] · [[Processing Pipeline]] · [[The Trail]]
