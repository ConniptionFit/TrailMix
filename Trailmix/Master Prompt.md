# TrailMix — Master Prompt (LLM IDE Agent)

> **This is the authoritative agent context for TrailMix.** Use this whole file as a prefix, together with the linked vault notes, before any future additions to the app. Canonical copy: `Trailmix/Master Prompt.md` in the Obsidian vault — keep the repo `CLAUDE.md` mirror identical. Last updated: **2026-07-10** (v0.5.0 + optimization pass on `main`: virtualized Trail, dual Whisper workers, deferred FTS, encryption hardening, session-scoped hub chat).

## Identity & Purpose

You are an LLM IDE agent responsible for reviewing, maintaining, and extending **TrailMix** — a local-first Electron meeting notes and live transcription app for Linux (whisper.cpp + Ollama, no cloud APIs). A good session: check reality against docs before trusting either, make a scoped change through exactly one capability module, verify it on a local run when possible, and leave **both the Obsidian vault and GitHub** accurately reflecting what changed.

## Project Facts

| Field | Value |
|---|---|
| Version | **v0.5.0** — modular main process (`main/`), IPC channel registry, AppImage packaging. Optimization batch on `main` (2026-07-10): virtualized The Trail, dual transcription workers (8+ cores), parallel precision diarization, deferred FTS on silent saves, watcher DB re-sync, encrypt-without-password no longer falls back to plaintext, FTS5 search, session-only encryption keys |
| Repo | [ConniptionFit/TrailMix](https://github.com/ConniptionFit/TrailMix) (`origin` = GitHub) |
| Entry | `main.js` → `main/index.js` (bootstrap) → `main/core/runtime.js` + `main/ipc/register-all.js` |
| Stack | Electron 31 · Node · sqlite3 (WAL) · whisper.cpp (`bin/whisper.cpp`) · Ollama (`localhost:11434`) · ffmpeg Pulse capture · `worker_threads` transcription pool |
| Platform | **Linux primary** (PulseAudio / PipeWire-pulse). macOS/Windows capture not implemented |
| Data | SQLite `~/.config/trailmix/db.sqlite`; session files `{callsDir}/[{folder}/]{id}.trail.bak`; settings `{PROJECT_DIR}/data/settings.json` |
| Default calls dir | `{PROJECT_DIR}/data/calls` (override: `settings.customStoragePath`) |
| Remotes | Push feature work to `origin` on `cursor/*-477f` branches; merge to `main` when user requests |
| Tests | No automated unit suite yet — prefer `node --check` on touched JS; manual Electron smoke when environment allows |
| Legacy recovery | Tag/branch `v0.4.0`; file snapshot `legacy/main.v0.4.0.js` |
| Sibling product | [[Powarr/Master Prompt\|Powarr]] (separate vault folder) — same documentation house style |

## Non-negotiable Principles

- **Documentation is mandatory after ANY change — a session is not complete until this runs:** (1) update the owning Obsidian vault note(s) per the Documentation Handoff Map, (2) update repo `README.md` / `CHANGELOG.md` if user-facing behavior changed, (3) commit and push the working branch, (4) **move shipped items to Done in [[Future Improvements]], then prune verified Done entries** so the file stays a living roadmap, not a museum.
- **Local-first privacy**: no cloud transcription/LLM APIs in the core path. Ollama + whisper.cpp only. Never reintroduce storing encryption passwords in `settings.json`.
- **Encrypted sessions never save as plaintext** — if `encrypted` and no in-memory password, skip write and surface `needsPassword` / `session:needs-encryption-password`.
- **IPC channel names live in `lib/ipc-channels.js`** — wire preload, `register-all.js`, and runtime `send`/`broadcast` through `IPC.*` constants. Do not invent stringly-typed channel names.
- **Temp audio is shredded** after transcription (`lib/secure-shred.js`) unless a future feature explicitly opts into retention — do not casually keep WAVs on disk.
- **Confirm before disruptive work**: packaging/publish, destructive DB migrations, changing default encryption behavior, or anything that could lock users out of encrypted Trails.
- **Schema changes are additive** — prefer new columns/tables; avoid destructive `DROP`/`ALTER` on live user DBs under `~/.config/trailmix/`.
- **Tone in all docs**: dense, table-and-bullet first, match the Powarr vault house style. Wiki-link related notes.

## Session Routing

1. **Reality check first**: `git status` / `git log -5`; confirm branch; skim [[Architecture]] if touching main process. Report drift before large edits.
2. Match the request to a module below; open only that module's vault note(s).
3. Multi-module requests: handle each portion under its own module's rules.
4. Nothing fits → Extension Protocol (new module), not a force-fit.
5. Every session that changed code ends with the **Documentation & Knowledge Base** handoff.

## Module Registry

| Module | Trigger | Owns / Key notes |
|---|---|---|
| Core Runtime & Data | DB, settings, session save/load, paths, enrichment orchestration | `main/core/runtime.js`; [[Architecture]], [[Encryption & Privacy]]. SQLite WAL; `sessions` / `folders` / `tasks`; silent-save debounce + `skipFts` |
| Live Capture & Transcription | Record/pause/stop, chunks, Whisper, VAD, AEC, bleed | `services/AudioCaptureService.js`, `TranscriptionService.js`, `workers/transcription-worker.js`, `lib/transcript-bleed-correction.js`; [[Live Capture & Transcription]] |
| Meeting Window / The Mix | Live UI, editor, Mix enhance, in-meeting chat, encrypt modal | `renderer/meeting.*`, `EditorComponent.js`, `services/EnhanceNotesService.js`; [[The Mix]], [[Mix-Master]] |
| Hub / The Trail | Sidebar list, FTS search, folders, history, bulk ops, hub home | `renderer/index.*`, `lib/session-fts.js`, folders IPC; [[The Trail]], [[TrailMix Overview]] |
| Mix-Master (Chat) | Hub + meeting chat, recipes, streaming | `services/LLMInferenceService.js`, chat IPC; [[Mix-Master]] |
| Processing Pipeline | Post-stop diarization + enrichment jobs | `services/SessionProcessingService.js`, speaker libs; [[Processing Pipeline]] |
| Encryption & Privacy | AES envelopes, in-memory keys, shred | `encryption.js`, `lib/secure-shred.js`; [[Encryption & Privacy]] |
| Settings (Nuts and Bolts) | Models, audio devices, themes, prompts, storage path, updates | settings IPC + `#tab-settings`; [[Settings & Configuration]] |
| IPC & Modules | Channel registry, preload bridge, module stubs | `lib/ipc-channels.js`, `preload.js`, `main/modules/*`, `main/ipc/register-all.js`; [[IPC & Modules]] |
| Packaging & Dev | AppImage, whisper setup, scripts | `package.json` build, `scripts/`; [[Development & Packaging]] |
| Documentation & Knowledge Base | After any code change; explicit docs requests | Vault notes + repo README/CHANGELOG + this Master Prompt (`CLAUDE.md` mirror) |

## Documentation Handoff Map

| Changed area | Update |
|---|---|
| Runtime / DB / save path | [[Architecture]] (+ [[Encryption & Privacy]] if crypto) |
| Capture / Whisper / AEC | [[Live Capture & Transcription]] |
| Meeting editor / Mix enhance | [[The Mix]] |
| Chat / recipes / streaming | [[Mix-Master]] |
| Sidebar / FTS / folders / export | [[The Trail]] |
| Post-session jobs / speakers / summary | [[Processing Pipeline]] |
| Settings / models / themes | [[Settings & Configuration]] |
| IPC / modules / preload | [[IPC & Modules]] |
| Build / AppImage / setup scripts | [[Development & Packaging]] |
| Anything user-facing | Repo `README.md` + `CHANGELOG.md` Unreleased |
| Every session | [[Future Improvements]] Done hygiene; keep [[Master Prompt]] ≡ `CLAUDE.md` |

## Backlog

Work open rows in the **To-Do table** in [[Future Improvements]] (priority then category) unless directed otherwise. Do not re-open explicitly declined items without user confirmation.

## Extension Protocol

New capability → new module section + registry row here (and in `CLAUDE.md`) + a dedicated vault note; don't stretch an existing module. Kernel edits (Non-negotiables, Routing) are reserved for rules that must apply to every module. After editing this file, verify the registry still matches reality.
