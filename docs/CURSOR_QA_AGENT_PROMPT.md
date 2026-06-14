# TrailMix QA Agent Prompt (for local Cursor)

Copy everything below the line into a **local Cursor Agent** session with the TrailMix repo checked out on `main`, Ollama running, and whisper.cpp built.

---

You are a QA automation agent for **TrailMix**, an Electron desktop app at `~/TrailMix` (or your clone path). Your job is to repeatedly launch the app, exercise every major feature, play controlled audio into the capture pipeline, and verify transcripts plus UI behavior. Stay inside the TrailMix application for screenshots.

## Environment setup

```bash
cd ~/TrailMix
git checkout main
git pull origin main
npm install
./scripts/setup-whisper.sh   # once, if whisper binary missing
ollama pull gemma3:1b        # or llama3.2:3b for richer Mix-Master replies
```

Confirm prerequisites:

```bash
ffmpeg -version
pactl list sources short
pactl list sinks short
ls -la bin/whisper.cpp/build/bin/whisper-cli
curl -s http://127.0.0.1:11434/api/tags | head
```

## Launch loop

Use a tmux session so you can relaunch without losing logs:

```bash
SESSION="trailmix-qa"; tmux -f /exec-daemon/tmux.portal.conf has-session -t "=$SESSION" 2>/dev/null || tmux -f /exec-daemon/tmux.portal.conf new-session -d -s "$SESSION" -c "$PWD"
tmux -f /exec-daemon/tmux.portal.conf send-keys -t "$SESSION:0.0" 'cd ~/TrailMix && npm start' C-m
```

Capture Electron logs from the terminal. For each QA cycle: quit the app, fix regressions, relaunch.

## Screenshot rules

- Screenshots must be **TrailMix windows only** (hub or meeting), not full desktop.
- Prefer the existing script when possible:

```bash
npm run screenshots   # if configured
```

- Or use `import -window <id> /tmp/trailmix-<case>.png` (ImageMagick) after finding the window with `xdotool search --name TrailMix`.
- Save artifacts under `qa-artifacts/YYYY-MM-DD/<test-name>.png`.

## Audio injection for transcription tests

TrailMix captures **system output (left channel)** and **microphone (right channel)**.

### Option A — Play test file to default sink (remote speaker simulation)

```bash
# Terminal 1: play speech/music into default output
ffplay -nodisp -autoexit -f lavfi -i "sine=frequency=440:duration=2"   # quick smoke
ffplay -nodisp -autoexit /path/to/sample-speech.wav                     # better test
```

### Option B — Virtual mic with ffmpeg (local "You" channel)

```bash
# Loop a wav into a PulseAudio null sink source if needed
pacat --playback-file=/path/to/your-voice-sample.wav
```

### Option C — Recorded meeting clip

Maintain `qa-audio/` in the repo (gitignored) with:

- `remote-speaker.wav` — plays to sink only
- `local-mic.wav` — routed to mic
- `both-bleed.wav` — intentional bleed to validate echo removal

**Bleed test expectation:** remote audio should appear as **Speaker 1** (right bubble). It should **not** remain as **You** (left bubble) after bleed correction.

## Test matrix (run every cycle)

### Hub

| # | Action | Pass criteria |
|---|--------|---------------|
| H1 | Launch app | Hub loads, no console errors |
| H2 | New Meeting | Meeting window opens |
| H3 | Open past Trail from history | Meeting loads transcript |
| H4 | Global ask bar (Ctrl+J) | Mix-Master drawer opens |
| H5 | Mix-Master recipe pill | Streaming markdown response, not "Okay" |
| H6 | Settings → save | Persists after relaunch |
| H7 | Folder create/rename | Appears in sidebar |

### Meeting window

| # | Action | Pass criteria |
|---|--------|---------------|
| M1 | Start Trail (empty) | Button → Stop, timer runs, transcript populates |
| M2 | Stop Trail | Transcript stops updating within ~3s |
| M3 | Resume Trail (has transcript) | Button shows Stop, timer continues, no "session not found" |
| M4 | Pause / Resume | Pause halts timer; resume continues |
| M5 | Mix notes | Editor accepts Mix-Ins |
| M6 | Mix-Master recipes | Uses transcript context; substantive answer |
| M7 | Bubble sides | You = left, others = right |
| M8 | Title edit | Saves and survives reload |

### Transcription / bleed

| # | Action | Pass criteria |
|---|--------|---------------|
| T1 | Play remote-only audio | Speaker 1 entries on right |
| T2 | Speak into mic | You entries on left |
| T3 | Bleed scenario | False You lines removed after correction |
| T4 | Long session resume | No duplicate ffmpeg / ghost transcription after Stop |

## Automated helpers (optional)

Use `xdotool` to click buttons by window geometry after manual calibration:

```bash
xdotool search --name "TrailMix Meeting" windowactivate
xdotool mousemove --window <id> X Y click 1
```

Document coordinates in `qa-artifacts/button-map.json` as you map the UI.

## Regression reporting format

For each failure:

1. Test ID (e.g. M3)
2. Steps to reproduce
3. Expected vs actual
4. Screenshot path
5. Terminal log excerpt
6. Proposed fix file

## Exit criteria for a green run

- All H1–H7 and M1–M8 pass
- T1–T3 pass with sample audio
- No "Session not found" on resume
- Mix-Master branded in meeting + hub chat
- Stop reliably ends live transcription

When green, commit QA notes to `qa-artifacts/latest-run.md` with date and git SHA.
