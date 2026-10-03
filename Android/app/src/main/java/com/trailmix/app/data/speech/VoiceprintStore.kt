package com.trailmix.app.data.speech

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPK-04: where enrolled voices and per-note speaker centroids live. App-private files, never
 * `cacheDir`, never exported, never backed up (`allowBackup=false`). Embeddings only.
 *
 * Files, under `filesDir/voiceprints/`:
 *  - `people.json`: every enrolled [VoicePerson].
 *  - `note-<id>.json`: the centroid of each speaker in one note, by the label the note shows,
 *    so the user can say "that was Priya, remember her" later without any audio being kept.
 *
 * It is a file store rather than a Room table on purpose: the data is a handful of small
 * records read whole, and a Room table would mean a schema bump that cannot be verified without
 * a device. Moving it into Room later is a mechanical change behind this class.
 *
 * Writes go to a temp file and are renamed over the target, so a crash leaves the old content
 * or the new, never half. A file that exists but cannot be read is **kept and never
 * overwritten** (REL-13's rule: failing to read is not a licence to delete).
 */
@Singleton
class VoiceprintStore internal constructor(private val dir: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR_NAME))

    private val lock = Mutex()

    suspend fun people(): List<VoicePerson> = withContext(Dispatchers.IO) {
        lock.withLock { readPeople() ?: emptyList() }
    }

    /**
     * Apply [change] to the stored people and save the result. Returns false (and writes
     * nothing) when an existing file could not be read, so a bad file is never replaced.
     */
    suspend fun update(change: (List<VoicePerson>) -> List<VoicePerson>): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = readPeople()
            if (current == null) {
                Log.w(TAG, "people file unreadable, leaving it alone")
                return@withLock false
            }
            runCatching { write(File(dir, PEOPLE_FILE), VoicePeopleJson.encode(change(current))) }
                .onFailure { Log.w(TAG, "could not save people: ${it.javaClass.simpleName}") }
                .isSuccess
        }
    }

    suspend fun saveNoteCentroids(noteId: Long, byLabel: Map<String, FloatArray>) = withContext(Dispatchers.IO) {
        if (noteId <= 0 || byLabel.isEmpty()) return@withContext
        lock.withLock {
            runCatching { write(noteFile(noteId), VoicePeopleJson.encodeCentroids(byLabel)) }
                .onFailure { Log.w(TAG, "could not save note voices: ${it.javaClass.simpleName}") }
        }
        Unit
    }

    suspend fun noteCentroids(noteId: Long): Map<String, FloatArray> = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = noteFile(noteId)
            if (!f.exists()) emptyMap() else runCatching { VoicePeopleJson.decodeCentroids(f.readText()) }.getOrDefault(emptyMap())
        }
    }

    suspend fun deleteNoteCentroids(noteId: Long) = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { noteFile(noteId).delete() } }
        Unit
    }

    /** Every voice and every note's centroids, gone. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { dir.deleteRecursively() } }
        Unit
    }

    private fun readPeople(): List<VoicePerson>? {
        val f = File(dir, PEOPLE_FILE)
        if (!f.exists()) return emptyList()
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return emptyList()
        return VoicePeopleJson.decodeOrNull(text)
    }

    private fun noteFile(noteId: Long) = File(dir, "note-$noteId.json")

    private fun write(target: File, text: String) {
        dir.mkdirs()
        val temp = File(dir, target.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val DIR_NAME = "voiceprints"
        const val PEOPLE_FILE = "people.json"
        const val TAG = "TrailMixVoices"
    }
}
