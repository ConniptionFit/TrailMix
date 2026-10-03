# UX redesign (October 2026)

Implements the Claude Design handoff (design system, Home, Live capture, Note detail,
Transcript and Chat, Settings, Deleted and Meetings) on top of the audit in the project
notes. Shipped as a stack of draft PRs, one per area, each branched from the last.

## What changed

| Area | Change |
|---|---|
| Theme and kit | Ink is the action colour. Amber means "you typed it", teal means "it was said", red means recording or destructive. Bundled Instrument Sans and IBM Plex Mono (no network). Shared `Tm*` components: top bar, buttons, chips, rows, sheets, dialogs, snackbar, text field. |
| Capture | One recording card with a live level meter, a clearer pause and end flow, and the layout fix that keeps End and Merge reachable. |
| Note detail | Structured summary is editable in place; Enhanced and My notes toggle; Regenerate with an edits warning. |
| Home | Day-grouped list, first-run explainer, one overflow menu, labelled selection bar, instant delete with Undo, recording and Building cards, export health line, recovery sheet. |
| Deleted and Meetings | Restore in one tap; permanent delete is a second, red confirm. Meetings grouped by day with a link to an existing note. |
| Settings | Six pages with state subtitles; full-screen editors; destructive actions confirmed. |
| Transcript | Speaker blocks, search, filters, long-press actions, inline wording fixes. |
| Chat | One shared pane for single and cross-note chat; Markdown replies; starters; Stop; Copy, Share, Add to note. |

No Room schema change. New data lives in existing JSON blobs as additive keys (`"ed"` on a
structured bullet and on a transcript line). Prompts are unchanged.

## Follow-ups not done

- Typed-only capture after microphone permission is denied (needs a `CaptureService` change).
- Show the failure when a merge throws (REL-24).
- Drag to reorder sections and bullets (up and down buttons ship now).
- Link a bullet to its transcript line index.
- Cancel for Rebuild.
- Tablet list-detail pane (the list is capped at 640dp).
- Settings: show sources by default, default microphone, a Restore preview.
- Transcript: fast-scroll thumb, Remove flag, Change speaker.
- Store the calendar event id on a note. Meetings currently matches "has a note" on the same
  title and day.
- View-model snackbar strings in Settings are still English literals.
- Remaining uses of amber for actions should move to ink as screens are touched.
