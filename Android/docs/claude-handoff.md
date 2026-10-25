# Handing notes to Claude

TrailMix stays local-only: it never sends a note anywhere on its own. Two hand-off paths
exist, both started by you.

## 1. One note, from the phone: Send to Claude

Note Detail → overflow menu → **Send to Claude**. This shares the note's LLM-optimized
Markdown, with a one-line instruction, to the Claude Android app (`com.anthropic.claude`).
If Claude is not installed the normal share sheet opens instead. You review and press send
inside Claude. Only the note summary is sent, not the companion transcript.

## 2. Everything, to your Linux box: shared export folder

TrailMix already writes each note as `<name>.md` plus `<name>.transcript.md` into the
**Export location** (Settings → Export). Point a folder-sync tool at that folder and let
Claude on the workstation read it. No new app code or permission is involved.

1. In TrailMix, set **Export location** to a folder on shared storage, e.g. `TrailMix-notes`,
   and keep the export format on **LLM-optimized** (Settings → Export).
2. Sync that folder to the workstation with any SAF-friendly tool (Syncthing is the
   local-only choice; a cloud-synced folder means that app uploads the files).
3. On Linux, give Claude read access to the synced folder:
   - **Claude Code:** run `claude` inside the folder, or `claude --add-dir <folder>`.
   - **Claude Desktop (beta, Ubuntu 22.04+/Debian 12+):** add the Filesystem MCP server
     pointed at the folder, then ask it to read recent notes.
4. Ask it to parse: for example "Read the newest `*.md` (not `.transcript.md`) and list
   decisions and action items; open the transcript only to check a quote."

Tips: the summary is short on purpose, so start there; the frontmatter (`date`, `topics`,
`action_items`) lets Claude filter by it without opening the transcript.

## Not available

Neither Claude app documents a way to push a note in silently. Links like
`claude://claude.ai/new?q=` (desktop) prefill a prompt of at most about 14,000 characters
and still need you to press send; on mobile the documented links cover only the Code tab.
