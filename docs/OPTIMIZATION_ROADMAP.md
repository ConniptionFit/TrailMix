# TrailMix Optimization Roadmap

This document captures the review findings from the v0.5 modular architecture pass, what shipped in the first optimization PR, and the recommended next features.

## What shipped (this pass)

### Correctness
- Restored missing IPC runtime bindings (`BrowserWindow`, `enhanceNotesService`, `WHISPER_DIR`, `spawn`, `XDG_CONFIG_DIR`) so chat streaming, Mix enhance, Whisper download, and open-file-location work again.
- Fixed numerical deadline parsing (`d` was undefined).
- Fixed resume chunk offset to use last segment timestamp (not `transcript.length / 2`).
- Meeting window now handles coalesced `merged` segments, applies speaker maps by turn index, and uses `textContent` for transcript lines.

### Performance
- Debounced silent session saves during live transcription (~2.5s) with flush on pause/stop.
- Skipped full filesystem folder sync on every autosave; cache ensured folder IDs.
- Async session file writes (`fs.promises.writeFile`).
- SQLite WAL + busy timeout on the main DB.
- `calls:get-list` no longer runs migrate/full sync on every sidebar refresh.
- Chat context limited to recent/top sessions instead of the entire history table.
- Transcription queue backpressure (drop oldest when depth exceeds cap).
- Longer stop flush wait (`waitForIdle(60s)`).
- Async, sampled audio level metering at 250ms (was sync full-file reads at 120ms).
- Incremental bleed correction (new mic segments vs nearby system window).
- Quieter VAD logging (opt-in via `TRAILMIX_DEBUG_VAD=1`).

### UX
- Meeting chat stream batched with `requestAnimationFrame`.
- Sidebar search debounced; settings browse marks dirty; save feedback without blocking `alert`.
- Mix-Master drawer no longer clears history on close.
- Sidebar toggle shortcut moved to **Ctrl/Cmd+B** (Ctrl+S reserved for save muscle memory).
- **Ctrl/Cmd+N** starts a new meeting from the hub.
- Mini widget shows latest transcript text.
- Basic a11y: transcript `aria-live`, meeting title label, bulk action labels, `prefers-reduced-motion`.

---

## Recommended next optimizations (ranked)

### Tier 1 — high impact
1. **Shared transcript view module** — extract `appendTranscriptLine` / speaker mapping into `renderer/shared/transcript-view.js` and delete the dead hub meeting block (~600 lines in `index.js`).
2. **Virtualize The Trail list** — render only visible session rows; avoid full DOM rebuild on every filter.
3. **SQLite FTS5 full-text search** — index transcript plain text; power sidebar search beyond title/summary.
4. **Whisper pipeline fusion** — one ffmpeg split per chunk (or stereo whisper) and optional 2-worker pool when CPU allows.
5. **Encrypt-once session keys** — reuse salt/key for incremental encrypted saves (today every autosave re-derives PBKDF2).

### Tier 2 — reliability & polish
6. Wire `lib/ipc-channels.js` into `preload.js` and `register-all.js` (single source of truth).
7. Surface transcription lag / dropped chunks in the meeting UI.
8. Toast/inline error system instead of `alert()` / `confirm()`.
9. Validate `workflow:register-trigger` URLs; keep webhook registration main-only.
10. Keep temp audio optional for playback/seek (or export WAV before shred).

### Tier 3 — architecture
11. Move remaining IPC handlers from `register-all.js` into `main/modules/*`.
12. Shrink `runtime.js` into orchestration + thin service facades.
13. Complete `renderer/shared/ipc-client.js` coverage so hub/meeting stop using raw `window.api` ad hoc.

---

## Feature suggestions (local-first Granola/Otter direction)

| Feature | Why it matters |
|---------|----------------|
| **Hub home with recent meetings + Resume** | Empty home underuses the library; make “continue” one click. |
| **In-meeting Ctrl+F transcript search** | Essential for 30–60+ minute calls. |
| **Inline speaker rename** | After diarization, rename “Speaker 2 → Sarah” and persist. |
| **Post-meeting summary toast + pin** | Celebrate completion; surface highlights before the window closes. |
| **Live mini recorder widget** | Timer + last lines + levels (mini window already exists unused). |
| **Export SRT/VTT + structured JSON** | Interop with video editors and other note tools. |
| **True multi-speaker diarization** | Embeddings / pyannote-style clustering beyond LLM post-hoc. |
| **Semantic / RAG recall** | Vector index over local sessions for Mix-Master. |
| **PipeWire-native + macOS/Windows capture** | Broaden beyond Pulse monitor sources. |
| **Health dashboard** | Whisper binary, Ollama reachability, model latency, queue depth. |
| **Keyboard-first workflow** | `/` focus search, Esc close drawers, Ctrl+Shift+R start/stop. |
| **Optional encrypted password vault UX** | Never store encryption password in plaintext settings JSON. |

---

## Measurement checklist

Before/after any further perf work, instrument:

1. Autosaves per minute of recording and bytes written.
2. Transcription queue depth and chunk age (enqueue → complete).
3. Main event-loop lag during capture (`perf_hooks.monitorEventLoopDelay`).
4. `calls:get-list` latency vs session count.
5. Chat prompt size (chars) for hub vs meeting scope.
