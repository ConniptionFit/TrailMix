package com.trailmix.app.data.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CaptureJournalStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val dir: File get() = File(context.filesDir, DIR_NAME)

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
        if (file.exists()) openAndWrite(file, record = null, append = true)
    }

    fun line(line: com.trailmix.app.data.model.TranscriptLine) = write(CaptureJournal.lineRecord(line))

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
        runCatching {
            current?.let {
                it.write((CaptureJournal.endRecord() + "\n").toByteArray())
                it.flush()
                it.fd.sync()
            }
        }
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
            }.getOrNull()
            if (session == null || !session.hasContent) {
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
        scope.launch {
            runCatching {
                val out = current ?: return@launch
                out.write((record + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        }
    }

    private fun openAndWrite(file: File, record: String?, append: Boolean) {
        runCatching {
            val out = FileOutputStream(file, append)
            current = out
            currentFile = file
            if (record != null) {
                out.write((record + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        }.onFailure {
            // A journal we can't open must not take the capture down with it — recording
            // without a safety net is strictly better than not recording.
            current = null
            currentFile = null
        }
    }

    private fun closeCurrent() {
        runCatching { current?.close() }
        current = null
        currentFile = null
    }

    /** `session-1785000000000.jsonl` → 1785000000000; 0 when the name says nothing. */
    private fun startedAtFromName(name: String): Long =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L

    private companion object {
        const val DIR_NAME = "capture-journal"
        const val FILE_PREFIX = "session-"
        const val FILE_SUFFIX = ".jsonl"
    }
}
