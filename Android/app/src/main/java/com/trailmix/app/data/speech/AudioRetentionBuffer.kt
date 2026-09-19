package com.trailmix.app.data.speech

/**
 * AI-01: [SpeakerDiarizer] needs this session's raw PCM to diarize — unlike every other
 * consumer of [AudioPipeline]'s stream, which reads and discards immediately, this one has to
 * retain a copy for the session's duration (diarization runs once, at merge time, over
 * whatever was captured). Never written to disk; freed the moment diarization runs or the
 * session ends without it (only ever constructed when the user has turned the feature on — see
 * [com.trailmix.app.data.settings.SettingsRepository.speakerDiarizationEnabled]).
 *
 * CAP-30 (2026-09-19): capped at [maxSamples] — **90 minutes by default, down from a
 * documented "~3 hours."** That original cap was never actually safe on a real device: at
 * 16 kHz mono / 2 bytes per sample, 3 hours of retained shorts alone is ~345 MB, which already
 * exceeds this device's `dalvik.vm.heapgrowthlimit` (256 MB, confirmed via `adb shell getprop`)
 * with nothing else in the heap at all. 90 minutes (~173 MB) matches the scale this app is
 * actually built and tested around (`LongSessionLoadTest`'s own keynote sizing) and leaves
 * headroom for [SherpaOnnxDiarizer]'s now-windowed processing on top of it — see its own doc
 * for the rest of the memory story. A session longer than 90 minutes still records and
 * saves in full either way; only the diarization step stops labeling speakers past the cap,
 * exactly as it already did at the old 3-hour one.
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

    /**
     * Concatenates every retained chunk into one array, for [SpeakerDiarizer]'s windowed
     * pass. CAP-30: clears [chunks] as it goes, rather than after returning — the two
     * copies (the per-chunk list and the flat result) would otherwise be simultaneously
     * live for however long the caller happens to keep this buffer reachable, which cost
     * a full second copy of the whole session's audio for no reason.
     */
    fun toShortArray(): ShortArray {
        val result = ShortArray(totalSamples)
        var offset = 0
        val iterator = chunks.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            chunk.copyInto(result, offset)
            offset += chunk.size
            iterator.remove()
        }
        return result
    }

    val sampleCount: Int get() = totalSamples

    companion object {
        // CAP-30 (2026-09-19): 90 minutes at 16 kHz mono — see this class's own doc for why
        // the prior 3-hour figure was never actually safe on-device. Matches
        // LongSessionLoadTest's own keynote-scale sizing.
        const val DEFAULT_MAX_SAMPLES = Pcm.SAMPLE_RATE * 60 * 90
    }
}
