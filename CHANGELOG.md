# Changelog

All notable changes to TrailMix are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Restored IPC runtime bindings broken in the v0.5 modular split (chat streaming, Mix enhance, Whisper download, open file location)
- Meeting live transcript now applies coalesced `merged` segment updates instead of duplicating bubbles
- Speaker label mapping in the meeting window uses turn-index keys correctly
- Resume chunk offset uses last segment timestamp; numerical deadline parsing no longer references an undefined date
- Mix notes no longer leave the editor stuck in “enhancing” state after success
- Directory watcher now reacts to `.trail.bak` session files (canonical on-disk format)
- History cards use real CSS classes instead of missing Tailwind utilities

### Changed
- Debounced silent session saves during live capture; flush on pause/stop
- Faster `calls:get-list` path (one-time filesystem sync per process)
- Bounded Mix-Master history context and transcription queue backpressure
- Async/sampled audio level metering; incremental bleed correction
- Hub UX: debounced search, non-destructive chat close, Ctrl/Cmd+B sidebar toggle, Ctrl/Cmd+N new meeting
- Parallel summary + action-item LLM enrichment; LLM `queryComplete` timeouts/retries
- Encrypted autosaves reuse salt/key (cached PBKDF2) instead of re-deriving every write
- `calls:find-related` scores metadata only (no full-table decrypt)
- Chunked secure shred to avoid OOM on large temp WAVs
- Removed ~600 lines of dead hub-embedded meeting UI

### Added
- Hub home recent meetings with Open / Resume Trail
- In-meeting Ctrl+F transcript search, save status, Whisper queue-pressure banner, Stop on Mix-Master streams
- Processing Retry chip for failed sidebar jobs; `/` focuses search; Esc closes modals
- Task/session SQLite indexes; workflow webhook localhost validation
- `docs/OPTIMIZATION_ROADMAP.md` — ranked performance work and feature suggestions

## [0.5.0] - 2026-06-15

### Added
- **Modular main process architecture** — `main/` directory with `ModuleRegistry`, `AppContext`, and 13 domain modules (audio, sessions, meetings, calls, chat, tasks, folders, processing, updates, models, windows, workflow)
- **Plugin-style module API** — `register`, `registerIpc`, `onReady`, `onBeforeQuit` hooks for expandable functionality (`docs/MODULES.md`)
- **IPC channel registry** — complete `lib/ipc-channels.js` contract covering all main ↔ renderer channels
- **Renderer IPC client** — `renderer/shared/ipc-client.js` typed wrapper over `window.api`
- **Legacy recovery** — git tag `v0.4.0`, branch `legacy/v0.4.0`, and `legacy/main.v0.4.0.js` snapshot
- **electron-builder AppImage** — Linux packaging config with whisper.cpp extraResources

### Changed
- **Main entry** — `main.js` is now a thin shim; bootstrap lives in `main/index.js`
- **Runtime core** — business logic extracted to `main/core/runtime.js`; IPC wiring in `main/ipc/register-all.js`
- **Task DB helpers** — `saveTaskToDb` / `getTasksListFromDb` moved to `lib/task-db.js` for shared use
- Version bumped to **0.5.0** (minor increment from 0.4.0)

### Recovery
To restore the pre-rewrite codebase: `git checkout v0.4.0` or `git checkout legacy/v0.4.0`

---

## [0.3.1] - 2026-06-14

### Added
- **README.md** — comprehensive GitHub and package documentation covering installation, features, architecture, and troubleshooting
- **CHANGELOG.md** — release history starting with this version
- **Non-blocking pipeline** — `AudioCaptureService`, `TranscriptionService`, and `LLMInferenceService` offload heavy work from the main process
- **Transcription worker** — ffmpeg conversion and whisper.cpp inference run in a `worker_threads` background thread
- **Streaming LLM output** — chat and Mix & Enhance stream tokens to the UI via `llm:stream-chunk` IPC
- **Jot & Enhance editor** — `EditorComponent` with a mixed user/AI span document model (`origin: user | ai`)
- **Auto-enhance on finalize** — sessions automatically run Mix after stop when jots are present
- **Dual-theme UI** — dark (default) and light Granola-inspired themes via `themes.css` and a Nuts and Bolts toggle
- **TrailMix nomenclature** — The Trail (sidebar), The Mix (editor), Nuts and Bolts (settings), Start the Mix (enhance action)
- **Collapsible sidebar** — The Trail can be toggled for a centered 800px editor stage

### Changed
- Refactored `main.js` to delegate capture, transcription, and LLM calls to dedicated services
- Extracted shared helpers into `lib/` (`editor-document`, `enhance-notes`, `format-timestamp`, `secure-shred`, `audio-vad`)
- Version set to **0.3.1** for this public release (consolidates post-0.3.0 feature work)

### Documentation
- Package metadata updated to include README and CHANGELOG in published files

---

## [0.3.3] - 2026-06-14

### Added
- **Breadcrumb System** — SQLite-backed `session_processing_jobs` and `session_processing_breadcrumbs` for crash-resilient post-session processing
- **Multi-pass pipeline** — Pass 1 live draft (channel labels only), Pass 2 precision clustering (`Speaker_00`, …), Pass 3 contextual name identification via local LLM batches
- **`SessionProcessingService`** — background job queue with boot recovery and progress IPC
- Sidebar processing badges ("Sifting the Mix…", "Sorting the Rations…") with Granola-style progress bars
- Global sticky **Save Settings** button (disabled until a setting changes)
- Sidebar semantic theme tokens for correct light-mode rendering
- Segment coalescing in main process (`lib/transcript-coalesce.js`) to merge contiguous same-speaker blocks

### Fixed
- **Resume button** — removed premature `isRecording=true` before `startRecordingHandler()`; timer no longer resets on pause/resume
- **Resume past session** — uses `activeSession.id` instead of missing `filePath`
- **Light mode sidebar** — replaced hardcoded dark rgba/Tailwind classes with `--sidebar-*` CSS tokens

### Changed
- Removed live 25s LLM diarization during recording (Pass 1 stays fast/light)
- Removed mic/system device badges from dashboard header for cleaner layout
- Headers use `Roboto Slab` display font; body remains Plus Jakarta Sans

---

## [0.3.2] - 2026-06-14

### Added
- README logo header using `assets/logo.png` (same icon as the app window and system tray)
- Step-by-step **Installation guide** and **How to use TrailMix** sections
- Five UI screenshots in `docs/screenshots/` (dark/light themes, live session, The Mix, settings, action items)
- `scripts/capture-screenshots.js` to regenerate documentation screenshots

### Changed
- Expanded README with first-launch setup, usage walkthrough, screenshot gallery, and troubleshooting tables

---

## [0.3.0] - 2026-06-13

### Added
- Extracted `lib/llm-utils.js`, `lib/speaker-diarization.js`, and `lib/task-db.js` from monolithic `main.js`
- Centralized speaker diarization logic and task persistence helpers

### Removed
- Dead code: unused `calculateChannelEnergy()`, legacy task list JSON handlers, unused `customAgents` config, `test_jfk.json` fixture

### Changed
- Improved naming consistency and readability across the codebase without altering core behavior

---

## [0.2.0] - prior release

### Added
- Notion/Apple Notes-inspired layout with Campfire Preserves folders
- Obsidian vault export, Live Trail drawer, and chat context integration
- Mix (Jot & Enhance) with offline LLM support and silent autosaving
- Collapsible side-panel chat, markdown chat, local RAG transcript search
- Sequential transcription queue and SQLite-backed tasks/folders

---

## [0.1.0] - initial release

- Electron desktop app with local whisper.cpp transcription and Ollama integration

[0.3.3]: https://github.com/ConniptionFit/TrailMix/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/ConniptionFit/TrailMix/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/ConniptionFit/TrailMix/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/ConniptionFit/TrailMix/compare/v0.2...v0.3.0
[0.2.0]: https://github.com/ConniptionFit/TrailMix/releases/tag/v0.2
[0.1.0]: https://github.com/ConniptionFit/TrailMix/releases/tag/v0.1
