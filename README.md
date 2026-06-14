# TrailMix

**Local-first AI note-taking and live transcription for Linux**

TrailMix captures system audio and microphone input, transcribes speech offline with [whisper.cpp](https://github.com/ggerganov/whisper.cpp), and enriches your notes with a local LLM via [Ollama](https://ollama.com). Everything stays on your machine—no cloud APIs, no account required.

---

## Features

### Live capture & transcription
- **Dual-channel recording** — system audio (meetings, calls) and microphone in stereo, split and transcribed independently to reduce overlap collisions
- **Real-time transcript** — 2-second chunks processed in a background worker thread so the UI stays responsive
- **Speaker diarization** — local LLM assigns speaker labels after each session
- **Pause / resume** — continue recording without losing context

### The Mix (Jot & Enhance)
- Jot plain-text notes during a session; AI blends them with the transcript after you stop
- Mixed document model: **your words in bold**, AI context in gray
- Customizable note-taking style templates (Executive, Bullet, Narrative, and more)
- Manual re-run via **Start the Mix** anytime

### AI assistant
- Collapsible chat panel with transcript-aware Q&A (local RAG over the active session)
- Streaming LLM responses for chat and Mix & Enhance
- Automatic summary, action items, title, description, and tag suggestions on session finalize

### Session management
- **The Trail** — collapsible sidebar listing all sessions with search and multi-select
- **Campfire Preserves** — organize sessions into folders
- Merge, export, delete, and find related sessions
- Obsidian vault export per folder
- Optional AES-256-GCM encryption at rest (PBKDF2 key derivation)

### Tasks & timeline
- Action items extracted from transcripts and stored in SQLite
- Toggle completion, omit, or bulk-delete from the timeline view
- Optional deadline color-coding

### Nuts and Bolts (settings)
- Whisper model selection and in-app download
- Ollama model picker
- PulseAudio source/sink device selection
- Custom storage path (XDG-friendly defaults)
- Editable LLM prompt templates for notes, summaries, and action items
- **Dark** and **light** themes (Granola-inspired light mode)

---

## Requirements

| Dependency | Purpose |
|------------|---------|
| **Linux** (primary target) | PulseAudio for audio routing |
| **Node.js** 18+ | Runtime |
| **ffmpeg** | Audio capture and chunking |
| **`pactl`** (PulseAudio) | Device enumeration |
| **Ollama** | Local LLM inference |
| **whisper.cpp** | Offline speech-to-text (see setup below) |
| **build tools** | `make`, `git`, C++ compiler (for whisper.cpp) |

> **Note:** System-audio capture is Linux/PulseAudio-specific today. macOS and Windows ports would require alternate capture backends.

---

## Installation

### 1. Clone the repository

```bash
git clone https://github.com/ConniptionFit/TrailMix.git
cd TrailMix
```

### 2. Install Node dependencies

```bash
npm install
```

### 3. Set up whisper.cpp and models

```bash
chmod +x scripts/setup-whisper.sh
./scripts/setup-whisper.sh
```

This clones whisper.cpp into `bin/whisper.cpp`, compiles the binary, and downloads the `tiny` and `base` GGML models.

### 4. Install and start Ollama

Install [Ollama](https://ollama.com/download) and pull a model (the default in settings is `gemma3:1b`):

```bash
ollama pull gemma3:1b
```

Use a larger model (e.g. `llama3.2`, `mistral`) for better summaries and diarization if your hardware allows.

### 5. System packages (Debian/Ubuntu example)

```bash
sudo apt update
sudo apt install ffmpeg pulseaudio-utils build-essential git
```

---

## Quick start

```bash
npm start
```

1. Open **Nuts and Bolts** (⚙️) and confirm your microphone, system audio sink, Whisper model, and Ollama model.
2. Click **Start Recording** on the dashboard (or use the system tray).
3. Jot notes in **The Mix** tab while the live transcript streams in **The Trail**.
4. Click **Stop** — TrailMix runs diarization, summary, action items, and auto-enhance in the background.
5. Review the blended notes, chat with the transcript, or export to Obsidian.

---

## Project structure

```
TrailMix/
├── main.js                 # Electron main process, IPC, session orchestration
├── preload.js              # Secure renderer ↔ main bridge
├── encryption.js           # AES-256-GCM encrypt/decrypt
├── lib/                    # Shared utilities
│   ├── audio-vad.js
│   ├── editor-document.js  # Jot & Enhance document model
│   ├── enhance-notes.js
│   ├── format-timestamp.js
│   ├── llm-utils.js
│   ├── secure-shred.js
│   ├── speaker-diarization.js
│   └── task-db.js
├── services/               # Background service layer
│   ├── AudioCaptureService.js
│   ├── TranscriptionService.js
│   ├── LLMInferenceService.js
│   └── EnhanceNotesService.js
├── workers/
│   └── transcription-worker.js   # ffmpeg + whisper in worker_threads
├── renderer/               # UI (vanilla JS, no bundler)
│   ├── index.html
│   ├── index.js
│   ├── index.css
│   ├── themes.css          # Dark + light semantic tokens
│   └── EditorComponent.js  # The Mix editor
├── scripts/
│   └── setup-whisper.sh
└── data/                   # Local app data (gitignored)
    ├── settings.json
    ├── calls/              # Session JSON (optional custom path)
    └── temp_rec/           # Recording chunks
```

Configuration and the task database live under `~/.config/trailmix/` (XDG Base Directory).

---

## Configuration

Settings are stored in `data/settings.json` and exposed in **Nuts and Bolts**:

| Setting | Default | Description |
|---------|---------|-------------|
| `selectedModel` | `ggml-base.bin` | Whisper GGML model file |
| `selectedLlm` | `gemma3:1b` | Ollama model tag |
| `encryptByDefault` | `false` | Encrypt new sessions on save |
| `selectedMic` / `selectedSink` | `default` | PulseAudio devices |
| `selectedNoteStyle` | `executive` | Mix prompt preset |
| `customStoragePath` | *(empty)* | Override session storage directory |
| `userName` | *(empty)* | Used in diarization prompts |

Prompt templates support a `{transcriptText}` placeholder and can be edited per style in settings.

---

## Privacy & security

- **No network calls for AI** — Whisper and Ollama run entirely on localhost
- **Optional encryption** — sessions can be saved as AES-256-GCM blobs; passwords are not sent anywhere
- **Secure delete** — optional shredding of temporary audio chunks after processing
- **Local SQLite** — tasks and folder metadata stay in `~/.config/trailmix/db.sqlite`

---

## Architecture

```
┌─────────────┐     IPC      ┌──────────────────────────────────────┐
│  Renderer   │◄────────────►│  Main process                        │
│  (UI)       │              │  AudioCaptureService → chunk files   │
└─────────────┘              │  TranscriptionService → worker thread│
                             │  LLMInferenceService → Ollama (stream)│
                             │  EnhanceNotesService → Mix document   │
                             └──────────────────────────────────────┘
                                        │              │
                                   ffmpeg/pactl    whisper.cpp
                                   PulseAudio      (worker_threads)
```

Transcription runs in a Node `worker_threads` worker so ffmpeg conversion and whisper inference do not block the Electron main loop. LLM requests stream tokens back to the renderer via `llm:stream-chunk` IPC events.

---

## Development

```bash
# Run in development
npm start

# Package (requires electron-builder configuration)
npm run package
```

### Key IPC channels

| Channel | Description |
|---------|-------------|
| `audio:start-recording` / `audio:stop-recording` | Session lifecycle |
| `audio:on-transcription-update` | Live segment push to renderer |
| `chat:query` | Transcript-aware chat |
| `chat:mix-enhance` | Manual Jot & Enhance |
| `calls:save` / `calls:load` | Encrypted session persistence |
| `folders:*` | Campfire Preserves CRUD |
| `tasks:*` | Timeline action items |

See `preload.js` for the full renderer API surface.

---

## Troubleshooting

| Problem | Things to check |
|---------|-----------------|
| No system audio | Select the correct PulseAudio **monitor** sink in Nuts and Bolts; verify `pactl list sources` shows a `.monitor` device |
| Transcription empty | Confirm `bin/whisper.cpp/main` exists and the selected model is in `bin/whisper.cpp/models/` |
| LLM errors | Ensure `ollama serve` is running and the selected model is pulled |
| ffmpeg not found | Install ffmpeg and ensure it is on `PATH` |

---

## Changelog

See [CHANGELOG.md](./CHANGELOG.md) for release history.

---

## License

MIT — see [package.json](./package.json).

---

## Acknowledgments

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) — offline speech recognition
- [Ollama](https://ollama.com) — local LLM runtime
- [Electron](https://www.electronjs.org) — desktop shell
