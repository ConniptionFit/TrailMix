package com.trailmix.app.data.export

import com.trailmix.app.data.ai.SummaryText
import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.TranscriptLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The export's rendering flavor (new — the export-format dropdown feature).
 *
 * [LLM_OPTIMIZED] is the original OBS-02 format and stays the default everywhere: YAML
 * frontmatter, per-bullet provenance tags gated by [NoteMarkdown.Source.showSources], and
 * Obsidian wikilinks between the note and its companion transcript.
 *
 * [HUMAN_READABLE] drops the frontmatter and provenance tags — a person reading this on their
 * phone doesn't need either — and swaps wikilinks for plain filenames so the file also makes
 * sense outside Obsidian. It keeps the same section structure, just without the clutter.
 *
 * [PLAIN_TEXT] goes further: no Markdown syntax at all (headers become plain/ALL-CAPS lines,
 * no bold/backticks/wikilinks, the transcript's table becomes flat `HH:MM  text` lines), for
 * pasting somewhere that would otherwise mangle Markdown. Exported as `.txt`, not `.md`.
 */
enum class ExportFormat { LLM_OPTIMIZED, HUMAN_READABLE, PLAIN_TEXT }

/**
 * A photo copied into a note's `photos/` export subfolder (photo-export feature), by the
 * name it actually ended up with (a collision with another note's photo may have renamed it)
 * and when it was taken — filled in by [PhotoExportWriter][com.trailmix.app.data.export.PhotoExportWriter]
 * after the copy, since that's the only point the real on-disk name is known.
 */
data class ExportedPhoto(val filename: String, val takenAtEpochMs: Long)

/**
 * OBS-02 (v1.11.0): builds the exported Markdown.
 *
 * The note has two audiences that pull in opposite directions, and this file is where that
 * tension is resolved:
 *
 * - **The user, on a phone.** Wants to scan, not read. Needs headings, short bullets, a
 *   verdict at the top, and no wall of text.
 * - **Another LLM, later.** Wants machine-readable metadata, an explicit structure it can
 *   anchor on, and — critically — *not* to have its context window eaten by a 40,000-character
 *   raw transcript that contributes almost nothing per token.
 *
 * Both are served by the same thing: dense structure up front, and the transcript moved to a
 * companion file ([transcriptFileName]) rather than appended. The summary stays paste-ready;
 * the transcript stays one link away. [ExportFormat] adds two more flavors for a third
 * audience (a person who doesn't want either kind of clutter) — see its own doc comment.
 *
 * Everything here is pure — no Android, no I/O — so the whole format is unit-testable.
 */
object NoteMarkdown {

    /** Data the builder needs, decoupled from `NoteEntity` so this stays pure and testable. */
    data class Source(
        val title: String,
        val createdAtEpochMs: Long,
        val durationMs: Long,
        val meetingTitle: String? = null,
        val attendees: List<String> = emptyList(),
        val template: String? = null,
        val capturedInCall: Boolean = false,
        val summary: StructuredSummary? = null,
        val bodyOverride: String? = null,
        val flatBody: String = "",
        val transcript: List<TranscriptLine> = emptyList(),
        val recipeOutputs: List<Pair<String, String>> = emptyList(),
        val showSources: Boolean = true,
        /** Photo-export feature: photos copied alongside this export, if any were selected. */
        val photos: List<ExportedPhoto> = emptyList(),
        /**
         * OBS-03. Actual on-disk base names (no `.md`) of the two files this note owns, when
         * they are already known — used verbatim for every wiki-link instead of re-deriving
         * one from [title].
         *
         * They exist because the two can legitimately disagree. A file keeps the name it was
         * first written under (the exporter rewrites it through its tracked URI), while
         * [title] can change afterwards — an AI re-title, or a fallback that reverts to the
         * placeholder. Deriving links from the title then points them at a filename that was
         * never created, which is exactly the dangling back-link found on-device 2026-08-01.
         *
         * Null means "no file tracked yet", i.e. a first export, where deriving from the
         * title is correct and is what the files will actually be called. The ad-hoc Share
         * flow also leaves these null — it writes no files, so there is nothing to point at.
         */
        val noteLinkBase: String? = null,
        val transcriptLinkBase: String? = null,
    ) {
        /** Link target for the summary note — the real filename when known. */
        internal val noteLink: String
            get() = noteLinkBase ?: noteFileName(title, createdAtEpochMs).removeSuffix(".md")

        /** Link target for the companion transcript — the real filename when known. */
        internal val transcriptLink: String
            get() = transcriptLinkBase
                ?: transcriptFileName(title, createdAtEpochMs).removeSuffix(".md")
    }

    // ── File naming ─────────────────────────────────────────────────────────

    fun baseName(title: String, createdAtEpochMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(createdAtEpochMs))
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "note" }
            .take(48)
        return "$date-$slug"
    }

    private fun extension(format: ExportFormat) =
        if (format == ExportFormat.PLAIN_TEXT) "txt" else "md"

    fun noteFileName(title: String, createdAtEpochMs: Long, format: ExportFormat = ExportFormat.LLM_OPTIMIZED) =
        "${baseName(title, createdAtEpochMs)}.${extension(format)}"

    fun transcriptFileName(
        title: String,
        createdAtEpochMs: Long,
        format: ExportFormat = ExportFormat.LLM_OPTIMIZED,
    ) = "${baseName(title, createdAtEpochMs)}.transcript.${extension(format)}"

    // ── Summary note ────────────────────────────────────────────────────────

    fun buildNote(source: Source, format: ExportFormat = ExportFormat.LLM_OPTIMIZED): String = buildString {
        if (format == ExportFormat.LLM_OPTIMIZED) {
            append(frontmatter(source))
            appendLine()
        }
        appendLine(titleLine(source.title, format))
        appendLine()

        if (format != ExportFormat.LLM_OPTIMIZED) {
            // No YAML for a human — the friendly header replaces it.
            val header = metaLine(source).trim('*')
            if (header.isNotEmpty()) {
                appendLine(header)
                appendLine()
            }
        }

        tldr(source)?.let { lines ->
            when (format) {
                ExportFormat.PLAIN_TEXT -> {
                    appendLine("TL;DR:")
                    lines.forEach { appendLine(stripMarkdown(it)) }
                }
                ExportFormat.HUMAN_READABLE -> {
                    appendLine("**TL;DR**")
                    lines.forEach { appendLine(it) }
                }
                ExportFormat.LLM_OPTIMIZED -> {
                    appendLine("> [!summary] TL;DR")
                    lines.forEach { appendLine("> $it") }
                }
            }
            appendLine()
        }

        if (format == ExportFormat.LLM_OPTIMIZED) {
            val meta = metaLine(source)
            if (meta.isNotEmpty()) {
                appendLine(meta)
                appendLine()
            }
        }

        val annotate = format == ExportFormat.LLM_OPTIMIZED && source.showSources
        val summary = source.summary
        when {
            source.bodyOverride != null -> {
                appendLine(plainize(source.bodyOverride.trim(), format))
                appendLine()
            }
            summary != null -> appendSummary(summary, annotate, format)
            else -> {
                if (source.flatBody.isNotBlank()) {
                    appendLine(plainize(source.flatBody.trim(), format))
                    appendLine()
                }
            }
        }

        if (source.recipeOutputs.isNotEmpty()) {
            appendLine(heading("Recipe outputs", 2, format))
            appendLine()
            source.recipeOutputs.forEach { (name, text) ->
                appendLine(heading(name, 3, format))
                appendLine()
                appendLine(plainize(text.trim(), format))
                appendLine()
            }
        }

        // Photo-export feature: LLM-optimized lists photos as frontmatter metadata instead
        // (pure token cost otherwise, for a model with no vision context in this pipeline) —
        // see [frontmatter]. Human/plain formats get a section here.
        if (source.photos.isNotEmpty() && format != ExportFormat.LLM_OPTIMIZED) {
            appendLine(heading("Photos", 2, format))
            appendLine()
            source.photos.forEach { photo ->
                val time = photoTime(photo.takenAtEpochMs)
                appendLine(
                    when (format) {
                        ExportFormat.HUMAN_READABLE -> "![${photo.filename}](photos/${photo.filename})  *$time*"
                        else -> "${photo.filename}  $time"
                    },
                )
            }
            appendLine()
        }

        if (source.transcript.any { it.text.isNotBlank() }) {
            if (format != ExportFormat.PLAIN_TEXT) appendLine("---")
            appendLine()
            appendLine(transcriptFooter(source, format))
            appendLine()
        }
    }.trimEnd() + "\n"

    /**
     * YAML frontmatter. This is the part another model reads first and the part Obsidian
     * queries against, so it carries everything answerable without parsing prose: what this
     * was, when, how long, who, what it's about, and how much is actionable. LLM-optimized
     * only — see [ExportFormat].
     */
    private fun frontmatter(source: Source): String = buildString {
        val created = Date(source.createdAtEpochMs)
        appendLine("---")
        appendLine("title: ${yaml(source.title)}")
        appendLine("date: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(created)}")
        appendLine("time: ${SimpleDateFormat("HH:mm", Locale.US).format(created)}")
        appendLine("created: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(created)}")
        appendLine("duration: ${humanDuration(source.durationMs)}")
        appendLine("duration_ms: ${source.durationMs}")
        source.meetingTitle?.let { appendLine("meeting: ${yaml(it)}") }
        if (source.attendees.isNotEmpty()) {
            appendLine("attendees: [${source.attendees.joinToString(", ") { yaml(it) }}]")
        }
        source.template?.let { appendLine("template: ${yaml(it)}") }
        if (source.capturedInCall) appendLine("in_call: true")

        val topics = topics(source)
        if (topics.isNotEmpty()) appendLine("topics: [${topics.joinToString(", ")}]")

        val actions = source.summary?.actionItems.orEmpty()
        appendLine("action_items: ${actions.size}")
        if (actions.any { it.source == Provenance.FRAGMENT }) appendLine("has_own_commitments: true")

        val lines = source.transcript.count { it.text.isNotBlank() }
        appendLine("transcript_lines: $lines")
        if (lines > 0) {
            appendLine(
                "transcript: \"[[${source.transcriptLink}]]\"",
            )
        }
        // Photo-export feature: metadata only here, never an inline image — a model with no
        // vision context gets nothing from a markdown image link but the token cost.
        if (source.photos.isNotEmpty()) {
            val entries = source.photos.joinToString(", ") { photo ->
                "{name: ${yaml(photo.filename)}, taken: ${yaml(photoTime(photo.takenAtEpochMs))}}"
            }
            appendLine("photos: [$entries]")
        }
        appendLine("source: trailmix")
        appendLine("tags: [trailmix/note${if (source.meetingTitle != null) ", trailmix/meeting" else ""}]")
        appendLine("---")
    }

    /**
     * Up to three lines that answer "what happened?" without opening anything else — model
     * highlights when present, else the highest-coverage bullets, plus a count of what needs
     * doing. Deterministic: no model call, so it exists on every note.
     */
    private fun tldr(source: Source): List<String>? {
        val summary = source.summary ?: return null
        val picks = summary.highlights.map { it.text }.ifEmpty {
            val pool = summary.sections.flatMap { it.bullets }.map { it.text }
            SummaryText.selectDistinct(pool, TLDR_LINES)
        }.take(TLDR_LINES)
        if (picks.isEmpty()) return null

        val lines = picks.map { "- ${it.trim()}" }.toMutableList()
        val actions = summary.actionItems.size
        if (actions > 0) lines += "- **$actions action item${if (actions == 1) "" else "s"}** below."
        return lines
    }

    /** Compact context line — everything a reader needs before the first heading. */
    private fun metaLine(source: Source): String {
        val parts = buildList {
            add(SimpleDateFormat("EEE d MMM yyyy · h:mm a", Locale.US).format(Date(source.createdAtEpochMs)))
            add(humanDuration(source.durationMs))
            source.meetingTitle?.let { add(it) }
            if (source.capturedInCall) add("in call")
            if (source.attendees.isNotEmpty()) add(source.attendees.joinToString(", "))
        }
        return if (parts.isEmpty()) "" else "*${parts.joinToString(" · ")}*"
    }

    private fun titleLine(title: String, format: ExportFormat): String =
        if (format == ExportFormat.PLAIN_TEXT) title else "# $title"

    /** LLM/human formats keep Markdown headers; plain text drops the syntax entirely. */
    private fun heading(text: String, level: Int, format: ExportFormat): String = when (format) {
        ExportFormat.PLAIN_TEXT -> if (level <= 2) text.uppercase(Locale.US) else text
        else -> "#".repeat(level) + " " + text
    }

    private fun transcriptFooter(source: Source, format: ExportFormat): String = when (format) {
        ExportFormat.LLM_OPTIMIZED -> "📄 **Full transcript:** [[${source.transcriptLink}]]"
        ExportFormat.HUMAN_READABLE -> "📄 Full transcript: ${source.transcriptLink}.md"
        ExportFormat.PLAIN_TEXT -> "Full transcript: ${source.transcriptLink}.txt"
    }

    private fun StringBuilder.appendSummary(summary: StructuredSummary, annotate: Boolean, format: ExportFormat) {
        if (summary.highlights.isNotEmpty()) {
            appendLine(heading("Highlights", 2, format))
            appendLine()
            summary.highlights.forEach { appendLine(bullet(it, annotate)) }
            appendLine()
        }

        // What the user typed is their own material, not a subsection of the speaker's
        // argument — it gets its own H2 above the talk itself.
        val (own, spoken) = summary.sections.partition { section ->
            section.bullets.isNotEmpty() && section.bullets.all { it.source == Provenance.FRAGMENT }
        }
        own.forEach { section ->
            appendLine(heading(section.heading, 2, format))
            appendLine()
            section.bullets.forEach { appendLine(bullet(it, annotate)) }
            appendLine()
        }

        if (spoken.isNotEmpty()) {
            appendLine(heading("Key points", 2, format))
            appendLine()
            spoken.forEach { section ->
                appendLine(heading(section.heading, 3, format))
                appendLine()
                section.bullets.forEach { appendLine(bullet(it, annotate)) }
                appendLine()
            }
        }

        if (summary.actionItems.isNotEmpty()) {
            appendLine(heading("Action items", 2, format))
            appendLine()
            summary.actionItems.forEach { appendLine(actionItem(it, annotate, format)) }
            appendLine()
        }
    }

    private fun bullet(b: SummaryBullet, annotate: Boolean) =
        "- ${tag(b.source, b.timestampLabel, annotate)}${b.text.trim()}"

    private fun actionItem(item: ActionItem, annotate: Boolean, format: ExportFormat): String {
        val plain = format == ExportFormat.PLAIN_TEXT
        val suffix = buildString {
            item.owner?.let { append(if (plain) " — $it" else " — **$it**") }
            item.deadline?.let { append(if (plain) " (due $it)" else " *(due $it)*") }
        }
        val box = if (plain) "[ ] " else "- [ ] "
        return "$box${tag(item.source, item.timestampLabel, annotate)}${item.text.trim()}$suffix"
    }

    /** AI-05's provenance marker: `[you]` for typed, `[mm:ss]` for spoken. Gated off for the
     * human/plain formats regardless of [Source.showSources] — see [ExportFormat]. */
    private fun tag(source: Provenance, timestampLabel: String?, annotate: Boolean): String {
        if (!annotate) return ""
        return when {
            source == Provenance.FRAGMENT -> "**`[you]`** "
            timestampLabel != null -> "**`[$timestampLabel]`** "
            else -> "**`[transcript]`** "
        }
    }

    // ── Transcript companion file ───────────────────────────────────────────

    /**
     * The verbatim record, in its own file so it never competes for space with the summary.
     * Carries enough frontmatter to stand alone if it's moved somewhere else entirely, which
     * is the stated point of splitting it out (LLM-optimized and human-readable only — plain
     * text drops the table for flat `HH:MM  text` lines, see [ExportFormat]).
     */
    fun buildTranscript(source: Source, format: ExportFormat = ExportFormat.LLM_OPTIMIZED): String = buildString {
        if (format == ExportFormat.PLAIN_TEXT) {
            appendLine("${source.title} — transcript")
            appendLine()
            source.transcript.filter { it.text.isNotBlank() }.forEach {
                val label = it.label.ifBlank { "--" }
                appendLine("$label  ${it.text.trim()}")
            }
            return@buildString
        }

        if (format == ExportFormat.LLM_OPTIMIZED) {
            append(transcriptFrontmatter(source))
            appendLine()
        }
        appendLine("# ${source.title} — transcript")
        appendLine()
        val summaryLink = if (format == ExportFormat.LLM_OPTIMIZED) "[[${source.noteLink}]]" else source.noteLink
        appendLine("*Verbatim, on-device. Summary: $summaryLink*")
        appendLine()
        appendLine("| Time | Text |")
        appendLine("|---|---|")
        source.transcript.filter { it.text.isNotBlank() }.forEach {
            val label = it.label.ifBlank { "—" }
            appendLine("| `$label` | ${it.text.trim().replace("|", "\\|")} |")
        }
    }.trimEnd() + "\n"

    private fun transcriptFrontmatter(source: Source): String = buildString {
        val created = Date(source.createdAtEpochMs)
        appendLine("---")
        appendLine("title: ${yaml(source.title + " — transcript")}")
        appendLine("date: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(created)}")
        appendLine("duration: ${humanDuration(source.durationMs)}")
        source.meetingTitle?.let { appendLine("meeting: ${yaml(it)}") }
        appendLine("note: \"[[${source.noteLink}]]\"")
        appendLine("type: transcript")
        appendLine("source: trailmix")
        appendLine("tags: [trailmix/transcript]")
        appendLine("---")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Distinctive terms across the note, for frontmatter `topics:`. */
    private fun topics(source: Source): List<String> {
        val text = buildString {
            source.summary?.let { s ->
                s.highlights.forEach { appendLine(it.text) }
                s.sections.forEach { sec -> sec.bullets.forEach { appendLine(it.text) } }
                s.actionItems.forEach { appendLine(it.text) }
            }
            if (isBlank()) append(source.flatBody)
        }
        return SummaryText.keywords(text, limit = MAX_TOPICS)
    }

    fun humanDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            else -> "<1m"
        }
    }

    private fun photoTime(epochMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(epochMs))

    /** Quote a YAML scalar only when it needs it, so the common case stays readable. */
    private fun yaml(value: String): String {
        val v = value.trim()
        val needsQuote = v.isEmpty() || v.first().isWhitespace() || v.last().isWhitespace() ||
            v.any { it in ":#[]{},&*!|>'\"%@`" } || v.first() in "-?" ||
            v.lowercase(Locale.US) in setOf("true", "false", "null", "yes", "no", "on", "off")
        return if (needsQuote) "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else v
    }

    /** Plain text only: strip bold/code/wikilink syntax out of otherwise free-form text. */
    private fun plainize(text: String, format: ExportFormat): String =
        if (format == ExportFormat.PLAIN_TEXT) stripMarkdown(text) else text

    private fun stripMarkdown(text: String): String = text
        .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[\\[([^\\]|]*)\\]\\]"), "$1")

    private const val TLDR_LINES = 3
    private const val MAX_TOPICS = 6
}
