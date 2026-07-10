# The Mix

> In-meeting notes editor. User jots (“Mix-Ins”) blend with the transcript via local LLM after enhance / finalize.

## Surfaces

| UI | Location |
|---|---|
| Editor host | Meeting `#editor-component-root` |
| Component | `renderer/EditorComponent.js` |
| Enhance button | Mix / “Start the Mix” → `chat:mix-enhance` |
| Service | `services/EnhanceNotesService.js` + `lib/enhance-notes.js` |
| Document model | `lib/editor-document.js` — spans with `origin: user \| ai` |

## Document model

- **User spans** — typed Mix-Ins (preserved)
- **AI spans** — blended output from enhance
- Persisted on session as `editorDocument`, plus `mixNotes` / `enhancedNotes` text fields
- Autosave: editor `onChange` → `persistSession()` (debounced silent save on main; encrypted uses password modal)

## Enhance flow

1. Require non-empty Mix-Ins (toast if empty)
2. `mixEnhance({ plainText, editorDocument, transcript, template })`
3. Apply `editorDocument` + show legend
4. `persistSession()` — encryption password if needed

Auto-enhance also runs during [[Processing Pipeline]] enrichment when jots exist (`runEnhanceNotesForSession`).

## Meeting chrome (adjacent)

| Feature | Notes |
|---|---|
| Transcript pane | Live lines + gap dividers; Ctrl/Cmd+F search |
| Save status | Saving… / Saved / Encryption password required |
| Title | Auto-generated until user edits; hint when auto |
| Encrypt modal | `#encrypt-password-modal` — set session password (memory only) |

## Shortcuts (meeting)

| Key | Action |
|---|---|
| Ctrl/Cmd+S | Persist session |
| Ctrl/Cmd+F | Transcript search |
| Ctrl/Cmd+J | Focus Mix-Master |
| Esc | Close encrypt / search / conflict |

## Related

[[Mix-Master]] · [[Processing Pipeline]] · [[Encryption & Privacy]] · [[Live Capture & Transcription]]
