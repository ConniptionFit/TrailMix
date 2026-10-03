package com.trailmix.app.data.speech

/**
 * REL-10: what the merge is doing right now.
 *
 * A merge used to be an instant at the end of a capture. At conference scale it is not:
 * `LongSessionLoadTest` sizes a 90-minute keynote at 6 model chunks plus a structuring pass,
 * each one an AICore `generateContent` call, so the wait is **minutes**. A frozen
 * "Merging on-device…" for that long reads as a hang, and the user's remedy for a hang is to
 * force-stop the app — which is exactly the thing that used to destroy the merge. Progress is
 * therefore not decoration here; it is what stops someone killing the work.
 *
 * Kept as a pure value type with no Android dependency so the label logic is unit-testable —
 * the notification and the Capture screen both render [label], and they must never disagree.
 */
data class MergeStatus(
    /** The note being written into — same title the capture notification was showing. */
    val title: String,
    val chunksDone: Int = 0,
    val chunksTotal: Int = 0,
) {
    /**
     * Three honest states, not a percentage:
     * - not yet chunked, or a short session that fits one model call → indeterminate;
     * - working through chunk N of M;
     * - chunks all condensed, final structuring pass running.
     *
     * `chunksDone + 1` is the chunk being worked on, which is what someone watching a
     * progress line expects to see — it counts 1..M rather than 0..M-1.
     */
    fun label(): String = when {
        chunksTotal <= 1 -> MERGING
        chunksDone < chunksTotal -> "Summarizing ${chunksDone + 1} of $chunksTotal…"
        else -> WRITING
    }

    /** C8: 0..1 for a determinate bar, or null while only "working" can be said honestly. */
    fun progress(): Float? =
        if (indeterminate) null else (chunksDone.toFloat() / chunksTotal).coerceIn(0f, 1f)

    /** C8: "3 of 6" for the counter beside the title, or null when [indeterminate]. */
    fun counter(): String? =
        if (indeterminate) null else "${(chunksDone + 1).coerceAtMost(chunksTotal)} of $chunksTotal"

    /** C8: what the current step is called in the step list; no counter, no ellipsis. */
    fun stage(): MergeStage = when {
        chunksTotal <= 1 -> MergeStage.WORKING
        chunksDone < chunksTotal -> MergeStage.SUMMARIZING
        else -> MergeStage.WRITING
    }

    /** True while there is no meaningful fraction to draw — the bar should spin, not fill. */
    val indeterminate: Boolean get() = chunksTotal <= 1

    companion object {
        const val MERGING = "Merging on-device…"
        const val WRITING = "Writing the note…"
    }
}

/** C7/C8: the coarse stages the UI lists. Derived from [MergeStatus], never stored. */
enum class MergeStage { WORKING, SUMMARIZING, WRITING }
