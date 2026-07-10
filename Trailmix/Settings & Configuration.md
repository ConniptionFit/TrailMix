# Settings & Configuration

> Nuts and Bolts — models, audio, themes, storage, prompts, encryption toggle, updates.

## Location

Hub tab `#tab-settings` · persisted `{PROJECT_DIR}/data/settings.json` · safe GET strips secrets.

## Key settings

| Key | Purpose |
|---|---|
| `selectedModel` | Whisper model filename under `bin/whisper.cpp/models` |
| `selectedLlm` | Ollama model name |
| `selectedMic` / `selectedSink` | Pulse device ids or `default` |
| `enableNoiseCancellation` | Master AEC toggle |
| `aecMode` | `os` / `app` (with guard behavior in worker) |
| `encryptByDefault` | New sessions encrypted |
| `customStoragePath` | Override calls directory |
| `userName` | Speaker identification context |
| `summaryPromptTemplate` / `actionPromptTemplate` | Enrichment prompts |
| Theme | Dark (default) / light via `themes.css` |

## Models IPC

| Channel | Role |
|---|---|
| `models:get-specs` | Hardware recommendation hints |
| `models:get-ollama-models` | List local Ollama tags |
| `models:download-whisper` | Fetch Whisper model + progress events |

## Audio devices

`audio:get-devices` → `pactl` enumeration (sources + sinks). Capture uses sink `.monitor` + mic.

## Updates

`UpdateService` + `updates:check` / `install` / `get-status` (electron-updater → GitHub releases).

## Prompts

`settings:get-default-prompts` returns built-in templates; user overrides stored in settings.

## UX

- Dirty tracking + sticky Save (non-blocking toast feedback)
- Browse directory marks dirty
- Encrypt-by-default does **not** store a password field

## Related

[[Live Capture & Transcription]] · [[Encryption & Privacy]] · [[Mix-Master]] · [[Development & Packaging]]
