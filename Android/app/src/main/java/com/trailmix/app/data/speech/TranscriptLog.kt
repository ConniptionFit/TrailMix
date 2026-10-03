package com.trailmix.app.data.speech

import com.trailmix.app.data.model.TranscriptLine

/**
 * CAP-25: the session's transcript lines and flagged moments — previously two plain
 * `mutableListOf<>()` fields directly on [CaptureSessionManager], mutated and snapshotted from
 * several different places: the listen-session collect loop and the rolling-summary/merge jobs
 * (all `scope.launch {}` on [CaptureSessionManager]'s scope, whose context has no dispatcher —
 * meaning they run on the shared, genuinely multithreaded `Dispatchers.Default` pool, so two of
 * those coroutines can execute on different OS threads at the same instant) *and*
 * [CaptureSessionManager.flagMoment], a plain non-suspend function called directly from a
 * Compose callback on the main thread with no `scope.launch` wrapper at all. `list += x` on one
 * thread racing `list.toList()` on another is a real `ConcurrentModificationException`/torn-read
 * hazard, not a theoretical one — this class exists so every access goes through a single lock
 * instead of touching two bare `MutableList`s from an unpredictable mix of threads.
 *
 * Kept deliberately small and synchronous (a plain monitor lock, not a suspend-based [kotlinx
 * .coroutines.sync.Mutex]): every operation here is a short, non-blocking list mutation or copy,
 * so a coroutine-aware lock would add ceremony (and a suspension point in places that currently
 * aren't suspend functions, like [flagMoment]) for no real benefit over `synchronized`.
 */
class TranscriptLog {
    private val lock = Any()
    private val lines = mutableListOf<TranscriptLine>()
    private val flagLabels = mutableListOf<String>()

    /** Appends [line] and returns an immutable snapshot of every line so far, atomically —
     *  callers that need to publish the new list (e.g. to a `StateFlow`) get one that can never
     *  be torn by a concurrent append. */
    fun appendLine(line: TranscriptLine): List<TranscriptLine> = synchronized(lock) {
        lines += line
        lines.toList()
    }

    fun appendFlag(label: String) = synchronized(lock) {
        flagLabels += label
    }

    /** Removes the **last** flag with [label] (the Undo on a just-added flag). Returns whether one was removed. */
    fun removeFlag(label: String): Boolean = synchronized(lock) {
        val at = flagLabels.lastIndexOf(label)
        if (at >= 0) flagLabels.removeAt(at)
        at >= 0
    }

    fun flagCount(): Int = synchronized(lock) { flagLabels.size }

    fun lineSnapshot(): List<TranscriptLine> = synchronized(lock) { lines.toList() }

    fun flagSnapshot(): List<String> = synchronized(lock) { flagLabels.toList() }

    fun lineCount(): Int = synchronized(lock) { lines.size }

    fun lastLineText(): String? = synchronized(lock) { lines.lastOrNull()?.text }

    /** Replaces the transcript wholesale (resume/recovery) and returns the new snapshot,
     *  atomically — see [appendLine]. */
    fun replaceLines(newLines: List<TranscriptLine>): List<TranscriptLine> = synchronized(lock) {
        lines.clear()
        lines.addAll(newLines)
        lines.toList()
    }

    fun replaceFlags(newFlags: List<String>) = synchronized(lock) {
        flagLabels.clear()
        flagLabels.addAll(newFlags)
    }

    fun clear() = synchronized(lock) {
        lines.clear()
        flagLabels.clear()
    }
}
