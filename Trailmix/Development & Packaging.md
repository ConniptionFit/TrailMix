# Development & Packaging

> Local run, whisper setup, AppImage build, legacy recovery.

## Dev loop

```bash
# deps: Node 18+, ffmpeg, pulseaudio-utils, g++, cmake, Ollama
./scripts/setup-whisper.sh
npm install
npm start                 # electron .
node --check path/to.js   # quick syntax gate (no full test suite yet)
```

## Scripts / packaging

| Command | Result |
|---|---|
| `npm start` | Electron app |
| `npm run package` | Linux AppImage (`electron-builder`) |
| `npm run package:dir` | Unpacked dir |
| `npm run screenshots` | Capture script |

`package.json` `build.extraResources` copies `bin/whisper.cpp` into the AppImage. `appId`: `com.trailmix.app`.

Publish config points at GitHub `ConniptionFit/TrailMix` releases.

## Layout reminders

| Path | Dev meaning |
|---|---|
| `bin/whisper.cpp` | STT binary + models |
| `data/` | Local settings, calls, temp_rec (often gitignored content) |
| `~/.config/trailmix/db.sqlite` | User DB |
| `legacy/main.v0.4.0.js` | Pre-modular monolith snapshot |

## Git / agent workflow

- Feature branches: `cursor/<name>-477f`
- Prefer `main` as merge base
- After behavior changes: update vault notes per [[Master Prompt]] handoff map + `CHANGELOG.md` Unreleased
- Keep root `CLAUDE.md` ≡ [[Master Prompt]]

## Legacy recovery

| Artifact | Use |
|---|---|
| Tag `v0.4.0` | Checkout pre-modular |
| Branch `legacy/v0.4.0` | Same |
| `legacy/main.v0.4.0.js` | File-level reference |

## Related

[[Architecture]] · [[IPC & Modules]] · [[Troubleshooting]] · [[Settings & Configuration]]
