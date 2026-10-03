package com.trailmix.app.data.speech

/**
 * Capability-only contract for whole-session speaker diarization (AI-01) — deliberately carries
 * no backend-specific concept (no access key, no model paths). [SpeakerSegment] was already
 * backend-agnostic before this interface existed; this just formalizes the seam
 * [CaptureSessionManager] was already isolated behind, mirroring [com.trailmix.app.data.export.ExportSink]'s
 * exact shape in this codebase: a plain interface, one concrete implementation bound to it in
 * [com.trailmix.app.di.AppModule], swappable to a new implementation (a newer sherpa-onnx
 * release, or a different engine entirely) by changing that one binding — never this interface,
 * never [CaptureSessionManager].
 */
interface SpeakerDiarizer {

    /**
     * Whole-recording batch diarization over 16kHz mono PCM16 — the app's own native audio
     * format, exactly what [AudioRetentionBuffer.toShortArray] already produces. Implementations
     * must be fail-soft internally (catch their own SDK/model errors, return `emptyList()`):
     * diarization is a pure enhancement and must never block a merge/save.
     */
    suspend fun diarize(pcm: ShortArray): List<SpeakerSegment>

    /**
     * SPK-04: [diarize] plus one voice embedding per speaker tag (a unit vector summarising how
     * that speaker sounds across the whole session). Backends that cannot produce embeddings
     * keep this default, which returns the segments with no voices, and everything downstream
     * simply has no voiceprint evidence.
     */
    suspend fun diarizeWithVoices(pcm: ShortArray): DiarizationResult = DiarizationResult(diarize(pcm), emptyMap())
}

/** [segments] with, per [SpeakerSegment.speakerTag], that speaker's session-wide [voices] embedding. */
class DiarizationResult(val segments: List<SpeakerSegment>, val voices: Map<Int, FloatArray>)
