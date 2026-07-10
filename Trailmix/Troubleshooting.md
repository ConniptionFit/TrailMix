# Troubleshooting

> Dense failure → check table. Prefer logs from the Electron main process and Ollama/whisper stderr.

## Capture / audio

| Symptom | Check |
|---|---|
| No devices | `pactl list short sources/sinks`; Pulse/PipeWire running |
| Silent system channel | Sink monitor name (`*.monitor`); correct `selectedSink` |
| Mic is echo of speakers | Enable noise cancellation; try `aecMode` `os` then `app` |
| Queue pressure banner | Whisper slower than realtime — smaller model, fewer threads contention, close other load |

## Transcription

| Symptom | Check |
|---|---|
| “Whisper binary not found” | `./scripts/setup-whisper.sh`; `bin/whisper.cpp` present in AppImage resources |
| Model missing | Nuts and Bolts download / models dir |
| Empty transcript | VAD gated silence; verify levels meters; `TRAILMIX_DEBUG_VAD=1` |
| Worker exit / stall | TranscriptionService resets worker; check ffmpeg in PATH |

## LLM / Mix-Master

| Symptom | Check |
|---|---|
| Chat errors | `ollama serve`; `ollama list`; `selectedLlm` pulled |
| Slow / timeout | Model size vs RAM; `queryComplete` timeouts; try smaller model |
| Enhance no-op | Need Mix-Ins text; Ollama up; watch enhancing state clears |

## Encryption / save

| Symptom | Check |
|---|---|
| “Encryption password required” | Set password in meeting modal; keys are memory-only (re-unlock after restart) |
| Session missing from FTS | Encrypted/locked sessions excluded until unlock |
| Trail not updating from disk | Watcher should `syncDatabaseWithFiles`; confirm `.trail.bak` under calls dir |

## Processing

| Symptom | Check |
|---|---|
| Stuck badge | Retry chip; check Ollama; inspect `session_processing_jobs` |
| No action items | Enrichment needs transcript text; tasks extracted after summary pass |

## UI

| Symptom | Check |
|---|---|
| Sidebar selection wrong | Uses `openedSessionId` after open |
| Multi-select lost on scroll | Should persist via `selectedTrailIds` — file a bug if not |
| Mini widget missing | `createMiniWindow` not wired from runtime yet |

## Related

[[Live Capture & Transcription]] · [[Encryption & Privacy]] · [[Settings & Configuration]] · [[Future Improvements]]
