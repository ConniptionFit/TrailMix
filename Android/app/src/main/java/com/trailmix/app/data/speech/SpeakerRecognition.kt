package com.trailmix.app.data.speech

import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPK-04: the app-facing side of voice recognition: match a session's speakers to enrolled
 * voices, learn from a finished session, and manage who is remembered. Everything here is a
 * no-op unless the user turned speaker recognition on ([SettingsRepository.speakerRecognitionEnabled],
 * off by default), and every failure degrades to "no voice evidence".
 *
 * Nothing is enrolled without an action by the user: either "remember this voice"
 * ([rememberVoice]) or, for the note-taker's own voice, a session in which the capture lanes
 * were unambiguous enough to trust ([MeEnrollment]).
 */
@Singleton
class SpeakerRecognition @Inject constructor(
    private val store: VoiceprintStore,
    private val settings: SettingsRepository,
) {
    private suspend fun enabled(): Boolean = settings.speakerRecognitionEnabled.first()

    /** Matches for the speakers in [voices] (keyed by the raw label they carry), best first. */
    suspend fun identify(voices: Map<String, FloatArray>): Map<String, VoiceMatch> {
        if (voices.isEmpty() || !enabled()) return emptyMap()
        val people = store.people()
        if (people.isEmpty()) return emptyMap()
        // A cluster already labelled "Me" by the lane is not second-guessed.
        return runCatching { VoiceMatcher.matchAll(voices.filterKeys { it != SpeakerLabels.ME_LABEL }, people) }
            .getOrDefault(emptyMap())
    }

    /**
     * After a merge: keep each speaker's centroid with the note (under the label the note
     * shows), and teach the note-taker's voice when [MeEnrollment] trusts this session.
     * [labelled] is the transcript with raw diarization labels; [finalLabels] maps a raw label
     * to what the saved note displays.
     */
    suspend fun learn(
        noteId: Long,
        voices: Map<String, FloatArray>,
        finalLabels: Map<String, String>,
        labelled: List<TranscriptLine>,
        userName: String?,
    ) {
        if (voices.isEmpty() || !enabled()) return
        store.saveNoteCentroids(noteId, voices.mapKeys { finalLabels[it.key] ?: it.key })
        val meVoice = voices[SpeakerLabels.ME_LABEL] ?: return
        val weight = MeEnrollment.weight(labelled)
        if (weight <= 0.0) return
        store.update { people ->
            VoicePeople.withVoice(people, userName?.takeIf { it.isNotBlank() } ?: ME_FALLBACK_NAME, true, meVoice, weight, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        }
    }

    /**
     * "Remember this voice": enroll the speaker shown as [label] in note [noteId] under
     * [personName]. Returns false when the note's voice for that label is not on file.
     */
    suspend fun rememberVoice(noteId: Long, label: String, personName: String): Boolean {
        val centroid = store.noteCentroids(noteId)[label] ?: return false
        return store.update { people ->
            VoicePeople.withVoice(people, personName, false, centroid, CONFIRMED_WEIGHT, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        }
    }

    suspend fun people(): List<VoicePerson> = store.people()

    suspend fun forget(personId: String): Boolean = store.update { people -> people.filter { it.id != personId } }

    suspend fun forgetNote(noteId: Long) = store.deleteNoteCentroids(noteId)

    suspend fun forgetEveryone() = store.clearAll()

    private companion object {
        /** A user-confirmed sample is worth about this many auto-enrolled lines. */
        const val CONFIRMED_WEIGHT = 10.0
        const val ME_FALLBACK_NAME = "Me"
    }
}
