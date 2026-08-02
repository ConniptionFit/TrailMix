# TrailMix

Local-only, security-conscious call/meeting note-taking. Capture a live transcript from
the mic (no bots, no meeting required), type rough fragments during the call, then merge
both into a structured note with **fully on-device AI** — and see exactly which sentence
came from which source.

**The product is the privacy posture**: no network permission (OS-enforced on Android),
no audio files ever written to disk, no accounts, no telemetry, no cloud anything.

## Platform status

| Platform | Status | Location |
|---|---|---|
| **Android** | Shipping, actively developed (Kotlin + Jetpack Compose, minSdk 31) | [`Android/`](Android/) |
| Linux (Arch + Debian) | Planned, not started | Will land as a sibling folder (e.g. `Linux/`) alongside `Android/` when work begins — same product shape and data model (provenance segments, transcript lines, recipes), different native stack (candidate: whisper.cpp/sherpa-onnx + llama.cpp) |

This repo is structured for multiple platform implementations to live side by side as
top-level folders, each a self-contained project. Only `Android/` exists today.

## Install (Android)

There is **no Play Store distribution** — TrailMix is sideloaded. It deliberately has
**no INTERNET permission**, so it can't update itself; distribution and updates go
through GitHub Releases.

### Recommended: install + auto-update via Obtainium

Signed APKs are published on the [Releases page](https://github.com/ConniptionFit/TrailMix/releases).
The easiest way to stay current without the app ever touching the network is
[**Obtainium**](https://github.com/ImranR98/Obtainium) — a free, open-source app that
watches a GitHub repo and installs new releases:

1. Install Obtainium (from F-Droid or its own GitHub Releases).
2. **Add App** → paste `https://github.com/ConniptionFit/TrailMix` → Obtainium finds the
   APK asset and installs it.
3. It notifies you (and can auto-install) whenever a new TrailMix release is cut. TrailMix
   itself never gains network access — Obtainium does all the fetching.

Or just download the latest `.apk` from Releases and install it by hand (you'll need to
allow your file manager to install unknown apps).

> **Upgrading from v1.9.0 or earlier requires an uninstall, which erases your notes.**
> Releases up to v1.9.0 were signed with a per-machine *debug* key, so they can't be
> upgraded in place. v1.10.0 switches to a stable release key; from v1.10.0 onward,
> updates install cleanly with no data loss. Before uninstalling anything, set
> **Settings → Export location** — TrailMix then writes every note out as Markdown, so a
> reinstall costs you nothing.

### Build from source

**Prerequisites:**
- JDK 17
- Android SDK (platform 35, build-tools) — via Android Studio or the standalone
  command-line tools
- A device on Android 12+ (API 31) for guaranteed on-device speech recognition, or an
  emulator image; `adb` for installing to a physical device
- For AI merge/chat/structured summaries: a device with AICore / Gemini Nano (Pixel 8+,
  recent Galaxy, etc.). Everything else works without it via a deterministic fallback.

**Build:**

```bash
cd Android
# macOS
export JAVA_HOME="$HOME/.jdks/jdk-17.0.19+10/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
# Linux
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20+8"
export ANDROID_HOME="$HOME/Android/Sdk"

./gradlew :app:assembleDebug
```

This produces `Android/app/build/outputs/apk/debug/app-debug.apk`.

**Install to a connected/authorized device:**

```bash
cd Android
./gradlew :app:installDebug
```

or, with the APK already built:

```bash
adb install -r Android/app/build/outputs/apk/debug/app-debug.apk
```

**Run unit tests:**

```bash
cd Android
./gradlew :app:testDebugUnitTest
```

## Security posture

- **No network permission.** The OS itself prevents this app from transmitting anything.
- **Zero-retention audio.** No audio file is ever created; captured PCM flows through an
  in-memory pipe straight into the on-device recognizer. There is nothing to delete
  because nothing is stored. The crash journal below is no exception — it records
  **recognized text only**, never audio.
- **On-device recognition only.** Primary: ML Kit GenAI speech recognition via AICore.
  Fallback: `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31+) — never the
  network-capable system recognizer.
- **Device-audio capture is consent-gated.** Capturing other apps' audio requires the
  system screen-share dialog every session; Android structurally excludes voice-call
  audio (e.g. the far end of Teams/Zoom) from app capture.
- **On-device LLM only.** Merge, chat, and recipes run on Gemini Nano through AICore.
  Every AI feature has a deterministic fallback — the app works with no model present.
- **`allowBackup="false"`** — notes don't leave the device via cloud backup.
- **Crash journal.** A capture in progress is written to an append-only journal in
  app-private storage (`filesDir`), so an app crash or a low-memory kill can't take an
  unsaved transcript with it — the next launch offers it back. It holds the same
  recognized text a saved note would, under the same app-private protection as the
  database, and it is deleted the moment the capture is saved or discarded.
- **Opt-in calendar.** `READ_CALENDAR` is requested only when you tap the Upcoming card,
  and is read-only. The same grant covers reading attendee names for a matched meeting —
  no separate permission — and those names never leave the device.
- Optional **Export location** writes note Markdown into a folder you pick (SAF), and
  only ever writes locally. One honest nuance, disclosed in-app: if the folder you pick
  is one another app syncs to the cloud (a Drive folder, say), that app may upload the
  files — TrailMix itself never does, and its `INTERNET` permission stays absent.

## Screens (Android)

1. **Home** — notes list (every row shows its creation date & time, and calendar-linked
   notes get a **Meeting** tag), one upcoming meeting (opt-in calendar), a **search bar**
   (keywords or dates — "jul 18", "7/18/2026", and "2026-07-18" all work), a **Meetings**
   filter chip, and an amber FAB to start capture. While a capture is running — even after
   you've navigated away — an amber *Recording · mm:ss* chip appears here (and on Note
   detail, so it's never far away); tap it to jump straight back into the live session
   (recording keeps running in the background the whole time). **Press and hold a note**
   for a context menu: **Select multiple** (selection mode with bulk Delete and bulk
   Share), Delete (also removes the exported copy), and Share. Deleting is safe: notes
   move to **Recently deleted** (an entry row appears at the bottom of the list) and stay
   restorable for 1 day before being removed for good — open it to restore a note or
   delete it immediately. If TrailMix ever dies mid-capture (a crash, or Android killing
   it for memory while you're in another app), the next launch says so here and offers
   the transcript back: **Continue capture**, **Save as note**, **Later**, or Discard.
   Nothing is lost in the meantime — the transcript is written to disk as it's recognized,
   not held in memory until you press End & Merge.
2. **Live capture** — recording status, live transcript preview, free-typing fragment area,
   red *End & Merge* button. Tap the live-transcript card to **expand** it into a
   scrolling view of recent lines. A 3-dot menu (upper right) picks the input mic
   (built-in, Bluetooth buds, USB — switchable mid-session), turns on **Capture system
   audio**, and jumps to the system output panel. Capture runs in a silent foreground
   service, so it survives switching to the meeting or video app. The app plays **no
   notification sounds whatsoever** — no recognizer chimes, and its one notification
   channel is muted. **Back never throws away a live recording:** on the expanded
   transcript it just collapses the view, and while recording it asks before discarding —
   a transcript keeps recording until you *End & Merge* or explicitly discard.

   **Capture is mic-only by default.** Device (system) audio is opt-in: turn it on from the
   3-dot menu, and a one-time explainer clarifies that Android's screen-share prompt grants
   TrailMix the audio stream only — never your screen. Important limits, both enforced by
   Android and not fixable in-app: **voice-call audio is never capturable** (the far end of
   a Teams/Zoom call), and apps that opt out of capture — **YouTube and most DRM/streaming
   apps set this flag** — can't be captured either. For those, put the call/video on
   speakerphone and let the mic hear it. The device-audio lane works for games, many
   browsers, and podcast apps that permit capture; a hint appears in-app when it's attached
   but hearing silence. A **chevron-back icon** in the top-left corner lets you go back to Home
   without stopping the recording — different from system Back, which still confirms
   before discarding. You can also freely navigate into any other note while a capture
   is running in the background — nothing about it depends on the Capture screen staying
   open. The 3-dot menu also has a **"What can be captured?"** help sheet summarizing all
   of the above limits honestly, in-app. Above *End & Merge*, a template row (Flat / 1:1 /
   Weekly Standup / Learning / **Conference talk** / User Interview, plus any **custom
   templates** you've saved in Settings) steers how the summary is structured for this
   capture. Pick **Conference talk** for a talk or presentation: it tells the summarizer
   that one person is speaking to an audience, so the speaker's instructional phrasing
   ("you should…", "let's look at…") is kept as content instead of being mistaken for your
   to-do list. Unlike the other templates, the built-in styles now also steer the
   **no-AI** path, so the choice matters even on a device without Gemini Nano. Ending a
   capture with **nothing typed and nothing transcribed saves no note at all**, and a
   typed-only capture is saved verbatim without invoking the AI.
3. **Note detail** — the merged note; amber tint = from your typed fragments, teal tint =
   from the transcript. *Sources shown* pill toggles provenance tinting (on by default
   after a merge). The meta line shows the meeting name and an in-call tag when the
   capture ran during a calendar event or phone call, and an attendee list when the
   calendar event had one. **Edit** the title and body inline (a hand-edited note becomes
   plain text — provenance tinting no longer applies — and is marked *edited*). **Resume**
   reopens capture seeded with this note's transcript and fragments, and re-merges back
   into the same note when you finish. A **Share** icon sends the note's Markdown export
   through the standard Android share sheet. Notes are shown as a **structured summary** —
   **Highlights**, expandable/collapsible **topic sections**, and an **Action Items**
   checklist (owner/deadline when statable) — rather than a flat block of text. When the
   on-device AI is available it groups the content by topic; when it isn't (or returns
   something unusable), a rule-based fallback still lays the note out as *Your notes*
   bullets plus topic sections and a detected *Action Items* list, so the default is
   readable either way. Tap the small "ⓘ" next to any bullet to see the transcript/fragment
   sentence it came from, and use **Reorder sections** to move topic sections up/down (the
   new order persists and carries into exports). Very short notes stay as plain text.
   - **Long sessions are summarized end to end.** A talk or presentation is divided into
     time windows, each becoming its own section headed by its range and topic (e.g.
     `15:48 – 23:40 · routing, table`), so the last half hour of a conference session is
     represented as well as the first ten minutes.
   - **Every bullet says where it came from.** With *Sources shown* on, each bullet's marker
     is tinted (amber = your typed notes, teal = spoken) with a legend above the note, and
     spoken bullets lead with the `mm:ss` they were said at, so you can jump back to that
     moment in the transcript. The exported Markdown carries the same annotation as
     `` **`[you]`** `` / `` **`[12:30]`** `` tags plus a one-line key — turning the pill off
     removes both the tinting and the export tags.

### Exported notes are built to be handed to another tool

Each note exports as **two files**: the summary, and a companion `<name>.transcript.md`
holding the verbatim record. They link to each other, so the summary stays short enough to
paste into another LLM as context without a 45-minute transcript eating the whole window —
and the transcript can be moved, archived, or parsed separately whenever you want it.

The summary carries YAML frontmatter (`title`, `date`, `duration`, `meeting`, `attendees`,
`topics`, `action_items`, a link to the transcript, `tags`), then a `TL;DR` callout, your own
typed notes, time-windowed `Key points`, and `- [ ]` action items. That means it's queryable
from Obsidian Dataview, scannable on a phone, and unambiguous to a model reading it cold.
   - **Upcoming meetings** — tap the calendar label on Home to see the next 7 days; tap a
     meeting to capture it (a meeting more than 5 minutes out asks first).
4. **Transcript** — full-screen, timestamp-labeled lines (no speaker diarization on-device
   yet). A Share icon sends the raw transcript through the Android share sheet.
5. **Chat & Recipes** — chat about the note; recipe chips (Follow-up email, Create ticket,
   Summarize, Action items, plus any **custom recipes** you've saved in Settings) are
   saved prompts. Chat knows the meeting attendees, so it can answer things like "what
   did Charlie say I need to do." Every assistant reply has a **Copy** button, and the
   latest output of each recipe is included in the note's Markdown export as a
   *Recipe Outputs* section.
6. **Settings** — dark mode (follows system until overridden), privacy disclosure, a
   **Speech recognition language** picker, an optional **Export location** (pick any
   folder via the system picker; changing it moves your already-exported files over
   automatically, and an **Open folder** button jumps to it), **Built-in and Custom
   Recipes** (tap any recipe to see the exact prompt it runs; create/edit/delete your own),
   **Built-in and Custom summary templates** (tap any template to see the exact guidance
   it adds to the AI's structuring prompt, and add your own — e.g. a "Sales call" template
   preferring Customer Needs / Objections / Pricing / Next Steps sections), and a
   **Default summary template** (custom templates selectable there and on Capture too).

## Architecture (Android)

- Kotlin + Jetpack Compose + Hilt + Room + DataStore. Single `app` Gradle module under `Android/`.
- `data/ai/OnDeviceAiProcessor` — Gemini Nano merge/chat with word-overlap provenance
  attribution (robust even when the small model ignores tagging instructions) and a
  deterministic no-AI fallback.
- `data/speech/` — app-owned capture pipeline: `AudioPipeline` (mic `AudioRecord` +
  optional `AudioPlaybackCapture` lane, mixed to 16 kHz mono PCM in an in-memory pipe),
  `MlKitTranscriber` (ML Kit GenAI ASR over that pipe, user-selectable locale via
  `AsrLocales`), `CaptureEngine` (backend selection + routing),
  `CaptureSessionManager` (process-scoped session owner, survives navigation away from
  the capture screen), `OnDeviceSpeechRecognizer` (legacy fallback).
- `service/CaptureService` — silent foreground service (`microphone|mediaProjection`)
  keeping capture alive across app switches.
- `data/db` — notes store provenance-tagged segments + transcript lines as JSON columns;
  chat messages per note in a second table, plus a per-note tracked export URI for
  update-in-place, cascade-delete, and export-location migration.
- `data/export/NoteExporter.kt` — writes note Markdown into the configured Export
  location, over the shared SAF logic in `data/export/MarkdownExportWriter.kt`.
- `ui/theme/Theme.kt` — design tokens from the design handoff (oklch → sRGB), light/dark
  with a persisted manual override.

## Linux plans (Arch/Debian)

Planned near-term, not yet started. The Linux port will not share the Android UI. The
plan is to keep the same product shape (capture → merge → provenance note → recipes)
with a native stack: whisper.cpp or sherpa-onnx for streaming ASR and llama.cpp for the
merge/chat model, which run well on both Arch and Debian. The data model (provenance
segments, transcript lines, recipes) is deliberately UI-independent so it can be ported
directly. It will land as its own top-level folder alongside `Android/`, not inside it.

## Known deviations from the Android design handoff

- The Settings privacy copy replaces the handoff's "SOC 2 Type II · GDPR" line (true of
  a hypothetical cloud service, not of a local app) with the app's actual guarantees.
- An Export location section (plus, as of v1.7.0, a Custom Recipes section) was added
  to Settings; the privacy section itself stays disclosure-only per the handoff.
- Transcript lines are labeled with capture timestamps instead of speaker names —
  on-device diarization isn't available yet.
