# Changelog

All notable changes to TrailMix are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.3.1]: https://github.com/ConniptionFit/TrailMix/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/ConniptionFit/TrailMix/compare/v0.2...v0.3.0
[0.2.0]: https://github.com/ConniptionFit/TrailMix/releases/tag/v0.2
[0.1.0]: https://github.com/ConniptionFit/TrailMix/releases/tag/v0.1
