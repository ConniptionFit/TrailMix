package com.trailmix.app.data.ai

import com.trailmix.app.data.ai.NoteAnchors.Anchor
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TemplateSpec
import java.util.Locale

/**
 * AI-22: the Granola "shape" of a default note, enforced in plain Kotlin. The prompt can only
 * *ask* a small on-device model for 3-6 sections with short topic names; the no-AI path has no
 * model at all, so both feed their result through [apply] (the [NoteTitle.clean] philosophy:
 * a prompt instruction is a request, the constraint lives here).
 *
 * What [apply] guarantees, in order:
 *  1. Headings are cleaned (markdown, numbering, "Topic:" labels, a leading time range,
 *     trailing punctuation) and Title Cased.
 *  2. Exact and near-duplicate bullets are removed across sections; empty sections are dropped.
 *  3. When the template has **no fixed sections**: a very short note (<= [SHORT_NOTE_BULLETS]
 *     bullets) collapses to one section, and anything above [MAX_TOPIC_SECTIONS] has its
 *     smallest mergeable section folded into its nearest mergeable neighbour.
 *  4. Generic headings ("Discussion 2", "Summary", a bare time range...) are replaced by a 2-4
 *     word Title Case heading built from the section's most distinctive keywords.
 *
 * **Locked sections** are never retitled, deduplicated against, or merged away: a section named
 * for one of the user's typed [anchors] (AI-17), "Open Questions" / "Your Take" / "Your notes",
 * and any section of a section-based template (AI-19). Merging only ever moves bullets, so no
 * content is lost; bullet *text* capping stays with [StructuredSummaryPrompt.capWords] (AI-18).
 *
 * Highlights and action items pass through untouched. Pure and Android-free.
 */
object NoteShape {

    /** Granola notes read as 3-6 topic blocks; past this the smallest topics are folded together. */
    const val MAX_TOPIC_SECTIONS = 6

    /** A note this small is one block, not several one-bullet sections. */
    const val SHORT_NOTE_BULLETS = 3

    private const val YOUR_NOTES = "Your notes"
    private const val DUPLICATE_OVERLAP = 0.85
    private const val ANCHOR_HEADING_OVERLAP = 0.6
    private const val MAX_HEADING_TERMS = 3
    private const val RELAXED_TERMS = 2

    private class Item(var section: SummarySection, val locked: Boolean)

    private val NEXT_STEPS_HEADINGS = setOf(
        "next steps", "action items", "actions", "to do", "todo", "follow ups", "follow up", "tasks",
    )

    fun apply(
        summary: StructuredSummary,
        anchors: List<Anchor> = emptyList(),
        spec: TemplateSpec? = null,
    ): StructuredSummary {
        if (summary.sections.isEmpty()) return summary
        val fixedSections = spec != null && spec.hasSections

        var items = summary.sections.map { s ->
            val locked = isLocked(s, anchors, spec)
            Item(if (locked) s else s.copy(heading = cleanHeading(s.heading)), locked)
        }
        items = dedupe(items).filter { it.section.bullets.isNotEmpty() }
        // The note always renders its own Next Steps list; a model-made section of the same name
        // would show the actions twice. Kept when there are no real action items to stand in for it.
        if (summary.actionItems.isNotEmpty()) {
            items = items.filterNot { !it.locked && normalize(it.section.heading) in NEXT_STEPS_HEADINGS }
        }
        if (!fixedSections) {
            items = collapseShort(items)
            items = capSections(items)
        }
        items = retitle(items)
        return summary.copy(sections = items.map { it.section })
    }

    // ── headings ─────────────────────────────────────────────────────────────

    private val MARKDOWN = Regex("[*_`#>]+")
    private val TIME = "\\d{1,2}:\\d{2}(?::\\d{2})?"
    private val TIME_RANGE_PREFIX = Regex("^\\s*$TIME\\s*(?:[–—-]|to)\\s*$TIME\\s*[·:–—-]?\\s*", RegexOption.IGNORE_CASE)
    private val NUMBERING = Regex("^\\s*(?:[-•]\\s*)?(?:\\d+[.)]|\\(\\d+\\))\\s+")
    private val LABEL = Regex("^(?:topic|section|heading|title)\\s*\\d*\\s*[–—\\-:]\\s*", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("\\s{2,}")

    /** Strip markdown, numbering, label prefixes, a leading time range and trailing punctuation; Title Case. */
    fun cleanHeading(raw: String): String {
        var s = raw.replace(MARKDOWN, "").trim()
        s = s.replace(TIME_RANGE_PREFIX, "")
        s = s.replace(NUMBERING, "")
        s = s.replace(LABEL, "")
        s = s.replace(MULTI_SPACE, " ").trim().trimEnd(':', '.', '-', '–', '—', ',', ';', '·', ' ')
        return titleCase(s)
    }

    private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "with")

    /** Title Case that leaves acronyms and mixed-case words (ARR, iOS, OAuth) exactly as written. */
    fun titleCase(text: String): String =
        text.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, word ->
            val keepAsWritten = word.drop(1).any { it.isUpperCase() }
            when {
                keepAsWritten -> word
                i > 0 && word.lowercase(Locale.ROOT) in SMALL_WORDS -> word.lowercase(Locale.ROOT)
                else -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }.joinToString(" ")

    private val GENERIC_HEADINGS = setOf(
        "discussion", "topic", "topics", "section", "part", "summary", "notes", "note", "key points",
        "key topics", "main points", "points", "other", "general", "overview", "misc",
        "miscellaneous", "highlights", "details", "meeting notes", "meeting summary", "agenda",
    )

    /** True for a heading that names nothing: blank, a bare time range, or one of the stock labels. */
    fun isGenericHeading(heading: String): Boolean {
        val bare = cleanHeading(heading).lowercase(Locale.ROOT)
            .replace(Regex("\\b\\d+\\b"), " ").replace(MULTI_SPACE, " ").trim()
        return bare.isEmpty() || bare in GENERIC_HEADINGS || Regex("^$TIME\\s*(?:[–—-]|to)\\s*$TIME$").matches(bare)
    }

    /**
     * 2-4 word Title Case heading from up to three distinctive terms ("Routing", "Routing and
     * Tables", "Routing, Tables and Latency"), or null when there are no terms.
     */
    fun keywordHeading(terms: List<String>): String? {
        val t = terms.take(MAX_HEADING_TERMS).map { titleCase(it) }
        return when (t.size) {
            0 -> null
            1 -> t[0]
            2 -> "${t[0]} and ${t[1]}"
            else -> "${t[0]}, ${t[1]} and ${t[2]}"
        }
    }

    /**
     * The most distinctive terms in [text]: [SummaryText.keywords] first (repeated, long words),
     * relaxed to the most frequent longer words when the text is too short to repeat anything.
     */
    fun topicTerms(text: String, exclude: Set<String> = emptySet()): List<String> {
        val strict = SummaryText.keywords(text, MAX_HEADING_TERMS, exclude)
        if (strict.size >= 2) return strict
        val order = LinkedHashMap<String, Int>()
        // Relaxed mode has no repetition to lean on, so skip likely verbs/adverbs ("reviewed",
        // "quickly") — a heading of nouns reads as a topic, a heading of verbs reads as noise.
        SummaryText.contentWords(text)
            .filter { it.length >= 4 && it !in exclude && !it.endsWith("ed") && !it.endsWith("ly") }
            .forEach { order.merge(it, 1, Int::plus) }
        val relaxed = order.entries.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Int>>> { it.value.value }.thenBy { it.index })
            .map { it.value.key }
            .take(RELAXED_TERMS)
        return if (relaxed.size > strict.size) relaxed else strict
    }

    /** Topic heading for [text] (a window or a section's bullets), or null when nothing distinctive. */
    fun topicHeading(text: String, exclude: Set<String> = emptySet()): String? =
        keywordHeading(topicTerms(text, exclude))

    // ── locking ──────────────────────────────────────────────────────────────

    private fun isLocked(section: SummarySection, anchors: List<Anchor>, spec: TemplateSpec?): Boolean {
        val heading = section.heading.trim()
        if (heading.equals(AnchorCoverage.OPEN_QUESTIONS, true) ||
            heading.equals(AnchorCoverage.YOUR_TAKE, true) ||
            heading.equals(YOUR_NOTES, true)
        ) {
            return true
        }
        if (spec != null && spec.hasSections && spec.sections.any { TemplateSectioner.headingMatches(heading, it.heading) }) {
            return true
        }
        if (anchors.isEmpty()) return false
        val words = QuoteMatch.tokenize(heading)
        val named = anchors.any { a ->
            normalize(a.text) == normalize(heading) ||
                QuoteMatch.tokenize(a.text).let { at ->
                    words.isNotEmpty() && at.isNotEmpty() &&
                        QuoteMatch.overlapScore(words, at) >= ANCHOR_HEADING_OVERLAP &&
                        QuoteMatch.overlapScore(at, words) >= ANCHOR_HEADING_OVERLAP
                }
        }
        return named || section.bullets.any { StructuredSummaryPrompt.isVerbatimAnchor(it.text, anchors) }
    }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(MULTI_SPACE, " ").trim()

    // ── dedupe ───────────────────────────────────────────────────────────────

    /** Locked sections keep every bullet (and are registered first); unlocked ones lose repeats. */
    private fun dedupe(items: List<Item>): List<Item> {
        val seen = mutableListOf<Pair<String, Set<String>>>()
        fun register(b: SummaryBullet) = seen.add(normalize(b.text) to QuoteMatch.tokenize(b.text))
        items.filter { it.locked }.forEach { it.section.bullets.forEach(::register) }
        for (item in items.filterNot { it.locked }) {
            val kept = item.section.bullets.filter { b ->
                val norm = normalize(b.text)
                val words = QuoteMatch.tokenize(b.text)
                val duplicate = seen.any { (n, w) -> n == norm || isNearDuplicate(words, w) }
                if (!duplicate) seen.add(norm to words)
                !duplicate
            }
            item.section = item.section.copy(bullets = kept)
        }
        return items
    }

    private fun isNearDuplicate(a: Set<String>, b: Set<String>): Boolean =
        a.size >= 3 && b.size >= 3 &&
            QuoteMatch.overlapScore(a, b) >= DUPLICATE_OVERLAP &&
            QuoteMatch.overlapScore(b, a) >= DUPLICATE_OVERLAP

    // ── structure ────────────────────────────────────────────────────────────

    private fun collapseShort(items: List<Item>): List<Item> {
        if (items.size < 2 || items.any { it.locked }) return items
        if (items.sumOf { it.section.bullets.size } > SHORT_NOTE_BULLETS) return items
        val first = items.first().section
        return listOf(Item(first.copy(bullets = items.flatMap { it.section.bullets }), locked = false))
    }

    private fun capSections(items: List<Item>): List<Item> {
        val list = items.toMutableList()
        while (list.size > MAX_TOPIC_SECTIONS) {
            val mergeable = list.indices.filter { !list[it].locked }
            if (mergeable.size < 2) break
            val smallest = mergeable.minWith(compareBy({ list[it].section.bullets.size }, { it }))
            // Nearest mergeable neighbour by position; on a tie the earlier one wins.
            val neighbour = mergeable.filter { it != smallest }
                .minWith(compareBy({ kotlin.math.abs(it - smallest) }, { it }))
            val small = list[smallest].section
            val target = list[neighbour].section
            val merged = if (neighbour < smallest) target.bullets + small.bullets else small.bullets + target.bullets
            list[neighbour] = Item(target.copy(bullets = merged), locked = false)
            list.removeAt(smallest)
        }
        return list
    }

    private fun retitle(items: List<Item>): List<Item> {
        val texts = items.map { it.section.bullets.joinToString(" ") { b -> b.text } }
        val common = SummaryText.commonTerms(texts)
        val used = items.filter { it.locked || !isGenericHeading(it.section.heading) }
            .mapTo(mutableSetOf()) { it.section.heading.lowercase(Locale.ROOT) }
        items.forEachIndexed { i, item ->
            if (item.locked || !isGenericHeading(item.section.heading)) return@forEachIndexed
            val terms = topicTerms(texts[i], common).ifEmpty { topicTerms(texts[i]) }
            // Prefer a heading no other section already has; drop the leading term to find one.
            val heading = (0 until terms.size.coerceAtLeast(1))
                .mapNotNull { keywordHeading(terms.drop(it)) }
                .firstOrNull { it.lowercase(Locale.ROOT) !in used }
                ?: keywordHeading(terms)
                ?: firstWords(item.section.bullets.firstOrNull()?.text)
                ?: cleanHeading(item.section.heading).ifBlank { "Notes" }
            used += heading.lowercase(Locale.ROOT)
            item.section = item.section.copy(heading = heading)
        }
        return items
    }

    private fun firstWords(text: String?): String? =
        text?.let { SummaryText.contentWords(it).take(3).joinToString(" ") }?.takeIf { it.isNotBlank() }?.let(::titleCase)
}
