package com.trailmix.app.data.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ownership of the auto-generated note title, and the ability to recognise one again later.
 *
 * Pure and Android-free so it can be unit tested (same idiom as [TranscriptCoverage] and
 * [SummaryText]).
 *
 * The recognition half exists because of a real regression (AI-06, found on-device
 * 2026-08-01): a note that already had a good title would silently revert to
 * `Note — Aug 1, 3:12 PM` whenever a *re-merge* fell back to the deterministic path,
 * because that path has no model to name things with and always emitted a fresh default.
 * Losing the title also desynchronised the exported filenames from the wiki-links inside
 * them (OBS-03), so the damage was not merely cosmetic.
 */
object NoteTitle {

    /**
     * Prefix of every auto-generated title. Deliberately matched as a literal prefix rather
     * than by re-formatting a timestamp: [isDefault] must still recognise a title generated
     * on a different day, in a different timezone, or under a different default [Locale]
     * than the one asking.
     */
    private const val DEFAULT_PREFIX = "Note — "

    /** The placeholder title used when nothing better can be derived from the content. */
    fun default(epochMs: Long): String =
        DEFAULT_PREFIX + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMs))

    /**
     * True when [title] is an auto-generated placeholder rather than something derived from
     * the note's content. Blank counts as default — an absent title carries no more meaning
     * than a placeholder one.
     */
    fun isDefault(title: String): Boolean =
        title.isBlank() || title.trimStart().startsWith(DEFAULT_PREFIX)

    /**
     * Pick the title to persist when re-merging an existing note.
     *
     * A meaningful title already on the note outranks a freshly generated placeholder: the
     * deterministic fallback cannot name a note, so letting it overwrite a real title is
     * pure loss. In every other case the incoming title wins, so a genuine re-title (the AI
     * produced a better name, or the content changed) still takes effect.
     */
    fun preferExisting(incoming: String, existing: String): String =
        if (isDefault(incoming) && !isDefault(existing)) existing else incoming

    // ── AI-07: making a model's first line usable as a title ────────────────

    /**
     * Turn the model's first output line into an actual title.
     *
     * The merge prompt asks for "a short title (max 8 words), no markdown" and **nothing
     * enforced it** — the reply was cut with a blind `.take(80)`. Observed on-device
     * 2026-08-01: a note came out titled
     * `Crash recovery verification for trail mix. The identity platform keynote begins`,
     * i.e. the model echoed the transcript's opening two sentences and the 80-character cut
     * sliced the second one mid-clause. That is not cosmetic at conference scale: a dozen
     * sessions produce a dozen near-identical truncated openings, and the Home list, the
     * search index, and the exported *filenames* are all derived from this string.
     *
     * A prompt instruction is a request, not a constraint, so the constraint lives here:
     * strip the noise a model reasonably might emit (markdown, a `Title:` label, list
     * markers, wrapping quotes), keep the **first sentence** rather than the first N
     * characters, then cap by whole words. Truncation is marked with an ellipsis rather than
     * hidden — a title that stops mid-thought should look deliberate, not corrupt.
     *
     * Falls back to [default] when nothing usable survives, which keeps [isDefault] and
     * therefore AI-06's title protection working unchanged.
     */
    fun clean(raw: String, createdAtEpochMs: Long): String {
        val line = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        var t = LEADING_LABEL.replace(LEADING_ENUMERATION.replace(LEADING_NOISE.replace(line, ""), ""), "")
        // Paired emphasis and code ticks only — deliberately NOT a blanket strip of `_` and
        // `*`, which would mangle a legitimate identifier like `user_id mapping`.
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.trim().trim('*', '_')
        t = t.trim().trim('"', '\'', '“', '”', '‘', '’')
        t = WHITESPACE.replace(t, " ").trim()
        t = firstSentence(t).trim().trimEnd(*TRAILING_PUNCTUATION)

        var truncated = false
        val words = t.split(' ').filter { it.isNotBlank() }
        if (words.size > MAX_WORDS) {
            t = words.take(MAX_WORDS).joinToString(" ")
            truncated = true
        }
        if (t.length > MAX_CHARS) {
            val head = t.take(MAX_CHARS)
            // Never end on half a word — fall back to the hard cut only if there is no space
            // to break on at all (one pathological run-on token).
            t = head.substringBeforeLast(' ', head)
            truncated = true
        }
        t = t.trimEnd(*TRAILING_PUNCTUATION)
        if (truncated && t.isNotEmpty()) t += "…"

        return if (t.length < MIN_CHARS) default(createdAtEpochMs) else t
    }

    /**
     * Everything up to the first sentence-ending punctuation that is followed by a space or
     * the end of the string.
     *
     * The trailing-whitespace requirement is what stops `99.9% uptime regressions` and
     * `v1.13.0 release notes` from being guillotined at the decimal point. An abbreviation
     * mid-title (`Q3 rev. review`) is cut short, which is an accepted trade: a slightly
     * clipped title is a far smaller problem than a paragraph masquerading as one.
     */
    private fun firstSentence(s: String): String {
        val end = SENTENCE_END.find(s) ?: return s
        return s.substring(0, end.range.first)
    }

    /** Comfortably above the prompt's 8-word request, so a good title is never mangled. */
    private const val MAX_WORDS = 10
    private const val MAX_CHARS = 60
    private const val MIN_CHARS = 2

    private val LEADING_NOISE = Regex("^[\\s#>\\-*•]+")
    /**
     * A list marker like `1. ` or `2) `. The trailing `\s+` is **required**: without it this
     * matched the `99.` of a title beginning `99.9% uptime regressions` and silently ate it.
     */
    private val LEADING_ENUMERATION = Regex("^\\d{1,3}[.)]\\s+")
    private val LEADING_LABEL = Regex("^(title|note|subject|summary)\\s*[:\\-–]\\s*", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_END = Regex("[.!?](\\s|$)")
    private val TRAILING_PUNCTUATION = charArrayOf(' ', ',', ':', ';', '-', '–', '—', '.')
}
