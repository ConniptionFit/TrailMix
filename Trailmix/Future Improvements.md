# Future Improvements

> Living roadmap for TrailMix. **To-Do** = open work. **Complete** = recently shipped (prune after verified). Agents: move items here when done, then delete verified Complete rows per [[Master Prompt]].

**Last reviewed:** 2026-07-10 · **Version baseline:** v0.5.0 + optimization pass on `main`

---

## To-Do

| Pri | ID | Category | Item | Notes |
|---|---|---|---|---|
| P1 | UX-TV-01 | Renderer | Shared transcript view module | Extract meeting transcript helpers → `renderer/shared/transcript-view.js`; single `scrollToTranscriptSegment` |
| P1 | CAP-01 | Capture | Optional temp audio retention | Keep WAV for playback/seek **or** export before shred; default remains shred |
| P1 | IPC-01 | Architecture | Split `register-all.js` into `main/modules/*` | Modules already declare channels; move handlers |
| P2 | RT-01 | Architecture | Shrink `runtime.js` | Orchestration + thin service facades |
| P2 | IPC-02 | Renderer | Complete `ipc-client.js` coverage | Hub/meeting stop using ad-hoc `window.api` |
| P2 | SEC-01 | Privacy | OS keychain for session passwords | Still never write to settings.json; unlock UX polish |
| P2 | UI-01 | Hub | Wire Live Trail mini widget | `WindowManager.createMiniWindow` exists but is never called |
| P2 | UI-02 | Hub | Restore or remove Live Trail drawer | JS toggles orphaned after HTML removal |
| P2 | EXP-01 | Export | Meeting “export this note to Obsidian” button | Handler exists; button missing from HTML |
| P2 | CAP-02 | Capture | Ctrl/Cmd+Shift+R start/stop | Keyboard-first capture |
| P3 | DIA-01 | Processing | True multi-speaker diarization | Beyond LLM post-hoc (embeddings / pyannote-style) |
| P3 | RAG-01 | Mix-Master | Semantic / vector recall over sessions | Local embeddings index |
| P3 | PLAT-01 | Platform | PipeWire-native + macOS/Windows capture | Beyond Pulse monitor sources |
| P3 | OPS-01 | Ops | Health dashboard | Whisper binary, Ollama reachability, queue depth, model latency |
| P3 | EXP-02 | Export | SRT/VTT + structured JSON export | Interop with editors |
| P3 | UX-01 | Meeting | Inline speaker rename persistence | Rename Speaker_N → person; save map |
| P3 | UX-02 | Hub | Post-meeting summary toast + pin | Celebrate completion before close |

### Declined / deferred (do not re-open unprompted)

| Item | Reason |
|---|---|
| Store encryption password in settings.json | Explicitly removed; privacy regression |
| Plaintext fallback when encrypt flag set | Closed as critical bug; must prompt or skip |

---

## Complete (recent — prune after verify)

| When | ID | Item |
|---|---|---|
| 2026-07-10 | PERF-01 | Virtualized The Trail + event delegation + scroll-safe multi-select |
| 2026-07-10 | PERF-02 | Dual Whisper workers (8+ cores) + parallel VAD + thread budgets |
| 2026-07-10 | PERF-03 | Parallel precision diarization (2 LLM blocks) |
| 2026-07-10 | PERF-04 | Silent saves skip FTS until flush; meeting close flush |
| 2026-07-10 | FIX-01 | Directory watcher re-syncs DB before list broadcast |
| 2026-07-10 | FIX-02 | Encrypted save never falls back to plaintext |
| 2026-07-10 | FIX-03 | Encryption passwords removed from settings.json |
| 2026-07-10 | FEAT-01 | FTS5 transcript search (`search:sessions`) |
| 2026-07-10 | FEAT-02 | Jump-to-segment from action items / calendar |
| 2026-07-10 | FEAT-03 | Hub home recent + Resume; meeting Ctrl+F; queue pressure; processing Retry |
| 2026-07-10 | IPC-00 | Wire `lib/ipc-channels.js` through preload / register-all / runtime |
| 2026-07-10 | UX-03 | Toasts + TrailMixConfirm; hub chat session-scoped IPC |
| 2026-06-15 | ARCH-01 | v0.5 modular main (`ModuleRegistry`, AppContext, domain modules) |

---

## Measurement checklist (before/after perf work)

1. Autosaves per minute of recording and bytes written  
2. Transcription queue depth and chunk age (enqueue → complete)  
3. Main event-loop lag during capture  
4. `calls:get-list` latency vs session count  
5. Chat prompt size (chars) for hub vs meeting scope  

## Related

[[Master Prompt]] · [[Architecture]] · docs in repo: `docs/OPTIMIZATION_ROADMAP.md` (engineering mirror — vault wins on product priority)
