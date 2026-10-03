# TrailMix Speaker Detection and Tagging: Optimization Plan

Status: plan only, no code changed. Written 2026-10-02 against `main` at c51545f (v1.22.0).
Scope: engine, data model, and fusion logic. UI is listed as requirements for the parallel UX track, not designed here.

## 1. TL;DR

- **Today** TrailMix has three disconnected speaker signals: post-merge anonymous diarization ("Speaker 1/2", off by default), a loudness-based Me/Them lane label (only when device audio is attached), and calendar attendee names (never used to name anyone).
- **The big win** is fusing them. A speaker is named only when evidence agrees: voice match, lane, calendar roster, and what people say ("thanks, Priya").
- **Cheapest high-value step**: in a 1:1 meeting, Me is known, so the other voice is the other attendee. Deterministic, no voiceprints, no new permission.
- **Voiceprints** (enroll once, recognized later) are the second step. They reuse the sherpa-onnx embedding model already shipped. They are biometric data, so they are opt-in and local.
- **Meeting-app hooks**: only the non-intrusive ones are worth building (calendar, pasted roster, screenshot OCR). Official platform APIs need network, accessibility/notification/screen hooks are privacy-hostile. Details in section 6.
- **Rule that governs everything**: precision over recall. A wrong asserted name is worse than "Speaker 2". Every name carries a confidence tier, and below the bar it stays anonymous.

## 2. Terms

- **Diarization**: split audio into "who spoke when", anonymous (Speaker 1, 2).
- **Embedding / voiceprint**: a fixed-length vector (a few hundred floats) describing a voice. Same person means nearby vectors.
- **Centroid**: the average embedding of one cluster or one enrolled person.
- **Cluster**: all the audio the system believes is one speaker within one meeting.
- **Evidence**: one independent signal pointing at a name (voice, lane, roster, cue).

## 3. Current state (verified in code)

| Piece | Where | What it does | Gap |
|---|---|---|---|
| Diarization | `SherpaOnnxDiarizer`, `SherpaOnnxDiarizerConfig` | pyannote segmentation 3.0 int8 (1.5 MB) plus wespeaker ResNet34 int8 embedding (6.7 MB), 5 minute windows, `SpeakerLinker` joins windows by embedding | Runs only at merge time. `clusterThreshold = 0.5` is a never-tuned placeholder. Toggle `speakerDiarizationEnabled` defaults to off. Needs the whole session's PCM retained (up to 90 min, about 170 MB). |
| Label mapping | `SpeakerLabels.apply` | Maps each line to one segment by a single timestamp | Line label is stamped when the utterance **finalizes** (end of speech plus ASR latency), so near a turn change the point can land in the next speaker's segment. Should use span overlap. |
| Me/Them | `LaneActivity`, `TranscriptLine.speechSource` | Mic vs playback energy, 2x dominance rule | Only with the device-audio lane attached. Useless for speakerphone (everything arrives via the mic). |
| Label precedence | `TranscriptLabels.speakerOf` | Diarization label wins over Me/Them | When both exist, "Me" is lost and replaced by "Speaker 1". |
| Roster | `UpcomingMeetingSource.attendeesFor` | Attendee display names or emails, stored on the note | Used only for the note prompt and `AutoTemplate`. Never used to name a speaker. No organizer, response status, or email kept. |
| Profile | `UserProfile.name` | Who "Me" is, fed to the note prompt | Not linked to any speaker label. |
| Dormant scaffolding | `speaker_profiles` table, `SpeakerProfileRepository`, `LiveSpeakerMatcher`, `speakerRecognitionEnabled` | Left from the Picovoice Eagle path (AI-12) | Table holds Eagle bytes, never written. Matcher is reusable (vote hysteresis). No enrollment UI exists. |

Two things to verify before building on them (read from code, not run):
1. After **Resume into an existing note**, `audioRetention` restarts at 0 while transcript labels continue from `priorDurationMs`. Diarization timestamps may be offset by the prior duration. Needs an offset or a test.
2. Whether the audio sink in `AudioPipeline` receives the mic lane alone or the post-mix stream. Me enrollment and Me/Them voice checks want the **mic lane alone**.

## 4. Target design

### 4.1 Pipeline

```
 capture (mic lane, playback lane)
    |-- per-utterance PCM clip (from a short rolling buffer)
    |      -> embedding (wespeaker int8)  -> live match vs enrolled voiceprints / online clusters
    |-- LaneActivity (Me/Them energy)
    |-- transcript line + NameCues (self-intro, direct address, hand-off)
    v
 live label with confidence tier (shown while recording)
    v
 post-pass: windowed pyannote diarization (turns, overlap), clusters re-linked by embedding
    v
 SpeakerFusion (pure Kotlin): clusters x candidate names -> assignment with abstain
    v
 per-note speaker table + labelled lines -> note prompt, export, Transcript screen
    v
 user confirms/renames -> "remember this voice" -> voiceprint updated (the loop that makes it better over time)
```

### 4.2 Evidence sources and how each is used

| Evidence | Strength | Notes |
|---|---|---|
| User confirmation | Certain | Always wins. Stored per note and, if the user opts in, as a voiceprint sample. |
| Voice match to an enrolled person | Strong, calibrated | Cosine similarity of cluster centroid vs person centroid. Threshold tuned on a corpus (section 8), not guessed. Multiple centroids per person (phone mic, speakerphone, headset) because channel changes embeddings. |
| Lane = Me | Strong for Me only | Mic dominant while playback attached. Confirms the "Me" cluster, and is the source of passive Me enrollment. |
| Roster constraint | Strong structural | Candidate names restricted to calendar attendees plus "unknown guest". In a 1:1: Me plus one other leaves one name. With N attendees and k already-named clusters, the remaining pool shrinks. |
| Self-introduction cue | Strong | "Hi, this is Rob", "Rob here". Names the **current** speaker. |
| Direct-address cue | Medium, about the **next** speaker | "Priya, can you take this?" names whoever answers next, not the current speaker. Getting this direction wrong is the classic bug, so it is encoded as a typed cue with a test. |
| Third-person mention | Negative or none | "Priya said..." means Priya is likely not the current speaker. Used only as a weak exclusion. |
| Series prior | Weak | Same recurring event (calendar `ORIGINAL_ID`/title) means same people attended before and the same voiceprints were useful. Biases the candidate pool. |
| Turn-taking | Weak | Alternating pattern in 2-party calls. Tie-breaker only. |

### 4.3 Fusion (pure, unit-testable)

`SpeakerFusion` takes `List<ClusterEvidence>` and `List<Candidate>` and returns `List<Assignment(clusterId, name?, tier, reasons)>`.
- Score per (cluster, candidate) as a sum of calibrated log-likelihood terms from the table above.
- One-to-one assignment (Hungarian or greedy with swap check). Two clusters can never get the same name unless the user merges them.
- **Abstain** when the best score does not clear the bar or the margin over second best is thin.
- Tiers: `CONFIRMED` (user), `CONFIDENT` (shown as the name), `SUGGESTED` (shown as "Speaker 2 · Priya?"), `ANONYMOUS`.
- `reasons` is kept (for example `voice 0.81, roster, cue:self-intro`) so a wrong guess is explainable in the UI and in tests.
- Gemini Nano is **not** in the loop for deciding. At most it can adjudicate a tie on a short snippet after deterministic cues fail. Its 256-token output cap and BUSY behavior make it a poor primary.

### 4.4 Data model (additive, no destructive migration)

- New table `people`: `id, displayName, email (nullable), createdAt, updatedAt`.
- New table `voiceprints`: `id, personId, embedding BLOB, sampleCount, channelTag (phone-mic / speakerphone / headset), source (user-confirmed / me-lane / enrolled), updatedAt`. Embeddings only, never audio.
- New nullable `notes.speakersJson`: per-note speaker table `{clusterId, name?, tier, reasons, centroid}`. Keeping the per-note centroid lets the user later say "that was Priya, remember her" **without any retained audio**.
- `TranscriptLine` gains additive JSON keys: `sid` (cluster id) and `sc` (tier). `speakerLabel` keeps working for old notes.
- The dormant `speaker_profiles` table is left alone (additive rule). New code does not use it.
- Cost: one Room version bump (v15 to v16) with `ALTER`/`CREATE` only, plus a `MigrationTest` case.

### 4.5 Memory and CPU budget

- Current: retaining PCM costs about 1.9 MB per minute (16 kHz, 16-bit), so it is capped at 90 min (about 170 MB) to stay inside the 256 MB Dalvik heap limit.
- **Incremental windowed diarization**: process each 5 minute window as soon as it completes during capture, keep only segments and centroids, free the PCM. Heap becomes O(window), the 90 minute cap goes away, and the merge-time spike (CAP-30's issue) disappears. Cost: CPU work during recording under the existing foreground service. Must be measured for thermals and battery on a Pixel (section 8).
- **Per-utterance embeddings** need only a 30-60 s rolling buffer. Embeddings are about 1 KB each.
- Models resident only while in use (existing pattern in `SherpaOnnxDiarizer`). Added APK size: zero if the current embedding model is reused. A multilingual model would add a few MB.
- Numbers above that I have not measured (real-time factor of the embedding model on a Pixel 9 Pro, battery) are explicitly **to be measured in Phase 0**, not assumed.

## 5. Phases

Each phase is independently shippable and fail-soft (no speaker data means today's behavior).

### Phase 0: Foundations and measurement (no behavior change users see, except fixes)
- Fix line-to-speaker mapping: use the utterance span (previous line end to this line's finalize time, same span `LaneActivity.sourceForLine` already uses) and pick max overlap. Mark lines with a material second speaker as mixed.
- Make Me/Them and diarization compose: a lane-confirmed Me line is "Me" regardless of the diarization cluster, and the Me cluster is the one most Me lines fall in.
- Verify and fix the resume-into-note time offset (section 3, item 1).
- Verify the audio sink carries the mic lane alone (item 2). If not, tap the mic lane before mixing, as `LaneActivity` already does.
- Build the evaluation harness (section 8) and tune `clusterThreshold`, `minDuration*`.
- Measure embedding RTF, heap, and battery on the Pixel. Decide on default-on for diarization from data.
- Size: M.

### Phase 1: Roster and conversation cues, no voiceprints
- Keep richer attendee data from the calendar: name, email, organizer, response status. Same `READ_CALENDAR`, no new permission.
- `NameCues` (pure): self-intro, direct-address-then-next-speaker, hand-off phrases, nickname and first-name matching against the roster, with a conservative stoplist for common-word names.
- `SpeakerFusion` v1 using roster, cues, lane. Covers:
  - 1:1 and "me plus one other" meetings fully.
  - Larger meetings partially (self-intros, direct address chains).
- Pass named speakers into the note prompt (the `Meeting:` line and speaker-prefixed transcript already exist, so named speakers improve action item owners).
- Export and Transcript show the name with tier. Unnamed stay "Speaker N".
- Size: M. Highest value per effort.

### Phase 2: Voiceprints and learning
- Me enrollment, two routes, user picks: a 20 second read-aloud in onboarding, or passive enrollment from lane-confirmed Me segments (only above a purity bar, never from segments where playback was active).
- Per-note centroid saved; after a meeting the user can confirm a name and tap "Remember this voice". The centroid joins the person's voiceprint (running average with a sample cap, new channel tag when the match is weak but the user confirms).
- Matching at post-pass: centroid vs voiceprints, calibrated threshold, fused with roster as in 4.3.
- Management: list people, delete one person, delete all, "forget voices from this note". Toggle `speakerRecognitionEnabled` finally gets a meaning.
- Reuse `LiveSpeakerMatcher`'s vote/hysteresis idea for live use after a type update (profile index to person id).
- Size: L (data model, enrollment flow, calibration).

### Phase 3: Live labels during capture
- Per finalized utterance: embedding then nearest enrolled voiceprint or online cluster. Show tentative labels while recording, reconcile at merge so the final labels come from the full post-pass.
- Live shows `SUGGESTED` at best. Names are only promoted to `CONFIDENT` after the post-pass.
- Size: M. Depends on Phase 2 and on the Phase 0 RTF number. If RTF is bad, ship only the Me/Them live label.

### Phase 4: Incremental diarization and series priors
- Window-by-window diarization during capture (4.5).
- Series prior from recurring calendar events.
- Better overlap handling (split a line only if ASR ever exposes word timings; otherwise mark mixed).
- Size: M.

### Phase 5: Optional roster import
- "Paste participants" and a share-sheet target for text copied from a meeting app's People panel.
- Optional screenshot of the People panel read with on-device text recognition (bundled model, no network). Needs a check that the dependency adds no `INTERNET` merge; see the Trap about re-verifying permissions after any dependency change.
- Size: S for paste, M for OCR.

## 6. Meeting hooks: what to do and what not to do

Criteria from the request: skip anything intrusive, a glaring security risk, a huge undertaking, or too heavy for a Pixel-class phone. Platform behavior below is from general Android and vendor knowledge. I could not confirm vendor API details through search this session, so **re-check each vendor claim before relying on it**.

| Option | Gives | Verdict | Why |
|---|---|---|---|
| Calendar event attendees | Roster, organizer, conference link | **Build (Phase 1)** | Already granted, read-only, local. |
| Platform detection from calendar link plus call state | "Meet/Zoom/Teams" hint, tailors help text | **Build (small)** | No new access. Informational only. |
| Pasted or shared roster | Roster for ad-hoc calls with no calendar entry | **Build (Phase 5)** | User-driven, zero permission. |
| Screenshot of People panel plus on-device OCR | Roster | **Maybe (Phase 5)** | User takes the screenshot, so consent is explicit. Small model. |
| Official meeting APIs (Google Meet REST/Media API, Zoom SDK, Teams Graph) | True participant list and active speaker | **No** | Need OAuth, a cloud project, and network. This breaks the zero-network principle. A companion server is a different product. |
| AccessibilityService reading the meeting UI | Names and active-speaker highlight | **No** | Reads everything on screen, is a policy and trust red flag, and breaks with each app update. |
| NotificationListenerService | Nothing useful | **No** | Meeting notifications carry no participant detail, and the permission exposes all notifications. |
| MediaProjection screen capture plus OCR/vision | Active speaker tile | **No** | Captures the screen, heavy, and scope-creeps the audio-only consent TrailMix asks for today. |
| Telecom / call log / phone state | Caller identity on phone calls | **No for now** | Sensitive permissions for a narrow case. Revisit only if users ask for PSTN-call tagging. |
| Per-participant audio from the meeting app | Perfect diarization | **Impossible** | Voice-call audio is excluded from playback capture and apps can opt out. This is an OS boundary already documented in the app. |

## 7. Privacy and security

- Voiceprints are **biometric identifiers**, and for non-users they identify third parties. Controls:
  - Everything opt-in. No voiceprint is created without an explicit action (read-aloud enrollment, or tapping "Remember this voice").
  - Local only, app-private storage, `allowBackup=false` as today. No new permission in Phases 0-4.
  - Embeddings never go into Markdown exports, shares, or logs. Exports carry names only.
  - Full management UI: view people, delete one, delete all, forget a note's voices. Deleting a note offers to remove voiceprints learned only from it.
  - Embeddings are not invertible to audio in practice, but still treated as sensitive: no logging of vectors, only counts and scores.
- A wrong name is a trust and possibly a legal problem (misattributed quote). Hence tiers, abstain, and the `reasons` trail.
- Caveat to flag to John: storing other people's voiceprints may fall under biometric laws in some regions. Mitigation is opt-in per person plus easy deletion. This is a product decision, not just an engineering one.
- Tradeoff: no backup means voiceprints are lost with the app data. Acceptable. An encrypted export is a possible later item, not part of this plan.

## 8. Evaluation and tests

- **Pure-logic tests (JVM, house style)**: `NameCues` (including the direction-of-address cases), `SpeakerFusion` (assignment, abstain, margin, no duplicate names), span-overlap alignment, voiceprint centroid update, migration test.
- **Synthetic corpus**: multi-voice TTS (the Piper approach used in the v1.22.0 live test) with scripted turn taking, overlaps, gaps, and playback through the phone speaker. Gives ground truth for free.
- **Public benchmark for the diarizer**: a standard meeting set (for example AMI or VoxConverse; check licenses before any redistribution) for DER, run offline on a workstation, not shipped.
- **On-device checks**: heap high-water mark, embedding RTF, battery over a 60 minute capture, thermals, with the existing `scripts/device-test.sh` flow.
- **Metrics that gate shipping**:
  - Wrong-name rate among asserted names (CONFIDENT tier): target under 1%.
  - Coverage: share of speech that gets a name at CONFIDENT.
  - DER of anonymous clusters.
  - Me-vs-others accuracy.
- Thresholds are set from these numbers, not from this document.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Embedding model is English-trained (VoxCeleb) and the app offers other recognition locales | Evaluate a multilingual speaker model behind `SherpaOnnxDiarizerConfig` in Phase 0. Swap needs no interface change. |
| Channel mismatch (phone mic vs speakerphone vs headset) degrades matching | Multiple centroids per person tagged by channel; confirmed-by-user samples add new channels. |
| Similar voices, short utterances (under about 1 s), overlap | Min-length gate for embeddings, abstain, mixed-line flag. |
| Always-on CPU during capture hurts battery or heat | Measure in Phase 0, make incremental diarization adaptive or fall back to merge-time. |
| Label churn: names flip between live and final | Live is SUGGESTED only; final comes from the post-pass. |
| More state to keep consistent (people, voiceprints, per-note table) | Single `SpeakerFusion` entry point, narrow DAO writes (the REL-14 lesson), fail-soft everywhere. |

## 10. Proposed backlog rows

New prefix `SPK-` (a genuine new category; `AI-12` stays as the umbrella for named recognition). Not yet filed in the vault.

| ID | Priority | Item |
|---|---|---|
| SPK-01 | S2 | Span-overlap line mapping, Me/Them and diarization compose, resume time-offset check, mic-lane tap check |
| SPK-02 | S2 | Evaluation harness (TTS corpus, DER, wrong-name rate) and threshold tuning; Pixel RTF, heap, battery measurements |
| SPK-03 | S2 | Rich calendar roster plus `NameCues` plus `SpeakerFusion` v1 (1:1 and roster naming) |
| SPK-04 | S2 | Voiceprints: `people`/`voiceprints` tables, `notes.speakersJson`, Me enrollment, "Remember this voice", matching |
| SPK-05 | S3 | Live per-utterance labels during capture |
| SPK-06 | S3 | Incremental windowed diarization (lifts the 90 minute cap), series priors |
| SPK-07 | S3 | Roster import: paste, share target, optional screenshot OCR |
| SPK-08 | S3 | Evaluate multilingual speaker embedding model |

## 11. Handoff requirements for the UX track

Not designed here, listed so the rework can plan for them:
- Speaker chips on Transcript lines with a tier indicator (confirmed / confident / suggested / anonymous).
- Rename speaker (applies to the whole cluster), merge two speakers, split a mislabeled one.
- "Remember this voice" action and a People screen (list, delete one, delete all).
- Me enrollment flow (read-aloud or passive) and an honest privacy explainer.
- A consent-style note when saving someone else's voiceprint.
- Settings: diarization and recognition toggles, with the memory and battery tradeoff stated plainly.

## 12. Defaults I assumed (change any of them)

1. Voiceprints are opt-in per person, not automatic.
2. Accessibility, notification, screen-capture, and cloud-API hooks stay rejected.
3. Diarization becomes default-on only if Phase 0 numbers show acceptable heap, battery, and heat.
4. Gemini Nano never decides a name on its own.
5. Phase order is 0, 1, 2, then 3-5 by demand. Phase 1 alone already covers 1:1 meetings.
