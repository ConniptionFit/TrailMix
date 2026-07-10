# The Trail

> Session library: virtualized sidebar, FTS search, Campfire Preserves (folders), history grid, bulk ops, Obsidian export.

## Hub pieces

| Piece | ID / location |
|---|---|
| Sidebar list | `#sidebar-calls-list` — **virtualized** (estimate 92px/row, overscan 6) |
| Search | `#input-sidebar-search` — debounced; FTS unless `#tag` |
| Folders | Campfire Preserves — `fs:` / `fs:Name`; select toggles classes (no full re-fetch) |
| History tab | Card grid (cap 48) |
| Hub home | Recent 8 + Open / Resume |
| Bulk bar | Merge, export, delete, unlock |

## Virtualization & selection

- Only visible rows mounted inside a spacer; scroll parent = `.sidebar-history-section`
- **Event delegation** for click / context / tooltip / retry / suggested tags
- Multi-select tracked by `selectedTrailIds` (survives scroll remounts)
- Highlight via `openedSessionId` (hub `activeSession` is not the source of truth)

## FTS5

| Piece | Detail |
|---|---|
| Module | `lib/session-fts.js` |
| IPC | `search:sessions` |
| Indexed | title, description, summary, notes, actions, tags, transcript |
| Excluded | Locked encrypted sessions |
| Ranking | `bm25` + `snippet()`; hub sorts list by FTS rank |
| Hydration | Single `WHERE id IN (...)` (no N+1) |
| Writes | Skipped on debounced silent saves; run on flush / explicit save / decrypt |

Tag search: query starting with `#` filters tags client-side (no FTS).

## Folders (Campfire Preserves)

| Action | Behavior |
|---|---|
| Create / rename / delete | IPC `folders:*`; delete requires empty |
| Move session | `calls:move-to-folder` |
| Export folder | 📤 → pick dir → Obsidian markdown export |

## Bulk & context menu

Merge, export, delete, decrypt-multiple; context: open location, find related (metadata-only scoring), select mode, delete.

## Obsidian export

| | |
|---|---|
| IPC | `calls:export-obsidian` |
| API | `window.api.exportObsidian(folderId, exportDir)` |
| Output | `{sanitizedTitle}_{sessionId}.md` |
| Sections | Title, Date, Tags, Jots, Blended Notes, Summary, Action Items |
| Skips | Encrypted sessions without in-memory unlock |
| Not included | Full transcript (use `calls:export` for transcript markdown) |

## Processing badges

In-place patch via `processing:jobs-updated`; Retry → `processing:retry`. Labels: “Sifting the Mix…”, “Sorting the Rations…”, “Packaging the Trail…”.

## Hub shortcuts

| Key | Action |
|---|---|
| `/` | Focus Trail search |
| Ctrl/Cmd+B | Toggle sidebar |
| Ctrl/Cmd+N | New meeting |
| Ctrl/Cmd+J | Mix-Master |
| Esc | Close modals |

## Related

[[Architecture]] · [[Encryption & Privacy]] · [[Processing Pipeline]] · [[Mix-Master]]
