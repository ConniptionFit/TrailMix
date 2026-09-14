package com.trailmix.app.data.db

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain view of an enrolled speaker — [profileBytes] is an opaque `EagleProfile.getBytes()`
 * export. Deliberately not a decoded `EagleProfile`: that SDK type owns a native handle that
 * must be `delete()`d, which has no natural lifecycle in a Flow-collected repository. Callers
 * that need the live SDK object (enrollment, live recognition) build a short-lived
 * `EagleProfile(bytes)` themselves, the same way [SherpaOnnxDiarizer] builds and releases its
 * `OfflineSpeakerDiarization` instance within a single call.
 */
data class EnrolledSpeaker(val id: Long, val name: String, val profileBytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is EnrolledSpeaker && id == other.id && name == other.name && profileBytes.contentEquals(other.profileBytes)

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Speaker recognition (Eagle path): CRUD over enrolled voiceprints. Same shape as
 * [NotesRepository] — constructor-injected DAO only, no `Context` — so it's testable on a
 * plain JVM fake with no Room.
 */
@Singleton
class SpeakerProfileRepository @Inject constructor(
    private val dao: SpeakerProfileDao,
) {
    val enrolledSpeakers: Flow<List<EnrolledSpeaker>> =
        dao.observeAll().map { rows -> rows.map { EnrolledSpeaker(it.id, it.name, it.profileBytes) } }

    suspend fun getAll(): List<EnrolledSpeaker> =
        dao.getAll().map { EnrolledSpeaker(it.id, it.name, it.profileBytes) }

    /** Null return = rejected input (blank name or empty profile), nothing written. */
    suspend fun save(name: String, profileBytes: ByteArray): Long? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || profileBytes.isEmpty()) return null
        return dao.insert(SpeakerProfileEntity(name = trimmed, profileBytes = profileBytes, createdAtEpochMs = System.currentTimeMillis()))
    }

    suspend fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        dao.rename(id, trimmed)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
