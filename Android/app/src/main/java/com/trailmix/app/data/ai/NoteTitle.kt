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
}
