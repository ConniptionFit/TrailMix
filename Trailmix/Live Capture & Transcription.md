# Live Capture & Transcription

> Dual-channel Pulse capture → 2s WAV chunks → Whisper worker pool → bleed correction → UI. Hot path for latency and CPU.

## Pipeline

```
pactl devices → AudioCaptureService.start()
  → ffmpeg (sink.monitor + mic) → 2s stereo 16 kHz WAV
  → TranscriptionService.enqueueChunk (max depth 24; drop oldest)
  → worker: split L/R → VAD (parallel) → AEC → whisper L∥R
  → secure shred temps
  → coalesce segments → bleed correction → IPC update
  → scheduleSilentSessionSave (2.5s, skipFts)
```

## Services

| Component | File | Notes |
|---|---|---|
| Capture | `services/AudioCaptureService.js` | ffmpeg Pulse; chunk files under `data/temp_rec/` |
| Queue | `services/TranscriptionService.js` | Backpressure; **1 worker** (&lt;8 cores) or **2** (≥8); adaptive `threadBudget` |
| Worker | `workers/transcription-worker.js` | One ffmpeg stereo split; parallel Whisper; echo dedupe |
| Bleed | `lib/transcript-bleed-correction.js` | Mic vs nearby system window; confidence gate |
| Coalesce | `lib/transcript-coalesce.js` | Merge adjacent updates for UI |
| Levels | `lib/audio-levels.js` | Sampled ~250ms (async) |
| VAD | `lib/audio-vad.js` | Silence gate; debug via `TRAILMIX_DEBUG_VAD=1` |
| AEC | `lib/audio-aec.js` + `lib/audio-echo-os.js` | Modes: `off` / `os` / `app` / `guard` |

## Speakers (live)

| Channel | Label |
|---|---|
| System (left) | `Speaker 1` (until post-processing renames) |
| Mic (right) | `You` |

Post-stop precision/identification: see [[Processing Pipeline]].

## Stop / finalize

1. Stop capture; wait for queue idle (up to **60s**)
2. `flushSilentSessionSave` (FTS on)
3. Shred `TEMP_DIR`
4. `finalizeAndSaveSession` → create `SessionProcessingService` job
5. Broadcast list/processing updates

## Queue pressure UX

`audio:on-queue-pressure` → meeting banner when Whisper falls behind. Dropped chunks increment `droppedChunks`.

## Controls (meeting)

| Action | Behavior |
|---|---|
| Start Trail | `audio:start-recording` (conflict modal if another session live) |
| Pause / Resume | Flush silent save on pause; resume offset from last segment time |
| Resume past Trail | `calls:resume-transcription` (needs unlock if encrypted) |
| Stop | Finalize + processing |

## Do / Don't

- **Do** keep shredding temps after each chunk unless implementing explicit playback retention ([[Future Improvements]]).
- **Do** pass `threadBudget` when adding workers so dual Whisper doesn't thrash all cores.
- **Don't** reintroduce sync full-file level metering or unbounded transcription queues.

## Related

[[Architecture]] · [[Processing Pipeline]] · [[Encryption & Privacy]] · [[Troubleshooting]]
