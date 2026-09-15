package com.trailmix.app.data.speech

/**
 * AI-01: whole-session diarization ([SpeakerDiarizer]) has no streaming API in either backend
 * this app has used (Falcon, then sherpa-onnx's `OfflineSpeakerDiarization`) — it takes the
 * whole session's raw PCM in one call — so unlike every other consumer of [AudioPipeline]'s
 * stream, which reads and discards immediately, this one has to retain a copy for the session's
 * duration. Never written to disk; freed the moment diarization runs or the session ends
 * without it (only ever constructed when the user has turned the feature on — see
 * [com.trailmix.app.data.settings.SettingsRepository.speakerDiarizationEnabled]).
 *
 * Capped at [maxSamples] (~3 hours by default) as a memory backstop: a session longer than
 * that diarizes only its first ~3 hours rather than risking an OOM on an unbounded buffer.
 */
class AudioRetentionBuffer(private val maxSamples: Int = DEFAULT_MAX_SAMPLES) {
    private val chunks = mutableListOf<ShortArray>()
    private var totalSamples = 0
    private var capped = false

    /**
     * Must be called synchronously from the mic pump thread with a chunk it owns for this
     * call only — [AudioPipeline] reuses its scratch array on the very next read, so this
     * copies the live range immediately rather than retaining a reference to it.
     */
    fun append(samples: ShortArray, count: Int) {
        if (capped || count <= 0) return
        val take = minOf(count, maxSamples - totalSamples)
        if (take <= 0) {
            capped = true
            return
        }
        chunks += samples.copyOfRange(0, take)
        totalSamples += take
        if (totalSamples >= maxSamples) capped = true
    }

    /** Concatenates every retained chunk into one array, for a single [SpeakerDiarizer] call. */
    fun toShortArray(): ShortArray {
        val result = ShortArray(totalSamples)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    val sampleCount: Int get() = totalSamples

    companion object {
        // ~3 hours at 16 kHz mono — comfortably past the ~90-minute keynote this app is
        // built around (see LongSessionLoadTest), without holding an unbounded amount of RAM.
        const val DEFAULT_MAX_SAMPLES = Pcm.SAMPLE_RATE * 60 * 180
    }
}
