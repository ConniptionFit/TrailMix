# Processing Pipeline

> After Stop: crash-resilient multi-pass speaker work, then enrichment (Mix enhance, summary, actions, rename, tasks).

## Service

`services/SessionProcessingService.js` — SQLite jobs + breadcrumbs; recovers interrupted jobs on boot.

## Passes

| Pass | Status | Work |
|---|---|---|
| 1 Draft | `draft_frozen` | Job created at finalize (live labels only) |
| 2 Precision | `precision_*` | LLM diarization per block; **2 blocks in parallel**; ordered checkpoint saves |
| 3 Identification | `identification_*` | Name speakers (`userName` context); batches of 4 blocks |
| 4 Enrichment | `enrichment_running` | `runSessionEnrichment` in runtime |
| Done | `complete` | List refresh / summary-ready events |
| Failed | `failed` | Sidebar Retry |

## Enrichment (`runSessionEnrichment`)

1. Sort transcript
2. `runEnhanceNotesForSession` → editor document + enhanced notes
3. Parallel Ollama: **summary** + **action items** (truncated transcript)
4. Context rename → title, description, suggested tags (`applyAutoTitle`)
5. **`await extractTasksFromActionItems`** → `tasks` (+ deadline parse)
6. Optional rename `id` → `{slug}_{oldId}` if auto-title
7. Broadcast `calls:session-summary-ready` / `session:updated`

## UI feedback

- Sidebar processing badge + progress
- Meeting may receive `session:updated` / transcript patches
- Action Items / Calendar jump via `meetings:open` + `session:focus-segment`

## Libs

| Lib | Role |
|---|---|
| `lib/processing-blocks.js` | Split / batch transcript |
| `lib/speaker-diarization.js` | Turns + prompts |
| `lib/speaker-identification.js` | Cluster map + global rename |
| `lib/llm-utils.js` | JSON parse helpers |

## Related

[[Live Capture & Transcription]] · [[The Mix]] · [[The Trail]] · [[Mix-Master]]
