package com.trailmix.app.data.speech

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/** A crash-surviving journal on disk, already replayed. [id] is its filename. */
data class PendingJournal(val id: String, val session: CaptureJournal.RecoveredSession)

/**
 * REL-09: the file side of [CaptureJournal] — where journals live, how they are appended to
 * durably, and how leftovers are found on the next launch.
 *
 * Storage is `filesDir/capture-journal/session-<startedAtMs>.jsonl`, app-private for the same
 * reason the Room database is: an in-flight transcript is exactly as sensitive as a saved
 * note. `filesDir` and **not** `cacheDir` — the system may evict the cache under storage
 * pressure, and losing a crash journal to a cleanup is the same outcome as not having written
 * it at all.
 *
 * ## Ordering and durability
 *
 * All writes are queued onto a **single-threaded** dispatcher, and the public write methods
 * are deliberately non-suspending fire-and-forget. That combination is what guarantees a
 * journal reads back in the order the session actually happened: `launch` on a
 * single-threaded dispatcher preserves submission order, so the header cannot land after the
 * first utterance, and utterances cannot be transposed. Making these `suspend` and letting
 * callers launch them would reintroduce exactly that race, since two coroutines can reach
 * their `withContext` in either order.
 *
 * Each record is flushed and `fsync`ed before the call returns. At roughly one utterance
 * every few seconds that cost is irrelevant, and it is the difference between surviving a
 * process kill (which a plain flush already handles) and surviving the phone dying outright.
 *
 * ## Record boundaries (REL-13)
 *
 * Replay's whole guarantee — damage costs at most the record it happened in — rests on every
 * record occupying exactly one line. A write that lands only part way breaks that: the next
 * record is appended straight onto the fragment, and the resulting line parses as neither, so
 * the damage spreads to a record that was written perfectly. Both places where a stream can
 * be positioned mid-record therefore close it first: after a failed write, and before
 * appending to a journal recovered from a crash. See [appendRecord] and [ensureRecordBoundary].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CaptureJournalStore internal constructor(private val dir: File) {

    /**
     * The real one. Journals live beside the database in app-private storage; the directory
     * is a constructor parameter only so the file logic can be exercised on a temp dir
     * without a device, which is where [CaptureJournalStoreTest] runs.
     */
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR_NAME))

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The journal being appended to right now. Only ever touched on [dispatcher]. */
    private var current: FileOutputStream? = null
    private var currentFile: File? = null

    // ── Session lifecycle ──────────────────────────────────────────────────

    /** Start a fresh journal for a session beginning at [startedAtEpochMs]. */
    fun begin(startedAtEpochMs: Long, resumeNoteId: Long) = scope.launch {
        closeCurrent()
        val file = runCatching {
            dir.mkdirs()
            File(dir, "$FILE_PREFIX$startedAtEpochMs$FILE_SUFFIX")
        }.getOrNull() ?: return@launch
        openAndWrite(file, CaptureJournal.sessionRecord(startedAtEpochMs, resumeNoteId), append = false)
    }

    /**
     * Reattach to an existing journal and keep appending to it (the "Continue" recovery
     * path). Continuing in the *same* file rather than copying into a new one means a
     * recovered session that crashes again is recovered again, with no special case: the
     * replay simply folds a longer list of records.
     */
    fun adopt(id: String) = scope.launch {
        closeCurrent()
        val file = File(dir, id)
        if (!file.exists()) {
            Log.w(TAG, "journal $id vanished before it could be adopted")
            return@launch
        }
        // REL-13: this file is being reopened *because* it was cut short, so it is the one
        // place a mid-record tail is expected rather than exceptional.
        ensureRecordBoundary(file)
        openAndWrite(file, record = null, append = true)
    }

    fun line(line: com.trailmix.app.data.model.TranscriptLine) = write(CaptureJournal.lineRecord(line))

    /** CAP-24: one flagged moment, same single-threaded write path as [line]. */
    fun flag(label: String) = write(CaptureJournal.flagRecord(label))

    /** [CaptureJournal.deltaRecord] returns null when nothing changed — then there is nothing to write. */
    fun delta(record: String?) {
        if (record != null) write(record)
    }

    /**
     * The session ended cleanly: mark it and remove the file.
     *
     * The end marker is written *before* the delete even though the delete usually makes it
     * moot, because the delete is the step that can fail. Without the marker, a file left
     * behind by a failed delete would be replayed on the next launch and offered as an
     * unsaved crash — prompting someone to "recover" a note they already have.
     */
    fun finish() = scope.launch {
        current?.let { appendRecord(it, CaptureJournal.endRecord()) }
        val file = currentFile
        closeCurrent()
        runCatching { file?.delete() }
    }

    /** The user discarded the capture — no marker needed, the file just goes. */
    fun discardCurrent() = scope.launch {
        val file = currentFile
        closeCurrent()
        runCatching { file?.delete() }
    }

    // ── Recovery ───────────────────────────────────────────────────────────

    /**
     * Every journal on disk with something recoverable in it, newest session first.
     *
     * Journals that replay to nothing — a clean end whose delete failed, or a session that
     * captured no speech and no typed text — are deleted here rather than reported. They
     * carry no user content, so removing them is not a data decision; leaving them would mean
     * a prompt on every launch about a session that holds nothing.
     *
     * Anything with real content is left strictly alone. Deleting a recoverable transcript is
     * the user's call and nobody else's, however old the file is: there is deliberately no
     * age-based cleanup here, because "stale" and "the only copy of an hour of a keynote" are
     * indistinguishable from this side.
     */
    suspend fun pending(): List<PendingJournal> = withContext(dispatcher) {
        val open = currentFile?.name
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }.orEmpty()
        files.mapNotNull { file ->
            if (file.name == open) return@mapNotNull null
            val session = runCatching {
                file.useLines { CaptureJournal.replay(it, startedAtFromName(file.name)) }
            }.getOrElse { failure ->
                // REL-13: a journal that could not be *read* is not a journal known to be
                // empty, and this branch used to treat the two the same and delete it. The
                // failures that land here are exactly the ones that correlate with size —
                // an I/O error, or replay running out of memory folding a very long
                // transcript — so the rule was at its most destructive on the longest
                // session, the one whose loss actually costs something. Keep the file: it
                // stays invisible this launch, and costs only the bytes it occupies, while
                // deleting it is unrecoverable and cannot be justified from here.
                Log.w(TAG, "could not replay journal ${file.name}; keeping it: $failure")
                return@mapNotNull null
            }
            if (!session.hasContent) {
                runCatching { file.delete() }
                null
            } else {
                PendingJournal(id = file.name, session = session)
            }
        }.sortedByDescending { it.session.startedAtEpochMs }
    }

    /** Remove one journal by id — the explicit "Discard" recovery action. */
    suspend fun discard(id: String) = withContext(dispatcher) {
        runCatching { File(dir, id).delete() }
        Unit
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun write(record: String) {
        scope.launch { current?.let { appendRecord(it, record) } }
    }

    /**
     * Append one record and its terminator, durably. The single place a record is written.
     *
     * REL-13: the recovery on failure is the point. A `write` that throws part way through
     * (a full disk being the realistic cause, and a full disk during a long capture being
     * precisely when the journal is load-bearing) leaves the stream sitting mid-line. The
     * next record would then be appended onto that fragment, producing one line that parses
     * as neither and silently destroying a record that was itself written perfectly.
     * Terminating the fragment costs one byte and confines the damage to the record that
     * actually failed — which is the guarantee the whole append-only format is built on.
     */
    private fun appendRecord(out: FileOutputStream, record: String) {
        runCatching {
            out.write((record + "\n").toByteArray())
            out.flush()
            out.fd.sync()
        }.onFailure { failure ->
            Log.w(TAG, "journal write failed: $failure")
            runCatching {
                out.write(NEWLINE)
                out.flush()
                out.fd.sync()
            }
        }
    }

    /**
     * REL-13: leave [file] ending on a record boundary before anything is appended to it.
     *
     * Only ever needed by [adopt], and only because that path reopens a journal a crash cut
     * short. A file already ending in a newline — the overwhelmingly common case — is left
     * untouched; otherwise one byte closes the partial record, so replay discards the
     * fragment as it already would and the first utterance after recovery survives.
     */
    private fun ensureRecordBoundary(file: File) {
        runCatching {
            val length = file.length()
            if (length == 0L) return@runCatching
            val last = RandomAccessFile(file, "r").use { it.seek(length - 1); it.read() }
            if (last == '\n'.code) return@runCatching
            Log.w(TAG, "journal ${file.name} ended mid-record; closing it before appending")
            FileOutputStream(file, true).use {
                it.write(NEWLINE)
                it.flush()
                it.fd.sync()
            }
        }
    }

    private fun openAndWrite(file: File, record: String?, append: Boolean) {
        val out = runCatching { FileOutputStream(file, append) }.getOrNull()
        if (out == null) {
            // A journal we can't open must not take the capture down with it — recording
            // without a safety net is strictly better than not recording.
            Log.w(TAG, "could not open journal ${file.name}; capturing without a safety net")
            current = null
            currentFile = null
            return
        }
        current = out
        currentFile = file
        // REL-13: a header that fails to write is *not* a reason to abandon the journal.
        // Replay already falls back to the start time encoded in the filename, so a session
        // whose first record was lost is still recovered in full — where dropping the
        // journal here would have cost every utterance that followed. appendRecord has
        // already closed the record boundary either way.
        if (record != null) appendRecord(out, record)
    }

    private fun closeCurrent() {
        runCatching { current?.close() }
        current = null
        currentFile = null
    }

    /** `session-1785000000000.jsonl` → 1785000000000; 0 when the name says nothing. */
    private fun startedAtFromName(name: String): Long =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L

    /**
     * Test seam: suspend until every queued write has actually run. Exact rather than a
     * poll, because the dispatcher is single-threaded and FIFO — the same property the
     * ordering guarantee in this class's header depends on.
     */
    internal suspend fun awaitIdle() = withContext(dispatcher) { }

    private companion object {
        const val TAG = "TrailMixJournal"
        const val DIR_NAME = "capture-journal"
        const val FILE_PREFIX = "session-"
        const val FILE_SUFFIX = ".jsonl"
        val NEWLINE = "\n".toByteArray()
    }
}
