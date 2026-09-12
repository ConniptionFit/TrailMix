package com.trailmix.app.data.export

import com.trailmix.app.data.ai.NoteTitle
import com.trailmix.app.data.model.TranscriptLine
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Best-effort recovery importer — the inverse of [NoteMarkdown], built 2026-09-12 as one-off
 * tooling after a `connectedAndroidTest` run auto-uninstalled the app on a real device and
 * wiped its Room DB. The exported files in shared storage were the only surviving copy. This
 * is also a first real step toward INT-04 (no import path back into the app), previously just
 * a documented gap.
 *
 * Surveying the actual exported files on the recovery device turned up **at least three
 * distinct historical export formats** — the current OBS-02 frontmatter-rich one with a
 * separate companion transcript file, an intermediate one with `## Highlights`/custom H2
 * sections and old-style `- **Meeting:**`/`- **Attendees:**` metadata bullets, and an early
 * one with the transcript inlined under `## Transcript` using untagged `- **mm:ss** text`
 * bullets. Reconstructing each era's exact `StructuredSummary`/provenance shape correctly
 * would mean writing (and trusting) a parser per era under real time pressure with real data
 * on the line. Instead this importer takes the safer trade: recover the fields that are
 * reliably present in *every* era (title from the `# ` heading, `created`/`duration_ms` from
 * frontmatter, meeting/attendees from either frontmatter or the old inline bullets, the
 * transcript from either the companion file's table or an inline `## Transcript` block) and
 * preserve the rest of the body **verbatim** as [ImportedNote.bodyOverride] rather than trying
 * to re-derive per-bullet provenance/sectioning that isn't safely recoverable from every
 * format. A flatter note that keeps 100% of the user's actual words beats a prettier one that
 * risks silently dropping some.
 *
 * Pure — no Android, no I/O — so this is unit-testable against real exported fixtures.
 */
object NoteMarkdownImporter {

    data class ImportedNote(
        val title: String,
        val createdAtEpochMs: Long,
        val durationMs: Long,
        val meetingTitle: String?,
        val attendees: List<String>,
        val template: String?,
        val bodyOverride: String,
        val transcript: List<TranscriptLine>,
    )

    /** Null only if [noteMarkdown] has no frontmatter block at all — not a TrailMix export. */
    fun parseNote(noteMarkdown: String, transcriptMarkdown: String?): ImportedNote? {
        val fmEnd = noteMarkdown.indexOf("\n---", 3)
        if (!noteMarkdown.startsWith("---\n") || fmEnd == -1) return null
        val frontmatter = parseFrontmatter(noteMarkdown.substring(4, fmEnd))
        val createdAtEpochMs = frontmatter["created"]?.let { parseCreated(it) } ?: return null
        val durationMs = frontmatter["duration_ms"]?.toLongOrNull() ?: 0L

        var body = noteMarkdown.substring(fmEnd + 4).trim('\n')

        // The H1 heading is the one thing every export era writes verbatim as the real title —
        // more reliable than frontmatter's `title:`, which only exists in the newest format.
        val h1 = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(body)
        val title = h1?.groupValues?.get(1)?.trim()
            ?: frontmatter["title"]
            ?: NoteTitle.default(createdAtEpochMs)
        if (h1 != null) body = body.removeRange(h1.range).trim('\n')

        // Drop this file's own transcript footer ("---" + a Full-transcript link) — derived,
        // not source data — but keep everything before it, including an inline "## Transcript"
        // block (old format), which is extracted separately below and then stripped.
        val footerStart = body.indexOf("\n---\n")
        if (footerStart >= 0) body = body.substring(0, footerStart)

        val inlineTranscriptStart = body.indexOf("\n## Transcript\n")
        val inlineTranscript = if (inlineTranscriptStart >= 0) {
            parseOldInlineTranscript(body.substring(inlineTranscriptStart))
        } else {
            emptyList()
        }
        if (inlineTranscriptStart >= 0) body = body.substring(0, inlineTranscriptStart)

        // Old-format metadata bullets right after the title — "- **Meeting:** X" /
        // "- **Attendees:** A, B" — recoverable extras the newest format instead puts in
        // frontmatter. Stripped from the body once captured so they aren't shown twice.
        val meetingFromBody = Regex("""(?m)^- \*\*Meeting:\*\* (.+)$""").find(body)?.groupValues?.get(1)?.trim()
        val attendeesFromBody = Regex("""(?m)^- \*\*Attendees:\*\* (.+)$""").find(body)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        body = body.lines().filterNot {
            it.startsWith("- **Date:**") || it.startsWith("- **Meeting:**") || it.startsWith("- **Attendees:**")
        }.joinToString("\n")

        // The "*Tue 1 Sep 2026 · 10:49 AM · <1m*" meta line and the "> [!summary] TL;DR"
        // callout are both fully derived from fields captured above — drop them too.
        body = body.lines().filterNot { it.trim().let { t -> t.startsWith(">") || (t.startsWith("*") && t.endsWith("*") && t.length > 1) } }
            .joinToString("\n")

        val transcript = transcriptMarkdown?.let { parseTranscriptTable(it) }.orEmpty().ifEmpty { inlineTranscript }

        return ImportedNote(
            title = title,
            createdAtEpochMs = createdAtEpochMs,
            durationMs = durationMs,
            meetingTitle = frontmatter["meeting"] ?: meetingFromBody,
            attendees = frontmatter["attendees"]?.let { parseBracketList(it) } ?: attendeesFromBody.orEmpty(),
            template = frontmatter["template"]?.takeIf { it != "NONE" },
            bodyOverride = collapseBlankLines(body).trim(),
            transcript = transcript,
        )
    }

    /** Current format: a `| Time | Text |` table in the companion `.transcript.md` file. */
    fun parseTranscriptTable(transcriptMarkdown: String): List<TranscriptLine> {
        val rowPattern = Regex("""^\|\s*`([^`]*)`\s*\|\s*(.*?)\s*\|$""")
        return transcriptMarkdown.lineSequence()
            .mapNotNull { line -> rowPattern.find(line.trim()) }
            .map { m -> TranscriptLine(label = m.groupValues[1], text = m.groupValues[2].replace("\\|", "|")) }
            .toList()
    }

    /** Early format: transcript inlined under `## Transcript` as `- **mm:ss** text` bullets. */
    private fun parseOldInlineTranscript(section: String): List<TranscriptLine> {
        val linePattern = Regex("""^-\s+\*\*([0-9:]+)\*\*\s+(.*)$""")
        return section.lineSequence()
            .mapNotNull { linePattern.find(it.trim()) }
            .map { TranscriptLine(label = it.groupValues[1], text = it.groupValues[2]) }
            .toList()
    }

    // ── Frontmatter ─────────────────────────────────────────────────────────

    private fun parseFrontmatter(block: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in block.lines()) {
            val colon = line.indexOf(": ")
            if (colon > 0) result[line.substring(0, colon).trim()] = unyaml(line.substring(colon + 2))
        }
        return result
    }

    private fun unyaml(value: String): String {
        val v = value.trim()
        if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
            return v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return v
    }

    private fun parseBracketList(value: String): List<String> {
        val inner = value.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return emptyList()
        return Regex("\"([^\"]*)\"|([^,]+)").findAll(inner)
            .map { it.groupValues[1].takeIf { g -> g.isNotEmpty() } ?: it.groupValues[2] }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun parseCreated(value: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(value)?.time
    }.getOrNull()

    private fun collapseBlankLines(text: String): String =
        text.lines().fold(mutableListOf<String>()) { acc, line ->
            if (line.isBlank() && acc.lastOrNull()?.isBlank() == true) acc else acc.apply { add(line) }
        }.joinToString("\n")
}
