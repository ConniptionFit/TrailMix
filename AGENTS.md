# AGENTS.md

## Cursor Cloud specific instructions

TrailMix is a single **Electron desktop app** (not a web server). Standard commands
live in `README.md` and `package.json` `scripts` — this section only records
non-obvious caveats for running it in the Cursor Cloud VM. The VM snapshot already
contains the compiled `whisper.cpp`, pulled Ollama models, and installed system
packages; the startup update script only refreshes Node deps (`npm install`).

### Services / dependencies

- **Ollama (local LLM, port 11434)** — required for summaries, "The Mix", chat,
  diarization. It is **not** systemd-managed here (no systemd), so start it manually:
  `ollama serve` (this repo runs it in a tmux session). Model `gemma3:1b` is already
  pulled. Verify: `curl -s http://127.0.0.1:11434/api/tags`.
  - GOTCHA: the latest Ollama (0.31.x) **segfaults** on this VM's CPU during model
    warmup. It is pinned to **0.6.8** (`curl -fsSL https://ollama.com/install.sh | sudo OLLAMA_VERSION=0.6.8 sh`).
    Do not blindly upgrade Ollama or the LLM features will crash with
    `llama-server process has terminated: signal: segmentation fault`.
- **whisper.cpp (offline STT)** — prebuilt at `bin/whisper.cpp/build/bin/whisper-cli`
  with `ggml-tiny.bin` + `ggml-base.bin` in `bin/whisper.cpp/models/`.
  - GOTCHA: to rebuild (`./scripts/setup-whisper.sh`) you MUST force g++:
    `CC=gcc CXX=g++ ./scripts/setup-whisper.sh`. The default `c++` is clang, which
    selects gcc-14 (no `libstdc++` dev files) and fails with `cannot find -lstdc++`.
- **PulseAudio + virtual devices** — recording needs a running sound server, which
  the headless VM lacks by default. Start one with virtual devices:
  ```bash
  export XDG_RUNTIME_DIR=/run/user/$(id -u)
  pulseaudio -D --exit-idle-time=-1 --disallow-exit
  pactl load-module module-null-sink sink_name=trailmix_speaker sink_properties=device.description=TrailMix_Speaker
  pactl load-module module-virtual-source source_name=trailmix_mic source_properties=device.description=TrailMix_Mic
  ```
  The app's audio dropdowns only expose "Default Active Microphone/Output Monitor",
  which follow the PulseAudio system defaults — so the virtual devices above are used
  automatically. To feed audio into a live transcript, play into the sink:
  `paplay --device=trailmix_speaker <file>.wav` (it appears on `trailmix_speaker.monitor`).

### Running the app (GUI)

- Launch with `npm start` (which is `electron .`).
- To make the window visible to GUI/computer-use tooling, run it on the VNC desktop:
  `DISPLAY=:1 XDG_RUNTIME_DIR=/run/user/$(id -u) npm start`. Display `:1` is the
  TigerVNC/noVNC desktop; an isolated `xvfb-run` display (e.g. `:99`) is NOT visible
  to computer-use.
- Benign log noise on startup: `Failed to connect to the bus`, GPU/`viz_main` errors,
  and the Electron CSP security warning. These do not stop the app.

### Lint / test / build

- **No lint config and no automated test suite exist** (no ESLint/Prettier, no
  jest/vitest/mocha, no `test`/`lint` npm scripts). Do not fabricate them.
- **Build**: `npm run package:dir` (unpacked) or `npm run package` (AppImage) via
  electron-builder → outputs to `dist/` (gitignored). First build downloads the
  Electron binary (~106 MB).
