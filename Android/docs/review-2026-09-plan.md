# TrailMix full review — execution plan

> **Self-contained handoff.** This file is the complete brief for a fresh Claude Code session (GUI or CLI) on a machine that has the Android SDK, `adb`, and the Pixel 9 Pro attached. It was produced in a cloud session on 2026-09-18 that could read the repo but could not build, test, or reach a device; every finding below is from reading and must be confirmed by the baseline in Phase 0. Appendix A carries the file:line detail from the three exploration passes so no other document is needed.

## How to start the new session

1. Open the repo clone on the workstation: `~/Projects/trailmix-android` (Mac: `~/Projects/trailmix`). Working tree must be clean.
2. `git fetch origin && git checkout claude/trailmix-full-review-sprn28` (branch exists on origin at `19aa242`, identical to `main`).
3. Put this file at `Android/docs/review-2026-09-plan.md` (it becomes part of the review's paper trail) and paste the opening prompt below as the first message. CLAUDE.md in the repo root loads automatically and remains authoritative for build commands, traps, and the documentation rules.

**Opening prompt to paste:**

```
Run the TrailMix full review described in Android/docs/review-2026-09-plan.md. Read that file completely first, then CLAUDE.md. Work through the phases in order on branch claude/trailmix-full-review-sprn28. Phase 0 (baseline) must actually run: unit tests, debug + release builds, signer and permission checks, phone state. One commit per confirmed defect with a unit test where the logic is pure or fakeable; file everything the plan says to file in Android/docs/review-2026-09.md using the vault To-Do row schema with tentative IDs. Decisions already made by me and not to be re-asked: photo-export permissions are signed off; extract all UI strings to strings.xml in this review; end at versionName 1.20.0 / versionCode 23 with an annotated tag, a release-signed APK, and a GitHub Release. Never adb uninstall and never run connectedAndroidTest against the Pixel. Report honestly what was and was not exercised on the device. Open a draft PR to main when the branch is pushed.
```

## Context

Requested: an all-encompassing review of the TrailMix Android app and repo (optimization, UX, bug-hunting, other improvements), with a Pixel 9 Pro on USB for live testing.

**Decisions taken with the user**
- **Execute locally.** A local session (this file as its brief, or `claude --teleport session_016Jww9DbSPswXxCmJK7dnXs` from the same clone to inherit the original conversation) does everything: static review, fixes, `testDebugUnitTest`, builds, device pass. Branch `claude/trailmix-full-review-sprn28`, draft PR to `main`.
- **Fix as you go**: one commit per confirmed defect, with a unit test where the logic is pure/fakeable. Larger or user-decision items are filed, not built.
- **Findings file in the repo**: `Android/docs/review-2026-09.md` — every new backlog row in the vault's To-Do schema (`| ID | Priority | Category | Item | Details | Requested |`) with **tentative IDs** the user reconciles against the vault. (This session cannot reach the Obsidian vault.)
- **Photo export is signed off**: keep `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE(≤32)`; document them in README Security posture and CLAUDE.md.
- **Extract all UI strings to `strings.xml`** in this review (Phase 5, after bug fixes, as mechanical commits).
- **End state: v1.20.0 / versionCode 23**, tagged, release-signed APK built, GitHub Release published.

**Cloud-session constraints that shaped this plan**: no Android SDK, no adb, `dl.google.com` blocked, so nothing was built or run there. Everything below is from reading; the local session's first job is to establish a real baseline. Appendix A holds the file:line detail behind every finding.

## Established facts (reality check, 2026-09-18)

| Fact | Detail |
|---|---|
| HEAD | `19aa242` (2026-09-12), branch == `origin/main`, tree clean |
| Undocumented work | **11 commits after v1.19.0** (all 2026-09-12, prior Sonnet session): BLD-01 CI + Room `MigrationTest`; export-format dropdown; photo export backend (**DB v9→v10**, two new permissions); photo picker UI; first `androidTest`; INT-04 import from export folder; UX-19/UX-20 search moments; OBS-05 export repair; Compose UI tests; conference-day soak test; CAL-06 day grouping |
| Version | still `1.19.0` / `22`; `Migrations.kt` and `NoteEntity.kt` already say "v1.20.0" |
| Docs drift | CLAUDE.md header/Project Facts stop at v1.19.0 ("DB stays v9", "213 tests", INT-04 "no import path"); README says no import path, omits photos/permissions/export formats/CAL-06/OBS-05 |
| Tests | 245 unit `@Test` + 10 instrumented; last commit message claims 258 green — **unverified here** |
| No git tags locally | releases are documented as tagged; verify on origin |
| Hazard on record | commit `8600a3f`: `connectedAndroidTest` **auto-uninstalled the real app** on the Pixel (signature mismatch) → data loss. Never run it against the Pixel with the release build installed |
| CI | GitHub + Forgejo workflows run unit tests only (JDK 17); no lint, no release build, no androidTest |

## Phase 0 — Baseline (local session, before any change)

1. `git status`, `git log --oneline -3`; confirm HEAD `19aa242` on the review branch.
2. `cd Android && ./gradlew :app:testDebugUnitTest` with the documented `JAVA_HOME`/`ANDROID_HOME`. Record the real count. If anything is red, fix that first (own commit).
3. `./gradlew :app:assembleDebug` and `:app:assembleRelease`; `apksigner verify --print-certs -v` on the release APK → SHA-256 must be `b6a53a16…` (release key present); `aapt2 dump permissions` → no INTERNET / ACCESS_NETWORK_STATE.
4. `adb devices`; `adb shell dumpsys package com.trailmix.app | grep -E 'versionName|firstInstallTime'`; note which build the phone holds. Check the export folder file count (no `-maxdepth`) as the data-safety baseline.
5. Confirm `git ls-remote --tags origin` shows `v1.17.0` etc.; fetch tags.

## Phase 1 — Confirmed defects (fix in this order, one commit each)

IDs are tentative (`?`) — next free numbers per prefix known from CLAUDE.md. Each row: what, where, test.

| # | ID | Defect | Files | Test |
|---|---|---|---|---|
| 1 | INT-05? | **Import duplicates every existing note.** Export writes `created:` at second precision (`NoteMarkdown.kt:238`), importer parses it, and `importFromExportFolder` dedupes on `(title, createdAtEpochMs)` against ms-precision DB rows → key never matches. Also parses in device-local TZ with no zone. Fix: dedupe on `(title, createdAt / 1000)` or on tracked URI; make the existing `NotesRepositoryTest` fake's `listExportedNotes` return real files (it returns `emptyList()` today). | `NotesRepository.kt:291-326`, `NoteMarkdownImporter.kt:154`, `NotesRepositoryTest.kt` | JVM: existing note with ms≠0 → skipped, not duplicated |
| 2 | REL-16? | **Failed merge is reported as "Nothing captured".** `endAndMerge` maps a thrown merge to `-1` (same as CAP-11 empty), journal stays on disk but `refreshRecovery()` is never called → recovery prompt only after a process restart; nav host toasts "no note saved". Fix: distinguish failure from empty (sealed result or `-2`), call `refreshRecovery()` after a failed merge, show an honest message. Also advance `priorDurationMs` at End & Merge so the final journal delta carries the true duration. | `CaptureSessionManager.kt:453-522`, `TrailMixNavHost.kt:91-102` | JVM if `CaptureSessionManager` gets a fake seam (see Phase 4); otherwise device (force AICore failure is hard — verify via reasoning + log) |
| 3 | CAP-18? | **Late `teardownPipeline()` releases the *new* pipeline.** `CaptureEngine.begin()` assigns `pipeline = pipe`; the previous flow's `onCompletion { teardownPipeline() }` runs after ML Kit's stop/close IPC and releases whatever `pipeline` currently is. Pause→Resume is the trigger. Fix: capture `pipe` in the closure and guard `if (pipeline === pipe)`; leak-free release of the *old* one. | `CaptureEngine.kt:73-94, 181-188` | Device: pause/resume ×5, `adb shell ps -T` pump-thread count stays 1, transcript continues after resume |
| 4 | REL-17? | **SAF delete/exists run on the caller's dispatcher (Main).** `ExportSink.deleteExported`/`exists` are non-suspend and `NoteExporter` does no `withContext(IO)`; callers are `viewModelScope` → binder I/O on the UI thread on every launch (`purgeExpiredDeleted`), delete, Settings open (`detectAndClearDeletedExports`), migration. Fix: make both `suspend`, wrap in `Dispatchers.IO`. | `ExportSink.kt:78,86`, `NoteExporter.kt:39-45`, fakes in tests | JVM: compile + existing cascade tests; device: StrictMode `detectDiskReads` run shows no violation |
| 5 | OBS-06? | **Photos re-copied on every export with a new name; never deleted.** `copyInto` unconditional; `uniqueName` appends `-1,-2…` → unbounded growth in the user's vault; delete/deleteForever/migrate never remove `photos/`. Fix: track exported photo *filenames* (reuse if present), delete them in the cascade, migrate them. Consider a `photos/<note-base>/` subfolder so cascade is a directory delete. | `PhotoExportWriter.kt`, `NoteExporter.kt:81-86`, `NotesRepository.delete/deleteForever/migrateExports`, `ExportSink` | JVM via `ExportSink` fake (extend `ExportedFiles` with photo names); device: export twice, folder listing unchanged |
| 6 | CAP-19? | **`resume()` ignores `merging`; `endAndMerge` leaves `paused`.** A stale notification Resume during a merge starts a second recording. Fix: guard `resume()` on `merging`; clear `paused` in `endAndMerge`. Also guard `CaptureViewModel.resume`. | `CaptureSessionManager.kt:357, 458` | JVM (with seam) or reasoning + device |
| 7 | REL-18? | **`crashGuard` leaves mic/service/`merging` stranded.** Handler only flips `recording=false`. Fix: route through `stopCapture()` semantics (cancel jobs, `engine.endInput()`, `CaptureService.stop`, clear `merging`/`mergeStatus`, flush journal). | `CaptureSessionManager.kt:91-96` | Reasoning + a unit test if seam exists |
| 8 | CAP-20? | **Main-thread blocking in pause/cancel.** `engine.endInput()` → `AudioPipeline.stop()` joins the pump up to 1 s on the main thread (notification Pause path via `CaptureService` on `Main.immediate`). Fix: move teardown to the session scope (`scope.launch`) with state flipped synchronously; keep ordering with the journal flush. | `CaptureSessionManager.kt:325-352, 651-680`, `CaptureService.kt:98-103` | Device: pause from notification, no ANR, `dumpsys audio` shows recorder gone within 1 s |
| 9 | AI-08? | **One malformed model element discards the whole AI summary.** `getString`/`getJSONObject`/`getString("heading")` throw → `runCatching` → deterministic fallback, silently. Fix: `optString`/`optJSONObject` + `mapNotNull`; log at `w`. Same pattern in `SegmentsJson`/`TranscriptJson` decode (one bad element loses the whole transcript) → `optJSONObject`+`mapNotNull` like `CustomTemplatesJson`. | `OnDeviceAiProcessor.kt:307-334`, `Models.kt` codecs | JVM: `ModelsTest` bad-element cases; extract the JSON→summary parse into a pure function and test it |
| 10 | AI-09? | **Prompt bound is soft.** `evenSampleLines` admits ≥1 line per slice regardless of length → `MAX_CONTEXT_CHARS` can be exceeded many-fold. Fix: hard cap (truncate the admitted line to the slice budget, or skip when a single line exceeds it), also bound `typedFragments` in prompts. | `TranscriptCoverage.kt:107-128`, `OnDeviceAiProcessor.kt` prompts | JVM: `TranscriptCoverageTest` with 12×5,000-char lines ≤ maxChars; `windows(targetWindowSeconds=0)` no NPE |
| 11 | AI-10? | **No timeout on model calls/download; cancelled merge reported as success.** `generate()`/`ensureModelReady()` unbounded; `catch (_: Exception)` swallows `CancellationException`. Fix: `withTimeout` per call (e.g. 90 s generate, 10 min download), rethrow `CancellationException` everywhere (13 broad catches listed in report-capture-ai §14). | `OnDeviceAiProcessor.kt:65,70-84,173,217,397-404`, plus listed catch sites | JVM where pure; otherwise reasoning |
| 12 | REL-19? | **`SpeechRecognizer` fallback lane: forever retry, silent on `INSUFFICIENT_PERMISSIONS`, `removeCallbacksAndMessages(null)` on a shared handler.** Fix: retry ceiling → close with error; close on permissions error; per-flow `Runnable` tokens. | `OnDeviceSpeechRecognizer.kt:98-127,179` | JVM: extend `OnDeviceSpeechRecognizerTest` for the ceiling |
| 13 | CAP-21? | **Legacy chime mute is not crash-safe** (`muteSystemChimes` with no restore on process death). Fix: restore in `TrailMixApp.onCreate` if a persisted "muted" flag is set; persist flag in DataStore when muting. | `CaptureEngine.kt:196-217`, `TrailMixApp.kt`, `SettingsRepository` | Device (legacy lane cannot be forced on this Pixel) — reasoning + unit test of the flag round-trip |
| 14 | CAP-22? | **`CaptureService.onStartCommand` has no `else`/null branch** → `startForegroundService` without `startForeground` → `ForegroundServiceDidNotStartInTimeException`; `onDestroy` doesn't cancel reminder/call-ended notifications; no `onTimeout` for `dataSync` (Android 15). Fix all three. | `CaptureService.kt:65-140` | Device: start via stale notification action after force-stop → no crash |
| 15 | CAP-23? | **`continueRecovered()` starts a mic FGS without checking `RECORD_AUDIO`/`POST_NOTIFICATIONS`** (prompts live only in `CaptureScreen`). Fix: check in `HomeScreen` recovery dialog before Continue; request if missing. | `CaptureSessionManager.kt:797`, `HomeScreen.kt` recovery dialog | Device: revoke mic, force-stop mid-capture, Continue → prompt, no crash |
| 16 | UX-22? | **Chat input dropped while busy**; no send button; no error bubble on `chat()` failure. | `ChatScreen.kt:196-199`, `ChatViewModel.kt` | Device |
| 17 | UX-23? | **`ExportFormatPickerDialog` `remember` unkeyed** → stuck on `LLM_OPTIMIZED` if opened before DataStore resolves. `remember(initialFormat)`. | `ExportFormatPickerDialog.kt:33` | androidTest exists; add case |
| 18 | UX-24? | **Two permission launchers fired in one frame** (`POST_NOTIFICATIONS` then `RECORD_AUDIO`). Sequence: mic first, notifications after result. | `CaptureScreen.kt:111-123` | Device: fresh-install flow shows both prompts |
| 19 | UX-25? | **Settings has no back affordance** (`onBack` unused). Add the `BackTitleBar`. | `SettingsScreen.kt:76` | Device |
| 20 | UX-26? | **Dark-mode switch can never return to "follow system"**; manual override doesn't set `SystemBarStyle`; `themes.xml` white `windowBackground` flashes. Fix: tri-state row (System/Light/Dark), pass `SystemBarStyle` to `enableEdgeToEdge`, transparent/theme-aware window background. | `SettingsScreen.kt:255,961-981`, `MainActivity.kt:30-34`, `res/values*/themes.xml` | Device |
| 21 | UX-27? | **Meetings screen says "Nothing scheduled" when permission is denied**; `UpcomingMeetingSource` returns `emptyList()` for denial. Sealed result + grant prompt. | `UpcomingMeetingSource.kt:91`, `MeetingsScreen.kt:88` | Device |
| 22 | UX-28? | **Blank/false states**: Note detail `note ?: return` blank on entry; Transcript says "No transcript was captured" while loading; Home shows "No notes yet" before first Room emission. Add loading state (nullable→sealed) on all three. | `NoteDetailScreen.kt:79`, `TranscriptScreen.kt:118`, `HomeScreen.kt:359`, `HomeViewModel.kt:63` | Device |
| 23 | OBS-07? | **Importer drops any body line wrapped in `*…*`** (meant to drop the meta line) and truncates at the first `\n---\n`. Tighten to the exact meta-line shape. | `NoteMarkdownImporter.kt:67-91` | JVM: `NoteMarkdownImporterTest` italic-line case |
| 24 | BLD-02? | `CaptureJournalTest.kt:81` holds raw NUL bytes → file is "binary" to grep/diff. Replace with `"\u0000".repeat(3)`. | test file | test still passes |

Smaller confirmed items to fold into the nearest commit above: `NoteDetailViewModel.deleteNote` dead; `CaptureSessionManager.activeResumeNoteId` dead; `CaptureEngine.asrDownloading` never observed (surface as "downloading model" in Capture); `OnDeviceAiProcessor` unused imports; `Recipes.RecipesJson.decode` all-or-nothing vs its docstring; `SettingsViewModel.saveRecipe/saveTemplate` read-modify-write outside `dataStore.edit {}`; `repairingExports` plain `var` → `Mutex`; `NoteMarkdown.baseName` trailing `-`; `PhotoPickerSheet` cannot clear a stale selection (Save hidden when no photos).

## Phase 2 — Performance (own commits, measured where possible)

| ID | Change | Files |
|---|---|---|
| UX-29? | **List screens select `transcriptJson`.** Add a projection DAO (`NoteListRow` without `transcriptJson`/`segmentsJson` blobs where the row only needs preview) for Home and Recently deleted; keep `HomeNoteIndex` fed by full rows only when search touches transcript (lazy `getById`). Measure with `adb shell dumpsys gfxinfo` / a `HomeNoteIndexLoadTest` extension. | `NoteDao.kt`, `HomeViewModel.kt`, `HomeNoteIndex.kt`, `RecentlyDeletedScreen.kt` |
| REL-20? | **Indexes** on `notes(deletedAtEpochMs, createdAtEpochMs)` and `chat_messages(noteId)`; replace live `transcriptJson <> '[]'` predicate in `observeUnexportedCount` with a stored `hasTranscript` column. **DB v10→v11, additive `MIGRATION_10_11`**, `11.json` exported, `MigrationTest` extended; device-verify over real data. | `NoteEntity.kt`, `Migrations.kt`, `NoteDao.kt`, `MigrationTest.kt` |
| UX-30? | **Memoize decoded entity getters at the composition boundary**: Note detail (6 decodes/recomposition), Transcript (largest column, defeats its own `remember`), Recently deleted rows, Chat VM on `Main.immediate`. Per-screen decoded model like `HomeNote`; `remember(current.id, current.hashCode())` at minimum. | `NoteDetailScreen.kt`, `TranscriptScreen.kt`, `RecentlyDeletedScreen.kt`, `ChatViewModel.kt` |
| UX-31? | `selectedMarkdown`/`deleteSelected` on Main → `Dispatchers.Default/IO`; `LaunchedEffect(liveLines.size, livePartial)` scroll thrash → key on size only; unkeyed `items()` in Transcript/Meetings/Capture/Settings rows. | `HomeViewModel.kt:90-111`, `CaptureScreen.kt:442-462`, others |
| AI-11? | `SummaryText.dedupe` O(n²), `selectDistinct` O(limit×n), `classify` O(bullets×sentences) — profile on `LongSessionLoadTest` and add a timing assertion; optimize only if the merge budget shows it. | `SummaryText.kt`, `OnDeviceAiProcessor.kt` |
| REL-21? | `SettingsRepository`: one shared `AppSettings` flow instead of 11 cold `data.map` collectors; add `corruptionHandler`. `listExportedNotes` reads every file into memory → stream per file. | `SettingsRepository.kt`, `NoteExporter.kt:120-138` |

## Phase 3 — UX / accessibility (own commits)

- **Semantics**: `Role.Switch`+state on the dark toggle; `Modifier.selectable(role = RadioButton)` on the four chip groups; dialog actions → `TextButton` (also fixes double-fire); `Role.Button` on clickable `Text`; `contentDescription` on meaningful icons and photo cells.
- **Touch targets**: `BackChevron` 20dp/no indication → 48dp `IconButton`; hamburger, search-clear, share, ⓘ, ↑/↓ likewise; replace glyph characters (`↑ ↓ ▾ ▸ ☐ ⓘ`) with Material icons **from `material-icons-core` only** (Trap) or local vector drawables.
- **Insets**: `navigationBarsPadding()` on Home (FAB, selection bar), Settings, Recently deleted, Meetings.
- **Contrast**: dark-theme amber on `#101214` ≈3.3:1 at 11–13sp — introduce a dark-mode `amber` token that passes 4.5:1, keep brand amber for large/decorative.
- **Consistency**: the one `Toast` → Snackbar; Recently-deleted entry reachable without scrolling 200 rows (pin below search or in the hamburger); Note-detail action bar (5 actions at 12sp) → overflow menu per handoff; custom-template delete gets a confirm like notes do.
- **Back while merging** (`CaptureScreen.kt:135`): keep a "Merging…" sheet or allow back with the Home chip showing merge progress.

## Phase 4 — Test, CI, build hygiene

1. **Seam for `CaptureSessionManager`**: constructor already takes interfaces except `CaptureEngine`/`CaptureJournalStore`/`Context`; introduce a small `CaptureEngine` interface (or open class + fake) and inject a `CoroutineDispatcher`, then add `CaptureSessionManagerTest` for: pause/resume timeline, `merging` latch incl. failure path (Phase 1 #2, #6, #7), journal delta contents, recovery state machine. This is the single largest untested file (948 lines) and owns the transcript.
2. `importFromExportFolder` + `NoteExporter` name resolution tests through the existing `ExportSink` fake (fix the fake first).
3. Extract the model-JSON→`StructuredSummary` parser into a pure function and test it (#9).
4. CI: add `./gradlew :app:lintDebug` and `:app:assembleRelease` (debug-key fallback is fine in CI — the point is that R8 is exercised); add a merged-manifest assertion that INTERNET is absent (`aapt2 dump permissions` step); keep Forgejo file in sync (or make it call the GitHub one via a shared script).
5. Commit missing Room schemas `2.json`–`8.json`? Not reconstructible honestly → document in `MigrationTest` that pre-v9 shapes are hand-derived; add `11.json` for the new migration.
6. Gradle: `org.gradle.caching=true`, `org.gradle.parallel=true`, `configuration-cache` if AGP 8.7 tolerates; bump `-Xmx` to 4g. Dependency bumps (Compose BOM, AGP, lifecycle, coroutines) are **filed**, not done — the `material-icons-core` and GenAI manifest-strip traps make bumps a separate verified session.
7. Fail loudly when the release key is absent on `assembleRelease` outside CI (a Gradle warning at minimum), since the fallback APK is unpublishable.

## Phase 5 — String extraction (user chose: do it now)

Mechanical, after Phases 1–3 so it does not conflict with fixes. Order: destructive dialogs and privacy copy first, then per screen. Use `<plurals>` for the ~20 hand-rolled `"s"` suffixes; `stringResource` in composables; `context.getString` in ViewModels/service (notification text, `ImportResult.summary`/`ExportRepairResult.summary` become resource-backed via a small formatter). Keep `Locale`-correct `uppercase(Locale.ROOT)` in `SectionLabel`. One commit per screen; unit tests for the pure summary formatters keep passing by moving the English strings into test expectations.

## Phase 6 — Device pass (Pixel 9 Pro, release-signed in-place update)

Guardrails: **never `adb uninstall`, never `connectedAndroidTest` on this phone** (run instrumented tests on an emulator or a disposable device only). Screenshot before touching a PiP window. Verify signer before every install. Export-folder listing before/after each destructive step.

Checklist (record each as pass/fail/not-exercised in `review-2026-09.md`):
1. In-place install of the review build; `firstInstallTime` unchanged; **DB v10→v11 migration over real notes** with zero loss; no `TrailMix*` warnings; empty crash buffer.
2. Capture → typed fragment → pause (notification) → resume → End & Merge → note. `ps -T` pump threads = 1 after resume (Phase 1 #3), 0 after merge; FGS type flips MICROPHONE→DATA_SYNC (REL-10 still holds).
3. Force-stop mid-capture → recovery prompt → Continue; **revoke RECORD_AUDIO first** on a second run → prompt, no crash (#15).
4. Stale notification action after force-stop → no `ForegroundServiceDidNotStartInTimeException` (#14).
5. Export twice, edit note, run recipe: `photos/` count unchanged (#5); delete note → photos gone; change export location → photos migrate.
6. Settings → Restore from export folder on a healthy library → "Nothing new to restore" (#1), no duplicates.
7. Search across the library; Home scroll with 20+ notes; `dumpsys gfxinfo` jank before/after Phase 2.
8. Chat: send while busy is queued/blocked visibly; failed chat shows an error (#16).
9. TalkBack pass over Home, Capture, Note detail, Settings: every control announced with role and state.
10. Dark mode: System/Light/Dark tri-state, status bar icons follow app theme, no white flash on cold start.
11. `aapt2 dump permissions` on the release APK: RECORD_AUDIO, READ_CALENDAR, READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE(≤32), FGS types, POST_NOTIFICATIONS — **no INTERNET**.
12. Legacy recognizer lane, device-audio capture during a call, 60-min idle nudge, call-ended prompt: attempt; mark not-exercised honestly if not inducible.

## Phase 7 — Docs, version, release, PR

1. `Android/docs/review-2026-09.md`: method, per-finding outcome (fixed commit / filed ID / not reproduced), device-pass results, proposed To-Do rows (tentative IDs) and Complete rows for the 11 undocumented commits **and** this review's fixes, marked `v1.20.0`.
2. **CLAUDE.md** (mirror of the vault Master Prompt): new header paragraph for v1.20.0; Project Facts (version, tests, DB v11, photo permissions, INT-04 now real, BLD-01 done); new Traps (teleport/no-SDK constraint; `connectedAndroidTest` uninstall hazard; late `teardownPipeline`; SAF I/O must be `suspend`+IO; import dedupe precision); registry rows for `data/media`, photo export, import. Tell the user which vault notes need the same edits (Architecture, On-Device AI, UI and Design, Security and Privacy, Build and Deployment, Future Improvements) since the vault is unreachable here.
3. **README**: import path exists; photo export + permissions in Security posture; export formats; CAL-06; UX-19/20; OBS-05; remove machine-specific JDK paths; fix the Screens list nesting; state current version.
4. Bump `versionName = "1.20.0"`, `versionCode = 23`; commit `v1.20.0`; annotated tag; `assembleRelease`; verify signer + permissions; GitHub Release with APK marked latest (Obtainium picks it up).
5. Push branch, open **draft PR** to `main` with the findings summary; offer to watch it.

## Filed, not built (need user decision or a separate session)

- Dependency/toolchain bumps (AGP 8.7→current, Compose BOM, Kotlin/KSP, coroutines) — verify `material-icons-core` icons and the GenAI manifest strip after.
- `MIGRATION_1_2` absence — confirm no v1 installs exist; otherwise add.
- Search debounce / FTS column (documented threshold in `NoteSearch.kt`).
- `dataSync` FGS 6-hour quota behaviour on Android 15 beyond `onTimeout` handling.
- Atomic SAF write (REL-15 remainder) — needs the instrumented harness on an emulator.
- Handoff deviations (Settings has nine sections vs "minimal") — product decision, document only.
- Predictive back (`enableOnBackInvokedCallback`), nav transitions.

## Verification summary

- Unit: `cd Android && ./gradlew :app:testDebugUnitTest` green after every commit; count recorded in CLAUDE.md.
- Static: `:app:lintDebug` clean of new errors; `assembleRelease` succeeds; `aapt2 dump permissions` unchanged except the documented photo permissions.
- Device: Phase 6 checklist, each item with evidence (adb output, screenshot path) in `review-2026-09.md`; nothing claimed verified that was not exercised.
- Release: tag on the commit carrying `1.20.0`, signer fingerprint `b6a53a16…`, in-place update on the Pixel with `firstInstallTime` unchanged and note count unchanged.

---

## Appendix A — Finding detail by file (from the three exploration passes, 2026-09-18)

Line numbers are against HEAD `19aa242`. Items already in the Phase 1–3 tables are referenced by their `#`; the rest are secondary findings to fold into nearby commits or file. Paths are under `Android/app/src/main/java/com/trailmix/app/` unless noted.

### Repo, build, CI (inventory pass)
- 60 main `.kt` files / 13,403 LOC; largest: `ui/home/HomeScreen.kt` 1163, `ui/settings/SettingsScreen.kt` 981, `data/speech/CaptureSessionManager.kt` 948, `ui/capture/CaptureScreen.kt` 768.
- Unit tests: 26 classes, 245 `@Test`; instrumented: 3 classes, 10 `@Test`. **No tests** for `OnDeviceAiProcessor`, `CaptureSessionManager`, `AudioPipeline`, `CaptureEngine`, `CaptureService`, `NoteExporter`, `PhotoExportWriter`, `SettingsRepository`, `UpcomingMeetingSource`, any ViewModel. No mockk/turbine/coroutines-test/robolectric on the classpath.
- `test/.../data/speech/CaptureJournalTest.kt:81` has raw NUL bytes in a string literal → grep/diff treat the file as binary (#24).
- Room schemas: only `9.json`, `10.json` under `Android/app/schemas/…`; `MigrationTest` hand-derives v2–v8 shapes. `Migrations.kt` KDoc says "v2→v9" (stale, chain is 2→10). No `MIGRATION_1_2` (filed).
- `Android/app/build.gradle.kts`: release falls back to the debug key silently when `TRAILMIX_*` properties are absent; `proguard-rules.pro` admits R8 has "never been built/tested with minification on"; no `lint {}` block; no `composeCompiler {}` options; `testOptions.unitTests.isReturnDefaultValues = true` (REL-13, keep).
- `Android/gradle.properties`: 4 lines, `-Xmx2048m`, no `org.gradle.caching`/`parallel`/configuration cache.
- `libs.versions.toml`: AGP 8.7.3 + Gradle 8.11.1 (late 2024) with Kotlin 2.2.21; Compose BOM 2024.12.01, lifecycle 2.8.7, coroutines 1.9.0, datastore 1.1.1, documentfile 1.0.1; GenAI `1.0.0-beta1` / ASR `1.0.0-alpha1`. `material-icons-core` only (correct). `org.json` is test-only on Maven but used by 4 main files against the platform copy.
- CI: `.github/workflows/test.yml` and `.forgejo/workflows/test.yml` identical except `runs-on`; unit tests only; no lint, no release build, no androidTest, no merged-manifest permission check.
- README discrepancies: says no import path (INT-04 now exists); omits photo export + its two permissions from Security posture; "two files per export" now false with photos; export formats undocumented; CAL-06, UX-19/20, OBS-05 undocumented; no mention of test suite/CI/androidTest; machine-specific JDK paths; "Upcoming meetings" mis-nested under Note detail in the Screens list; no current version stated.
- CLAUDE.md: header and Project Facts stop at v1.19.0; says DB stays v9 and 213 tests; INT-04 "no import path"; BLD-01 pending. Vault notes need the same edits (list in Phase 7).

### `data/speech/CaptureSessionManager.kt`
- `scope = CoroutineScope(SupervisorJob() + crashGuard)` — no dispatcher → `Dispatchers.Default`; 13 `launch` sites (lines 107, 208, 270, 289, 365, 398, 405, 431, 460, 704, 743, 853, 900, 915).
- :208 template seed from DataStore races :212 `startRecording` — a fast End & Merge can use `NONE`.
- ~30× `_state.value = _state.value.copy(...)` from ≥4 threads (ticker on Default, listen collector, `pause()`/`resume()` on Main via `CaptureService`, calendar launch) → lost updates; use `update {}`.
- :145 `transcriptLines` plain `MutableList` appended on Default (:237), cleared on Main (:187/:376/:805), read at :506 after a `withTimeoutOrNull(5_000)` join (:488) — on timeout, iterated while mutated.
- Non-volatile session fields :146–173 written from Main and Default.
- :752 `flushJournal()` not re-entrant (called from `pause()`, `stopCapture()`, `journalJob`).
- :357 `resume()` ignores `merging`; :458 `endAndMerge` leaves `paused` (#6).
- :325/:598/:651 → `engine.endInput()` → `AudioPipeline.stop()` → `join(1_000)` on Main (#8).
- :91 `crashGuard` only flips `recording` (#7). :453 `endAndMerge` never `refreshRecovery()` (#2). :486 `runMerge` no timeout, no cancel (#11).
- :269/:671 `CaptureService.start/merge` unguarded (`ForegroundServiceStartNotAllowedException`); :797 `continueRecovered` no permission check (#15).
- :429 `delay(500)` as sync for projection attach; :450 `deviceAudioSupported` non-reactive getter read from Compose (`CaptureScreen.kt:382`).
- :176 `activeResumeNoteId` dead. :946 bump nudge uses `delay(1h)` — unreliable under Doze (file).
- `runMerge` → `stopCapture(handOffToMerge)` → `flushJournal()` reads `currentDurationMs()` with `recording=false` and `priorDurationMs` never advanced → last journal delta under-reports duration (fold into #2).

### `service/CaptureService.kt`
- :65 `onStartCommand` `when` has no `else`/null branch; PAUSE/RESUME/STOP_AND_SAVE/DISMISS_* skip `startForeground` (#14).
- :98–103 pause/resume/endAndMerge run synchronously on Main; `endAndMerge {}` from the notification swallows failure.
- :150 `startObservers` sets `observersStarted = true` before `serviceScope ?: return` → can never start if scope null.
- :152–194 two collectors post the same `NOTIFICATION_ID`; only `distinctUntilChanged` prevents a race while `paused && mergeStatus != null`.
- :137 `onDestroy` does not `stopForeground` or cancel reminder/call-ended notifications; no `onTaskRemoved`; no `onTimeout` for `dataSync` on Android 15 (#14).
- :91 `engine.attachDeviceAudio` builds/starts an `AudioRecord` on Main. `POST_NOTIFICATIONS` requested only in `CaptureScreen.kt:111`.
- No `Log` calls anywhere in the service.

### `data/speech/CaptureEngine.kt`
- :73–94 `pipeline = pipe` then `onCompletion { teardownPipeline() }` with no identity guard (#3). :63–64 `pipeline`/`projection` non-volatile, written on Main handler and read on Default.
- :152 `MediaProjection.Callback` never unregistered; `onStop → detachDeviceAudio → projection.stop()` re-entrant.
- :196–217 chime mute not crash-safe (#13). :97–103 model download launched per session start, un-deduplicated, on a scope with no exception handler. :60 `asrDownloading` never observed. :112 `emptyFlow()` for NONE leaves the UI on "Listening…". :148/:202/:212 broad catches.

### `data/speech/AudioPipeline.kt`
- :269 `stop()` doesn't close the read side or interrupt the pump — a dead recognizer strands thread + `AudioRecord` + 2 fds after the 1 s join. :141/:233 `Thread.sleep(20)` spin on persistent `read() <= 0` (no failure counter; negative error codes treated as "no data"). :253 `detachPlayback` never joins the playback thread. :240 ring-buffer overflow dropped silently. :84 `check(!running)` throws `IllegalStateException`, not `AudioUnavailableException`. :101 buffer sizing mixes frames and bytes.

### `data/speech/MlKitTranscriber.kt` / `OnDeviceSpeechRecognizer.kt`
- Transcriber: new client per `status()/download()/transcribe()`; `stopRecognition()+close()` blocking IPC in `finally` on the collector's dispatcher (why the 5 s join exists); :83 `ErrorResponse` logged and swallowed → session sits on "Listening…" with no `captureError`; :44/:56 broad catches.
- Recognizer: :126 `ERROR_INSUFFICIENT_PERMISSIONS` logs only; :98 retry forever at 8 s cap; :179 `removeCallbacksAndMessages(null)` on a shared singleton handler; :191 `Channel.UNLIMITED` with no backpressure; :121/:143 double `startListening`; :68 `flowOf(SpeechEvent("",""))` placeholder (#12).

### `data/speech/CaptureJournalStore.kt`
- Writes are `scope.launch` enqueues, so "journaled before it is anything but RAM" is slightly overstated; :204 full-disk recovery writes a newline to a full disk; `pending()` replays every file with no size cap at singleton construction. Coverage good (29 tests). File only.

### `data/ai/OnDeviceAiProcessor.kt`
- :49 `generativeModel` never closed. :70–84 `ensureModelReady` collects a full download with no timeout on every merge and chat; two `checkAvailability()` IPCs. :397 `generate()` no timeout (#11).
- :283–285 JSON extraction `substringAfter('{')`/`substringBeforeLast('}')`; :308/:313/:315/:317/:323/:324 `getString`/`getJSONObject` throw → whole summary lost (#9); :328 compares `optString` to literal `"null"`.
- Prompts interpolate `typedFragments` unbounded (:130–146, :262–280, :343–351) (#10). :368–392 `classify` is O(bullets × transcript sentences). :168 `segments.ifEmpty { fallbackMerge(...).segments }` runs a second full `DeterministicSummary`. :442 `attributeProvenance` duplicates `classify`. :65/:173/:217 broad catches swallow `CancellationException`. :16–17 unused imports. Progress: final structuring pass after chunks is unreported (`MergeStatus` shows WRITING for minutes).

### `data/ai/TranscriptCoverage.kt`, `SummaryText.kt`, `DeterministicSummary.kt`, `NoteTitle.kt`, `Recipes.kt`
- Coverage :107–128 `evenSampleLines` admits ≥1 line per slice (#10); :86/:88/:94 `!!` reachable with `targetWindowSeconds = 0`; unlabelled lines collapse windows.
- SummaryText: `dedupe` O(n²) (:56), `selectDistinct` O(limit×n) (:81), 15 regex passes per sentence (:26–34, :165), `titlecase` uses `Locale.getDefault()` vs `Locale.ROOT` elsewhere (:33).
- DeterministicSummary: `isAction` bare substrings (`"should "`, `"send "`) (:165); `from(String,String,style)` overload dead (:121).
- NoteTitle: `isDefault` matches the English literal `"Note — "` while the date is localized (:32).
- Recipes: `RecipesJson.decode` all-or-nothing `runCatching` contradicts its "entries are skipped" docstring (:45–53).

### `di/AppModule.kt`, `MainActivity.kt`, `TrailMixApp.kt`, manifest
- DB v10, migrations 2→10 contiguous, no destructive fallback (good). DAOs unscoped (fine). DataStore delegate lives in `SettingsRepository.kt:21`, no `corruptionHandler`.
- `MainActivity.kt:31` creates a new eager `stateIn` per activity recreation; first frame renders with `null` theme → flash. No `SystemBarStyle` passed to `enableEdgeToEdge`. `TrailMixApp` has no `onCreate` work (chime restore would go here, #13).
- Manifest: permissions and FGS types correct; INTERNET/ACCESS_NETWORK_STATE removed via `tools:node="remove"` (merge-time — add a CI assertion); no `<uses-feature android:name="android.hardware.microphone">`; no `onTimeout` handling for `dataSync` (Android 15).

### `ui/` — cross-cutting
- `res/values/strings.xml` has one entry; zero `stringResource` calls; ~400 literals; ~20 hand-rolled plurals (Phase 5).
- Zero `semantics`/`Role`/`selectable`/`toggleable` in `ui/`. Sub-48dp targets: `components/Common.kt:56` `BackChevron` 20dp + `indication = null`; `HomeScreen.kt:195` hamburger 34dp, :322 search clear 18dp; `NoteDetailScreen.kt:172` share 20dp, :553/:645 ⓘ 13sp, :485–502 ↑/↓ 16sp (Phase 3).
- Insets: `navigationBarsPadding()` missing on Home (FAB :427, selection bar :451), Settings (:690), Recently deleted, Meetings.
- Theme: dark amber `#BB5D00` on `#101214` ≈ 3.3:1 at 11–13sp; `AlertDialog` `containerColor` passed manually at 11 sites; no typography/shape scale (`13.5.sp` literal ×14). `Common.kt:26` `uppercase()` without `Locale`.
- Nav: `TrailMixNavHost.kt:99` the only `Toast`; Transcript and Chat get their own `NoteDetailViewModel`/`ChatViewModel` instances → duplicate Room subscriptions per note; no transitions.

### `ui/home/*`
- `HomeViewModel.kt:63` initial `emptyList()` → "No notes yet" on every cold start (#22). :90–111 `deleteSelected`/`selectedMarkdown` run on Main. :157 `purgeExpiredDeleted()` on every launch (Main + SAF, #4). :121/:128 two `LaunchedEffect(Unit)` on `extraBufferCapacity = 1` flows → emissions while off-screen are lost (`recoveredNoteId` never opens the note).
- `HomeScreen.kt:735` `SimpleDateFormat` built in composition; :136 `contextMenuNote` holds a stale `NoteEntity`; dialog confirms are bare clickable `Text` (double-fire) at :514, :667, :849, :926; :299/:326 writes `viewModel.searchQuery.value` from composition; no search debounce; "Recently deleted" row is the last list item (:407); :251 section label is a hidden button. `HomeNoteIndex.kt` is sound; :140 eviction only when `entries.size > notes.size`.
- `RecentlyDeletedScreen.kt:71/:206` `System.currentTimeMillis()` in composition; :217 `note.preview` re-parses JSON per row per recomposition.

### `ui/note/*`, `ui/transcript/*`, `ui/chat/*`
- `NoteDetailScreen.kt:79` `note ?: return` blank screen (#22); decoded getters in composition: `exportedPhotoUris` :115 and ×3 at :306–310, `attendees` :292/:294/:296, `structuredSummary` :320, `segments` :338, `displayBody` :127 — none `remember`ed (Phase 2). :588 `withTimestamp` allocates per bullet. :466 collapse state keyed by heading jumps after reorder. :109 `LaunchedEffect(Unit)` inside `if`. Five 12sp top-bar actions + "Add photos". `saveEdits` has no error surface. Glyphs `↑ ↓ ▾ ▸ ☐ ⓘ`. `NoteDetailViewModel.deleteNote` dead.
- `TranscriptScreen.kt:117/:99/:83` decode `transcript` three times per composition; :127 `remember(lines, …)` never hits (new list per parse); :118 false "No transcript was captured" while loading; :146 `itemsIndexed` unkeyed.
- `ChatScreen.kt:196–199` input cleared before `send` early-returns on busy (#16); no send button; no empty state; :94 `LocalClipboardManager` read per item; :112 `widthIn(max = 300.dp)`. `ChatViewModel.kt:51/:73` `observeNote().first()` instead of `getNote`; :57/:75 decodes on `Main.immediate`; :91 recipes matched by English name; no error path for a thrown `chat()`.

### `ui/capture/*`, `ui/settings/*`, `ui/meetings/*`, `ui/export/*`
- `CaptureScreen.kt:111–123` two launchers in one frame (#18); :442 `LaunchedEffect(liveLines.size, livePartial)` scroll thrash; :462/:560 unkeyed `items`; :555 local `selectedTemplate` mirrors a non-reactive getter; :538 writes `viewModel.fragments.value` from composition; :135 `BackHandler` disabled while merging → back pops to Home mid-merge; :681 `DropdownMenu` uses default M3 colors; :724 meaningful icon with null description; :748 `startActivity` failure swallowed.
- `SettingsScreen.kt:76` `onBack` unused (#19); :255/:961 dark switch can't return to system, no `Role.Switch` (#20); :301/:642/:674 unkeyed `LazyRow`s inside a 981-line `verticalScroll` column; :498–618 four `PromptRow`s wire tap and long-press identically; :720 editor dialog `remember` unkeyed; :787 template delete has no confirm; :422 `startActivity` failure handled here but not in Capture.
- `SettingsViewModel.kt:76–83` `init` runs `detectAndClearDeletedExports()` on Main (#4); :103–131 `takePersistableUriPermission` before validation, no re-entry guard; :201/:242 read-modify-write outside `dataStore.edit {}`; 12 separate `stateIn` collectors of the same Preferences file.
- `MeetingsScreen.kt:43` VM declared in the screen file; :95 unkeyed `items`; :88 "Nothing scheduled" for permission denied (#21); :122–123 `isOngoingAt()` in composition ×2.
- `ExportFormatPickerDialog.kt:33` unkeyed `remember` (#17); options color-only. `PhotoPickerSheet.kt:69` `remember(photos)` wipes selection on reload; :161 Save hidden when no photos → tracked selection can never be cleared; :183 thumbnails via `loadThumbnail` uncached; :194 null description on the only cell content; select-all is a bare `Text`.

### `data/db/*`
- **No `@Index` anywhere** (confirmed in `9.json`/`10.json`). `NoteDao.kt:32/:42` `SELECT *` incl. `transcriptJson` for list screens, filesort on `createdAtEpochMs`; :61 `observeUnexportedCount` live `COUNT` with `transcriptJson <> '[]'` string compare, re-evaluated on every `notes` write; :64 `getUnexported` `SELECT *`, called after **every** successful export via `retryMissingExports`; :110 `getDeletedBefore` `SELECT *` for ids only; `ChatDao` :128/:135/:141 `WHERE noteId` unindexed, `getRecipeOutputs` called on every export (Phase 2).
- `NoteEntity.kt:84–95` decoded getters (`segments`, `transcript`, `attendees`, `structuredSummary`, `exportedPhotoUris`, `displayBody`, `preview`) re-parse on every access.
- `NotesRepository.kt`: :291–326 import dedupe (#1) and `getAll()` loads full blobs to compare two URI columns (:352, #4); :399 `exportMissing` re-reads each note to detect a moved URI; :226/:260/:422 cascades never touch `photos/` (#5); :487 `repairingExports` plain `var` (→ `Mutex`); :144/:155/:206 whole-row `update` (short window, inconsistent with `setExportUris`). `Models.kt` `SegmentsJson`/`TranscriptJson` decode use `getJSONObject` → one bad element loses the whole list (#9); `StructuredSummaryJson.decode` returns null for an all-empty summary (load-bearing, undocumented).
- `SettingsRepository.kt`: 11 independent `data.map` flows; `setCustomRecipes`/`setCustomSummaryTemplates` take whole lists; same DataStore filename as production (instrumented-test hazard, documented in `build.gradle.kts:144`).
- `UpcomingMeetingSource.kt:91` empty list for denied permission (#21); `isOngoingAt`/`minutesUntilStart` read "now" in composition; attendee cursor uses positional indices.

### `data/export/*`
- `ExportSink.kt:78/:86` `deleteExported`/`exists` non-suspend; `NoteExporter.kt:39–45` no `withContext(IO)` (#4). :85 `PhotoExportWriter.copyInto` unconditional every export; `PhotoExportWriter.kt:77–89` `uniqueName` appends `-N` with an unbounded `findFile` loop (#5). :120 `associateBy { it.name }` then :126 `noteFile.name!!`; :138 `readBytes()` of every export file into memory on restore.
- `MarkdownExportWriter.kt:75` `"wt"` truncating write, not atomic (REL-15 remainder, filed); :101 `isShortWrite` ignores stale non-zero provider lengths; two `exists()` round-trips per export; several full-directory `findFile` enumerations per save.
- `NoteMarkdown.kt`: 9 `SimpleDateFormat` per render; `Locale.US` on the human-readable meta line (:297) and `photoTime` (:462); `baseName` `.take(48)` can leave a trailing `-` (:112); `stripMarkdown` compiles 3 regexes per call (:477); `topics()` recomputes `SummaryText.keywords` on every render incl. share (:437); attendee/photos YAML emitted unwrapped.
- `NoteMarkdownImporter.kt:90` drops any `*…*` line; :67 truncates at first `\n---\n`; :155 parses in device zone with no TZ (#1, #23); :130 frontmatter split on first `": "`.
- Handoff deviations (from `Android/docs/design-handoff/README.md`): Settings has nine sections vs "minimal, do not add rows"; Note-detail bar has five actions vs one pill; transcript shows `mm:ss` not speakers; glyph characters instead of icons; dark-mode toggle shown and non-resettable. Document in `review-2026-09.md`, do not "fix" without a product decision.
