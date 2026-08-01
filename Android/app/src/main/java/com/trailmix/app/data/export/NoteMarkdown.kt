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
 * the transcript stays one link away.
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

    fun noteFileName(title: String, createdAtEpochMs: Long) = "${baseName(title, createdAtEpochMs)}.md"

    fun transcriptFileName(title: String, createdAtEpochMs: Long) =
        "${baseName(title, createdAtEpochMs)}.transcript.md"

    // ── Summary note ────────────────────────────────────────────────────────

    fun buildNote(source: Source): String = buildString {
        append(frontmatter(source))
        appendLine()
        appendLine("# ${source.title}")
        appendLine()

        tldr(source)?.let {
            appendLine("> [!summary] TL;DR")
            it.forEach { line -> appendLine("> $line") }
            appendLine()
        }

        val meta = metaLine(source)
        if (meta.isNotEmpty()) {
            appendLine(meta)
            appendLine()
        }

        val summary = source.summary
        when {
            source.bodyOverride != null -> {
                appendLine(source.bodyOverride.trim())
                appendLine()
            }
            summary != null -> appendSummary(summary, source.showSources)
            else -> {
                if (source.flatBody.isNotBlank()) {
                    appendLine(source.flatBody.trim())
                    appendLine()
                }
            }
        }

        if (source.recipeOutputs.isNotEmpty()) {
            appendLine("## Recipe outputs")
            appendLine()
            source.recipeOutputs.forEach { (name, text) ->
                appendLine("### $name")
                appendLine()
                appendLine(text.trim())
                appendLine()
            }
        }

        if (source.transcript.any { it.text.isNotBlank() }) {
            appendLine("---")
            appendLine()
            appendLine("📄 **Full transcript:** [[${source.transcriptLink}]]")
            appendLine()
        }
    }.trimEnd() + "\n"

    /**
     * YAML frontmatter. This is the part another model reads first and the part Obsidian
     * queries against, so it carries everything answerable without parsing prose: what this
     * was, when, how long, who, what it's about, and how much is actionable.
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

    private fun StringBuilder.appendSummary(summary: StructuredSummary, annotate: Boolean) {
        if (summary.highlights.isNotEmpty()) {
            appendLine("## Highlights")
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
            appendLine("## ${section.heading}")
            appendLine()
            section.bullets.forEach { appendLine(bullet(it, annotate)) }
            appendLine()
        }

        if (spoken.isNotEmpty()) {
            appendLine("## Key points")
            appendLine()
            spoken.forEach { section ->
                appendLine("### ${section.heading}")
                appendLine()
                section.bullets.forEach { appendLine(bullet(it, annotate)) }
                appendLine()
            }
        }

        if (summary.actionItems.isNotEmpty()) {
            appendLine("## Action items")
            appendLine()
            summary.actionItems.forEach { appendLine(actionItem(it, annotate)) }
            appendLine()
        }
    }

    private fun bullet(b: SummaryBullet, annotate: Boolean) =
        "- ${tag(b.source, b.timestampLabel, annotate)}${b.text.trim()}"

    private fun actionItem(item: ActionItem, annotate: Boolean): String {
        val suffix = buildString {
            item.owner?.let { append(" — **$it**") }
            item.deadline?.let { append(" *(due $it)*") }
        }
        return "- [ ] ${tag(item.source, item.timestampLabel, annotate)}${item.text.trim()}$suffix"
    }

    /** AI-05's provenance marker: `[you]` for typed, `[mm:ss]` for spoken. */
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
     * is the stated point of splitting it out.
     */
    fun buildTranscript(source: Source): String = buildString {
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
        appendLine()
        appendLine("# ${source.title} — transcript")
        appendLine()
        appendLine("*Verbatim, on-device. Summary: [[${source.noteLink}]]*")
        appendLine()
        appendLine("| Time | Text |")
        appendLine("|---|---|")
        source.transcript.filter { it.text.isNotBlank() }.forEach {
            val label = it.label.ifBlank { "—" }
            appendLine("| `$label` | ${it.text.trim().replace("|", "\\|")} |")
        }
    }.trimEnd() + "\n"

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

    /** Quote a YAML scalar only when it needs it, so the common case stays readable. */
    private fun yaml(value: String): String {
        val v = value.trim()
        val needsQuote = v.isEmpty() || v.first().isWhitespace() || v.last().isWhitespace() ||
            v.any { it in ":#[]{},&*!|>'\"%@`" } || v.first() in "-?" ||
            v.lowercase(Locale.US) in setOf("true", "false", "null", "yes", "no", "on", "off")
        return if (needsQuote) "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else v
    }

    private const val TLDR_LINES = 3
    private const val MAX_TOPICS = 6
}
