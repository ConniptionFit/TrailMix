package com.trailmix.app.data.ai

import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SectionSpec
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TemplateSpec

/**
 * AI-19: makes a summary honour a section-based [TemplateSpec] in plain Kotlin. A prompt only
 * *asks* a small model for the template's headings (the [NoteTitle.clean] philosophy: enforce,
 * don't trust), and the no-AI path has no model at all, so both go through here:
 *
 *  - [conform] — AI path. Reorders the model's sections to the template's order under the
 *    template's own heading text, merges duplicates, appends the model's extra sections after
 *    the template ones, and (when the template says so) keeps empty sections as "Not discussed".
 *  - [arrange] — deterministic path. Places bullets into template sections by keyword overlap
 *    with each section's heading and instruction; anything that fits nothing stays in its
 *    original section after the template ones.
 *
 * A spec with no sections is returned untouched by both, so context-only and pre-AI-19
 * templates behave exactly as before. Pure and Android-free.
 */
object TemplateSectioner {

    const val NOT_DISCUSSED = "Not discussed"

    /** Score a bullet needs to be placed: one heading word (weight 2) or two instruction words. */
    private const val PLACE_MIN = 2

    fun conform(summary: StructuredSummary, spec: TemplateSpec): StructuredSummary {
        if (!spec.hasSections) return summary
        val remaining = summary.sections.toMutableList()
        val ordered = mutableListOf<SummarySection>()
        for (section in spec.sections) {
            val matches = remaining.filter { headingMatches(it.heading, section.heading) }
            remaining.removeAll(matches.toSet())
            val bullets = matches.flatMap { it.bullets }
            when {
                bullets.isNotEmpty() -> ordered += SummarySection(section.heading, bullets)
                !spec.omitEmptySections -> ordered += placeholder(section)
            }
        }
        return summary.copy(sections = ordered + remaining)
    }

    /**
     * Deterministic placement. [anchorSections] are the user's typed-note sections (the heading
     * is the user's own words, so a whole section moves together, keeping its heading as a typed
     * bullet so the note is still represented); [otherSections] are transcript-window sections,
     * whose bullets are placed one by one.
     */
    fun arrange(
        anchorSections: List<SummarySection>,
        otherSections: List<SummarySection>,
        spec: TemplateSpec,
    ): List<SummarySection> {
        if (!spec.hasSections) return anchorSections + otherSections
        val targets = spec.sections.map { Target(it, stems(it.heading), stems(it.instruction) - stems(it.heading)) }
        val placed = targets.map { mutableListOf<SummaryBullet>() }

        val leftoverAnchors = mutableListOf<SummarySection>()
        for (section in anchorSections) {
            val index = bestTargetForHeading(section.heading, targets)
            if (index == null) {
                leftoverAnchors += section
                continue
            }
            val own = headingMatches(section.heading, targets[index].spec.heading) ||
                section.bullets.any { it.text.equals(section.heading, ignoreCase = true) }
            if (!own) placed[index] += SummaryBullet(section.heading, Provenance.FRAGMENT)
            placed[index] += section.bullets
        }

        val leftoverOthers = otherSections.mapNotNull { section ->
            val kept = section.bullets.filter { bullet ->
                val index = bestTargetForText(bullet.text, targets)
                if (index == null) {
                    true
                } else {
                    placed[index] += bullet
                    false
                }
            }
            if (kept.isEmpty()) null else section.copy(bullets = kept)
        }

        val templateSections = targets.mapIndexedNotNull { i, t ->
            when {
                placed[i].isNotEmpty() -> SummarySection(t.spec.heading, placed[i])
                !spec.omitEmptySections -> placeholder(t.spec)
                else -> null
            }
        }
        return templateSections + leftoverAnchors + leftoverOthers
    }

    // ── matching ─────────────────────────────────────────────────────────────

    private class Target(val spec: SectionSpec, val head: Set<String>, val instr: Set<String>)

    private fun placeholder(section: SectionSpec) =
        SummarySection(section.heading, listOf(SummaryBullet(NOT_DISCUSSED, Provenance.TRANSCRIPT)))

    /** True when [heading] is (modulo case/punctuation) [target], or shares at least half of its words. */
    fun headingMatches(heading: String, target: String): Boolean {
        if (normalize(heading) == normalize(target)) return true
        val targetWords = stems(target)
        if (targetWords.isEmpty()) return false
        val words = stems(heading)
        return targetWords.count { it in words } * 2 >= targetWords.size && words.any { it in targetWords }
    }

    private fun bestTargetForHeading(heading: String, targets: List<Target>): Int? {
        targets.indexOfFirst { headingMatches(heading, it.spec.heading) }.takeIf { it >= 0 }?.let { return it }
        return bestTargetForText(heading, targets)
    }

    private fun bestTargetForText(text: String, targets: List<Target>): Int? {
        val words = stems(text)
        if (words.isEmpty()) return null
        var best = -1
        var bestScore = 0
        targets.forEachIndexed { i, t ->
            val score = 2 * words.count { it in t.head } + words.count { it in t.instr }
            if (score > bestScore) {
                best = i
                bestScore = score
            }
        }
        return if (bestScore >= PLACE_MIN) best else null
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Content words with a crude plural strip ("points" -> "point") so headings match prose. */
    private fun stems(text: String): Set<String> =
        QuoteMatch.tokenize(text).filter { it !in STOP }.mapTo(mutableSetOf()) { w ->
            if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
        }

    private val STOP = setOf(
        "the", "and", "for", "are", "was", "were", "with", "that", "this", "from", "have", "has",
        "had", "but", "not", "you", "your", "our", "its", "any", "can", "will", "about", "into",
        "than", "then", "them", "they", "their", "what", "when", "who", "how", "why", "which",
        "other", "person", "words", "name", "each", "one", "per", "either", "direction", "directions",
    )
}
