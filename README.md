# TrailMix

Local-only, security-conscious call/meeting note-taking for Android. Capture a live
transcript from the mic (no bots, no meeting required), type rough fragments during the
call, then merge both into a structured note with **fully on-device AI** — and see
exactly which sentence came from which source.

Built from the TrailMix.ai Android design handoff, but with a fundamentally different
data architecture than the cloud product it resembles:

| | TrailMix.ai (cloud) | This app |
|---|---|---|
| ASR | Deepgram/AssemblyAI (cloud) | ML Kit GenAI on-device speech recognition (AICore), fed by the app's own audio pipeline; system on-device recognizer as fallback |
| LLM | OpenAI/Anthropic (cloud) | Gemini Nano via ML Kit GenAI (AICore) |
| Audio retention | Temp cloud cache, deleted post-transcription | **Never written anywhere** — RAM-only PCM pipe into the recognizer |
| Network | US-hosted AWS VPC | **No `INTERNET` permission in the manifest** |
| Training opt-out toggle | Yes | Not needed — nothing leaves the device |

## Security posture

- **No network permission.** The OS itself prevents this app from transmitting anything.
- **Zero-retention audio.** No audio file is ever created; captured PCM flows through an
  in-memory pipe straight into the on-device recognizer. There is nothing to delete
  because nothing is stored.
- **On-device recognition only.** Primary: ML Kit GenAI speech recognition via AICore.
  Fallback: `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31+) — never the
  network-capable system recognizer.
- **Device-audio capture is consent-gated.** Capturing other apps' audio requires the
  system screen-share dialog every session; Android structurally excludes voice-call
  audio (e.g. the far end of Teams/Zoom) from app capture.
- **On-device LLM only.** Merge, chat, and recipes run on Gemini Nano through AICore.
  Every AI feature has a deterministic fallback — the app works with no model present.
- **`allowBackup="false"`** — notes don't leave the device via cloud backup.
- **Opt-in calendar.** `READ_CALENDAR` is requested only when you tap the Upcoming card,
  and is read-only.
- Optional **Obsidian export** writes Markdown into a folder you pick (SAF) — local disk only.

## Screens

1. **Home** — notes list, one upcoming meeting (opt-in calendar), amber FAB to start capture.
2. **Live capture** — recording status, live transcript preview, free-typing fragment area,
   red *End & Merge* button. A 3-dot menu (upper right) picks the input mic (built-in,
   Bluetooth buds, USB — switchable mid-session), toggles **capture device audio**
   (transcribe videos/media other apps play, after the system consent dialog), and jumps
   to the system output panel. Capture runs in a silent foreground service, so it
   survives switching to the meeting or video app. The app plays **no notification
   sounds whatsoever** — no recognizer chimes, and its one notification channel is muted.
3. **Note detail** — the merged note; amber tint = from your typed fragments, teal tint =
   from the transcript. *Sources shown* pill toggles provenance tinting (on by default
   after a merge).
4. **Transcript** — full-screen, timestamp-labeled lines (no speaker diarization on-device yet).
5. **Chat & Recipes** — chat about the note; recipe chips (Follow-up email, Create ticket,
   Summarize, Action items) are saved prompts.
6. **Settings** — dark mode (follows system until overridden), privacy disclosure,
   optional Obsidian vault link.

## Requirements

- JDK 17+, Android SDK 35
- Device on Android 12+ (API 31, for guaranteed-on-device speech recognition)
- For AI merge/chat: a device with AICore / Gemini Nano (Pixel 8+, recent Galaxy, etc.).
  Everything else works without it via the deterministic fallback.

## Build

```bash
export JAVA_HOME="$HOME/.jdks/jdk-17.0.19+10/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug     # APK
./gradlew :app:installDebug      # install on a connected device
```

## Architecture

- Kotlin + Jetpack Compose + Hilt + Room + DataStore. Single `app` module.
- `data/ai/OnDeviceAiProcessor` — Gemini Nano merge/chat with word-overlap provenance
  attribution (robust even when the small model ignores tagging instructions) and a
  deterministic no-AI fallback.
- `data/speech/` — app-owned capture pipeline: `AudioPipeline` (mic `AudioRecord` +
  optional `AudioPlaybackCapture` lane, mixed to 16 kHz mono PCM in an in-memory pipe),
  `MlKitTranscriber` (ML Kit GenAI ASR over that pipe), `CaptureEngine` (backend
  selection + routing), `OnDeviceSpeechRecognizer` (legacy fallback).
- `service/CaptureService` — silent foreground service (`microphone|mediaProjection`)
  keeping capture alive across app switches.
- `data/db` — notes store provenance-tagged segments + transcript lines as JSON columns;
  chat messages per note in a second table.
- `ui/theme/Theme.kt` — design tokens from the handoff (oklch → sRGB), light/dark with a
  persisted manual override.

## Linux plans (Arch/Debian)

The near-term Linux port will not share this Android UI. The plan is to keep the same
product shape (capture → merge → provenance note → recipes) with a native stack:
whisper.cpp or sherpa-onnx for streaming ASR and llama.cpp for the merge/chat model,
which run well on both Arch and Debian. The data model (provenance segments, transcript
lines, recipes) is deliberately UI-independent so it can be ported directly.

## Known deviations from the design handoff

- The Settings privacy copy replaces the handoff's "SOC 2 Type II · GDPR" line (true of
  the cloud service, not of a local app) with the app's actual guarantees.
- An Obsidian-export section was added to Settings (carried over from the prototype);
  the privacy section itself stays disclosure-only per the handoff.
- Transcript lines are labeled with capture timestamps instead of speaker names —
  on-device diarization isn't available yet.
