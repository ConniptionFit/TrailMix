# Speaker detection: build status

Plan: [plan.md](plan.md). Each phase is a draft PR stacked on the one before.

| Phase | PR | State |
|---|---|---|
| 0 Foundations | #23 | Line-to-speaker mapping over the utterance span, Me cluster named from lane evidence, resume/recovery time offset. Not done: evaluation corpus, threshold tuning, device measurements (need a device). |
| 1 Roster and cues | #24 | `Roster`, `NameCues`, `SpeakerFusion`; wired after diarization in `mergeAndSave`. |
| 2 Voiceprints | this branch | `VoiceMath`, `VoiceMatcher`, `VoiceprintStore` (files, not Room), `SpeakerRecognition`, session-wide speaker embeddings from `SherpaOnnxDiarizer`, voice evidence in `SpeakerFusion`, passive Me enrollment. |
| 3 Live labels | not started | Needs the Phase 0 embedding speed measurement first. |
| 4 Incremental diarization, series priors | not started | |
| 5 Roster import (paste, share, OCR) | not started | UI-heavy; wait for the UX rework. |

## Phase 2 notes

- Everything voice-related is behind `speakerRecognitionEnabled` (off by default) and needs diarization on, because speaker embeddings come from the diarization pass.
- Thresholds in `VoiceMatcher` (0.50 / 0.65 / margin 0.10) are provisional. They are not tuned on TrailMix recordings. Tune with the Phase 0 corpus before turning recognition on by default.
- Voiceprints live in `filesDir/voiceprints/` (`people.json`, `note-<id>.json`). A Room table would need a schema bump that cannot be verified without a device; moving later is a change behind `VoiceprintStore`.
- Known gap: `note-<id>.json` is not deleted when a note is permanently deleted. `SpeakerRecognition.forgetNote(id)` exists; calling it from `NotesRepository.deleteForever` and the purge is a small follow-up.

## API the UX track can call (no UI built yet)

`SpeakerRecognition.rememberVoice(noteId, label, personName)` enrolls the speaker shown as `label` in that note. `people()`, `forget(id)`, `forgetNote(id)`, `forgetEveryone()` manage who is remembered. The Settings toggle `speakerRecognitionEnabled` already exists in `SettingsRepository` with no UI.
