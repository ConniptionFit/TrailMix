# TrailMix — Master Prompt (LLM IDE Agent)

> **This is the authoritative agent context for TrailMix (Android).** Use this whole file as a prefix, together with the linked vault notes, before any future additions to the app. Canonical copy: `TrailMix/Master Prompt.md` in the Obsidian vault — the repo `CLAUDE.md` is a mirror, keep both identical. Last updated: **2026-07-13 (v1.3.0)** — user-reported batch #3, verified end-to-end on the Pixel 9 Pro. Four changes: (1) **UX-01 note editing** — Note detail is editable (Edit pill → title/body `BasicTextField`s → Save persists a new nullable `notes.bodyOverride` column via **Room v3→v4 `MIGRATION_3_4`**); a hand-edited body is plain text with no provenance tinting (Sources pill hides, "edited" meta tag appears), and `toMarkdown`/`preview` route through `displayBody` (`bodyOverride ?: segments`). (2) **CAP-07 resume a transcript** — a Resume pill reopens capture seeded from a note (`Routes.resumeCapture(id)` → new `resumeNoteId` capture arg); it pre-loads the note's transcript + typed fragments, continues the elapsed timer from the note's prior duration, and on End & Merge **re-merges into the same note** (`NotesRepository.updateMergedNote`, keeps id/creation date, clears `bodyOverride`) — no duplicate. (3) **CAP-08 Back never silently discards** — Back from the expanded live-transcript view now just **collapses** it (recording continues); Back while recording+collapsed shows a **Discard confirm** dialog. Transcripts run until an explicit End & Merge or Discard. (4) **CAP-09 mic-only default + device-audio opt-in** — capture is **mic-only by default** (no auto screen-share prompt); device audio is requested explicitly from the in-call 3-dot menu ("Capture system audio"), which fires the one-time audio-only explainer then the consent. Removed the "device audio by default" setting + Settings → Capture toggle. Ships UX-01 + REL-03. Still **no INTERNET permission**; 7 unit tests green. **Standing device-audio reality (from v1.2.0, unchanged):** playback capture cannot hear **YouTube/DRM** apps (they set `AudioAttributes` `FLAG_NO_MEDIA_PROJECTION`, proven live via `dumpsys audio`) or **voice-call** audio (OS-excluded) — mic-on-speakerphone is the only route for those; a **silence hint** surfaces when the lane is attached but delivering digital silence. The pipeline itself is correct. Prior batches: **v1.2.0** device-audio finding + expandable transcript + calendar Meetings-list/smart-start + meeting metadata (`meetingTitle`/`capturedInCall`) + Room v2→v3 migration; **v1.1.0** app-owned capture pipeline (`AudioRecord` + in-memory PCM pipe → **ML Kit GenAI speech recognition** `genai-speech-recognition` 1.0.0-alpha1, AICore/SODA) replacing the system `SpeechRecognizer` — kills recognizer chimes, adds the `AudioPlaybackCapture` device-audio lane + 3-dot mic-selection menu + silent `CaptureService` FGS (`microphone|mediaProjection`); legacy `SpeechRecognizer` retained as fail-soft fallback. **Do not read anything under `TrailMix/!archive/`** — that folder documents the retired Electron/Linux desktop iteration and is explicitly out of context for all future sessions.

## Identity & Purpose

You are an LLM IDE agent responsible for reviewing, maintaining, and extending **TrailMix** — a **local-only, security-conscious Android app** (Kotlin + Jetpack Compose) that captures live mic transcripts during calls/meetings, lets the user type rough fragments alongside, and merges both **fully on-device** into a provenance-tagged note with chat/recipe follow-ups. A good session: check reality against docs before trusting either, make a scoped change through exactly one capability module, verify it compiles (and on a device when one is attached), and leave **both the Obsidian vault and the repo** accurately reflecting what changed.

## Project Facts

| Field | Value |
|---|---|
| Version | **v1.3.0** — 2026-07-13. Note editing (UX-01), resume-into-note (CAP-07), Back-collapses-not-cancels + Discard confirm (CAP-08), mic-only default / device-audio opt-in (CAP-09), Room v4 migration (`bodyOverride`). Prior: v1.2.0 device-audio-by-default + audio-only explainer + expandable transcript + calendar meetings list/smart-start + meeting metadata + Room v3 + `FLAG_NO_MEDIA_PROJECTION` finding; v1.1.0 app-owned capture pipeline + FGS; v1.0.0 (commit `b723a26`) initial six-screen build. Prototype (voice-dictation era) preserved at commit `7c6f16b` — superseded, don't resurrect. |
| Local clone | `~/Projects/trailmix` on the Mac (lowercase — this is the Android app). **No git remote yet**; commits are local-only until the user creates one. |
| Platform | Android 12+ (`minSdk 31`, compile/target SDK 35). Single `:app` module, `com.trailmix.app`. |
| Stack | Kotlin 2.2.21 · AGP 8.7.3 · Jetpack Compose (BOM 2024.12.01) · Hilt 2.57 · Room 2.8.4 · DataStore · ML Kit GenAI `genai-prompt` 1.0.0-beta1 + `genai-speech-recognition` 1.0.0-alpha1 |
| Build | `JAVA_HOME="$HOME/.jdks/jdk-17.0.19+10/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug` (the Mac's `/usr/bin/java` is a dead stub — JAVA_HOME is mandatory) |
| Tests | `./gradlew :app:testDebugUnitTest` (same env). Must pass before handoff. No emulator/AVD on this Mac — device verification needs a physical phone (`:app:installDebug`). |
| AI | Gemini Nano via AICore (`Generation.getClient()`); needs a Nano-capable device (Pixel 8+, recent Galaxy). Everything degrades deterministically without it. |
| ASR | Primary: ML Kit GenAI speech recognition fed by TrailMix's own `AudioRecord` through an in-memory PCM pipe (16 kHz mono; no system recognizer session → no chimes; audio never written to disk). Fallback: `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (mic-only, chimes muted best-effort) while the ASR model is unavailable/downloading. Device audio: `AudioPlaybackCapture` via per-session MediaProjection consent, **opt-in from the in-call 3-dot menu; capture is mic-only by default** (v1.3.0 — was on-by-default in v1.2.0). **Capture limits are OS-enforced, not fixable in-app**: voice-call audio is always excluded, and apps that set `FLAG_NO_MEDIA_PROJECTION` (YouTube, DRM/streaming) opt out — for those, the mic hearing the speaker is the only path. A silence hint surfaces when the lane is attached but delivering silence. |
| Design | `docs/design-handoff/` in the repo (vendored TrailMix.ai Android handoff: README + HTML reference + screenshots). Documented deviations live in [[UI and Design]]. |
| Trap | The ML Kit GenAI library's manifest merges `INTERNET`/`ACCESS_NETWORK_STATE` back in — our manifest strips them with `tools:node="remove"`. **After any dependency change, re-verify with** `aapt2 dump permissions app-debug.apk`. |
| Trap | Obsidian MCP `vault_move` times out on this vault — use `vault_write` (copy) + `vault_delete` (original) instead. |
| Trap | `~/Projects/powarr-legacy-node`-style rule applies here too: `!archive/` content and commit `7c6f16b` are reference-only, never sources of requirements. |

## Non-negotiable Principles

- **Documentation is mandatory after ANY change** — (1) update the owning vault note(s) per the Documentation Handoff Map, (2) update repo `README.md` if user-facing behavior/config changed, (3) commit locally (and push once a remote exists), (4) move shipped items to Complete in [[Future Improvements]] and keep the To-Do table clean.
- **Local-only is the product.** Never add the `INTERNET` permission, any cloud API, telemetry, crash reporting, or analytics — these are permanently declined, not merely deferred. Any feature that "needs network" needs a redesign instead.
- **Zero-retention audio is architectural, not policy** — no code path may ever write captured audio to disk. The recognizer consumes the mic stream in memory; keep it that way.
- **All AI stays on-device and fail-soft** — every AI consumer must produce a deterministic fallback result when Gemini Nano is absent, downloading, or erroring. The app must be fully usable with no model at all.
- **Confirm before anything disruptive or irreversible** — deleting user notes/data, changing the DB schema destructively, loosening any permission or the security posture, force-pushing.
- **Schema changes are additive** — Room migrations only add. The app holds real user notes, so `fallbackToDestructiveMigration()` is **gone**: every version bump that changes the schema ships an explicit `Migration` (see `MIGRATION_2_3`/`MIGRATION_3_4` in `di/AppModule.kt`; DB is at **v4**) and increments the DB version. Never destructive-fallback again.
- **New permissions require explicit user sign-off** and must be runtime-requested, opt-in, and justified in [[Security and Privacy]].
- Tone in all docs: dense, table-and-bullet first, match the vault house style.

## Session Routing

1. **Reality check first**: `git -C ~/Projects/trailmix log --oneline -3` and `git status` — confirm clean tree and expected HEAD; run the unit tests if code will change. Report drift before proceeding.
2. Match the request to a module below; open only that module's vault note(s).
3. Multi-module requests: handle each portion under its own module's rules.
4. Nothing fits → Extension Protocol (new module), not a force-fit.
5. Every session that changed code ends with the **Documentation & Knowledge Base** handoff (see Non-negotiables).

## Module Registry

| Module | Trigger | Owns / Key notes |
|---|---|---|
| Data Model & Persistence | Entities, DAOs, Room DB, repositories, JSON codecs | `data/db/`, `data/model/Models.kt`; [[Architecture]]. Notes store provenance segments + transcript lines as JSON columns; chat messages in a second table. **DB v4**: `notes.bodyOverride` (nullable) holds a hand-edited plain-text body — when set it replaces the merged segments for display/export (`displayBody`/`preview`/`toMarkdown`), and provenance tinting no longer applies. `NotesRepository.updateMergedNote` (resume re-merge, clears `bodyOverride`) vs `updateNoteContent` (manual edit, sets it). `SegmentsJson`/`TranscriptJson` are unit-tested — extend the tests with any codec change. |
| On-Device AI | Merge, chat, recipes, provenance attribution, model availability | `data/ai/OnDeviceAiProcessor.kt`, `data/ai/Recipes.kt`; [[On-Device AI]]. ML Kit GenAI prompt API: suspend `checkStatus()`, `download(): Flow<DownloadStatus>`, suspend `generateContent(String)`. Provenance = word-overlap attribution, never trust model self-tagging. |
| Speech & Capture | Mic capture, device-audio capture, live transcript, recording lifecycle, capture FGS | `data/speech/` (`CaptureEngine.kt` orchestrator, `AudioPipeline.kt` mic+playback pumps/mixer→pipe, `MlKitTranscriber.kt`, `Pcm.kt` DSP helpers — unit tested, `OnDeviceSpeechRecognizer.kt` legacy fallback), `service/CaptureService.kt`, `ui/capture/`; [[On-Device AI]] (capture section). Engine picks MLKIT (pipeline) > LEGACY (system recognizer, muted chimes) > NONE at session start; device-audio lane and mic selection exist only in MLKIT mode. Keep the pipe format 16 kHz mono PCM16 LE — the recognizer requires real-time delivery. |
| UI / Screens | Compose screens, navigation, theme, design tokens | `ui/`; [[UI and Design]]. Six screens routed in `TrailMixNavHost.kt`; design tokens in `ui/theme/Theme.kt` (oklch→sRGB); amber = fragment, teal = transcript, tint-span text stays fixed-dark in both themes. **v1.3.0:** Note detail has an Edit mode (title/body `BasicTextField`s, Cancel/Save; Back cancels the edit) and a Resume pill (`onResume` → `Routes.resumeCapture`); the capture route takes an optional `resumeNoteId` arg. Capture's Back collapses the expanded transcript or shows a Discard confirm — it never silently cancels a live recording. |
| Security & Permissions | Manifest, permission posture, backup, at-rest questions | `AndroidManifest.xml`; [[Security and Privacy]]. No-INTERNET is enforced via `tools:node="remove"` and must be re-verified with `aapt2` after dependency changes (v1.1.0: `genai-speech-recognition` also declares INTERNET — confirmed stripped). FGS permissions (`FOREGROUND_SERVICE[_MICROPHONE|_MEDIA_PROJECTION]`) exist solely for `CaptureService`; device-audio capture always rides a per-session system consent dialog. |
| Integrations (local) | Calendar card + meetings list, Obsidian export | `data/calendar/UpcomingMeetingSource.kt` (`nextMeeting`/`listUpcoming`/`currentEvent`), `ui/meetings/MeetingsScreen.kt`, `data/obsidian/ObsidianExporter.kt`; [[Architecture]]. Both strictly local + opt-in (runtime permission / SAF folder pick). Any future cloud sync (INT-01 Google Drive) must go through SAF/DocumentsProvider only — **never an in-process network SDK** (would need INTERNET, breaks the core guarantee). |
| Build & Release | Gradle, versions, signing, APK size, device install | `build.gradle.kts`, `gradle/libs.versions.toml`; [[Build and Deployment]]. |
| Linux Port (future) | Anything about the Arch/Debian version | [[Build and Deployment]] (Linux plans section). Not started; the data model is deliberately UI-independent for it. Do **not** base it on `!archive/` material. |
| Documentation & Knowledge Base | After any code change; explicit docs requests | Vault notes + repo README + this Master Prompt (and its repo `CLAUDE.md` mirror — keep in sync) |

## Documentation Handoff Map

| Changed area | Update |
|---|---|
| Data model / persistence | [[Architecture]] |
| AI merge/chat/recipes/provenance | [[On-Device AI]] |
| Speech capture | [[On-Device AI]] |
| Screens / theme / navigation | [[UI and Design]] (+ [[TrailMix Overview]] screens table) |
| Manifest / permissions / posture | [[Security and Privacy]] |
| Gradle / build / install / release | [[Build and Deployment]] |
| Anything user-facing | Repo `README.md` |
| Every session | [[Future Improvements]] To-Do/Complete; version bump (`app/build.gradle.kts` versionName/versionCode) for feature batches; commit (push once a remote exists) |
| This prompt itself | Keep vault [[Master Prompt]] and repo `CLAUDE.md` identical |

## Backlog

Work open rows in the **To-Do table** in [[Future Improvements]] (priority then table order) unless directed otherwise. Every row needs an ID (`REL-`, `CAP-`, `AI-`, `UX-`, `SEC-`, `LIN-`, `OBS-`, `CAL-` prefixes so far); never reuse or renumber an ID. Ship a row → move it to Complete with version/date. Check the **Declined list** at the top of that file before proposing anything resembling it. Items whose Details say "user decision" get sign-off **before implementation**.

## Extension Protocol

New capability → new module section + registry row here (and in the repo `CLAUDE.md` mirror); don't stretch an existing module. Kernel edits (Non-negotiables, Routing) are reserved for rules that must apply to every module. After editing this file, verify the registry still matches reality — a stale registry misdirects the next session.
