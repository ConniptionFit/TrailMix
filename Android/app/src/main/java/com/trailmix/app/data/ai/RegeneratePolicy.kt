package com.trailmix.app.data.ai

import com.trailmix.app.data.model.TranscriptLine

/**
 * UX-39/UX-40: pure decisions for re-running the merge over an already-saved note. Kept free
 * of Android so the rules are unit-testable; the session manager's `regenerateNote` and Note
 * Detail both ask this rather than restating the conditions.
 */
object RegeneratePolicy {
    /** Why a regeneration cannot start. */
    enum class Refusal {
        /** A capture, a paused capture, a merge or a crash-recovery merge is already running. */
        BUSY,

        /** Neither typed notes nor a transcript — the same CAP-11 rule that saves nothing. */
        NOTHING_TO_MERGE,
    }

    /** The synchronous half of [refusal] — decidable before the note has even been loaded. */
    fun isBusy(recording: Boolean, paused: Boolean, merging: Boolean, recovering: Boolean): Boolean =
        recording || paused || merging || recovering

    /**
     * Null = go ahead. Every capture entry point early-returns on `merging`, and a second
     * merge sharing the foreground-service phase / merge-progress status would corrupt the
     * first's, so any live session or merge refuses rather than queues.
     */
    fun refusal(
        recording: Boolean,
        paused: Boolean,
        merging: Boolean,
        recovering: Boolean,
        typedFragments: String,
        transcript: List<TranscriptLine>,
    ): Refusal? = when {
        isBusy(recording, paused, merging, recovering) -> Refusal.BUSY
        MergePolicy.nothingToSave(typedFragments, transcript) -> Refusal.NOTHING_TO_MERGE
        else -> null
    }

    /** Regenerating discards a hand-edited body (`bodyOverride`), so that needs a confirm. */
    fun needsOverwriteConfirm(bodyOverride: String?): Boolean = bodyOverride != null

    /**
     * N5: how many hand edits a rebuild would replace. A flat hand-edited body counts as one;
     * structured edits count each changed heading, point and step. Zero means a plain "Rebuild".
     */
    fun editsToReplace(bodyOverride: String?, structuredEdits: Int): Int =
        structuredEdits + if (bodyOverride != null) 1 else 0

    /** True when the raw-notes draft differs from what is stored (ignoring trailing blanks). */
    fun fragmentsChanged(stored: String, draft: String): Boolean = stored.trimEnd() != draft.trimEnd()

    /** Human-readable failure line for the snackbar. */
    fun failureMessage(refusal: Refusal?): String = when (refusal) {
        Refusal.BUSY -> "Finish or stop the current capture first."
        Refusal.NOTHING_TO_MERGE -> "Nothing to enhance — this note has no raw notes or transcript."
        null -> "Couldn't regenerate this note. Your existing note is unchanged."
    }
}
