package com.trailmix.app.data.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-01 (sherpa-onnx path, replacing Picovoice Falcon 2026-09-13): thin wrapper around
 * sherpa-onnx's `OfflineSpeakerDiarization`, mirroring [FalconDiarizer]'s fail-soft shape one
 * call site later removes — the rest of the app never touches the SDK directly, only
 * [SpeakerDiarizer]. Constructs and releases fresh native engines per call rather than holding
 * any open on this singleton: diarization only ever runs once per session, at merge time, so
 * there's no benefit to keeping ONNX Runtime plus loaded models resident between captures, only
 * memory cost.
 *
 * **CAP-30 (2026-09-19): windowed instead of one whole-session batch call.**
 * `OfflineSpeakerDiarization.process()` takes the *entire* recording as one `FloatArray` — for
 * a real 90-minute session that meant this call held, simultaneously, the already-retained PCM
 * shorts (2 B/sample, from [AudioRetentionBuffer]), the flat short copy [AudioRetentionBuffer
 * .toShortArray] produces (another 2 B/sample), and a freshly built whole-session `FloatArray`
 * (4 B/sample) — an 8 B/sample peak, ~690 MB for 90 minutes, against this device's
 * `dalvik.vm.heapgrowthlimit` of 256 MB (confirmed via `adb shell getprop`). Reproduced live: an
 * unattended ~35-minute capture with speaker labels on, on the real Pixel 9 Pro, before this
 * fix (see the session's device log for the exact reproduction).
 *
 * The fix splits `pcm` into [WINDOW_SECONDS]-sized windows and diarizes one at a time: only one
 * window's audio is ever converted to float at once (~4.6 MB for a 5-minute window, not ~345 MB
 * for a 90-minute session), so the peak on top of the retained buffer drops from ~3x to roughly
 * 1.15x. [AudioRetentionBuffer]'s own cap (90 minutes, also lowered this session — see its doc)
 * governs the retained-buffer floor; this change governs the multiplier on top of it.
 *
 * The cost of windowing: `OfflineSpeakerDiarization`'s own clustering only ever sees one
 * window's audio, so a window's local `speaker` ints (0, 1, 2…) carry no meaning across window
 * boundaries — the same real person can come back as a different local id in the next window.
 * [SpeakerLinker] (backed by sherpa-onnx's own [SpeakerEmbeddingManager]) re-identifies each
 * window's local speakers by voice-embedding similarity and remaps them onto stable global ids
 * before segments are handed to [SpeakerLabels], which is entirely unaware windowing happens at
 * all — it still just sees one flat, session-relative segment list. If the embedding
 * extractor/manager can't be constructed for any reason, each window's local ids are offset by
 * a large per-window constant instead (graceful degradation: speakers get relabeled at every
 * window boundary rather than staying consistent, but nothing crashes or drops a segment).
 *
 * Verified against the real AAR (decompiled directly, not just the Kotlin source on GitHub) and
 * a real end-to-end instrumented smoke test on the local emulator before this class was
 * originally written — see the plan's Phase 0. The windowed-vs-whole-file agreement is covered
 * by [SherpaOnnxDiarizationSmokeTest]'s extended case.
 */
@Singleton
class SherpaOnnxDiarizer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val config: SherpaOnnxDiarizerConfig,
) : SpeakerDiarizer {

    override suspend fun diarize(pcm: ShortArray): List<SpeakerSegment> = diarizeWithVoices(pcm).segments

    override suspend fun diarizeWithVoices(pcm: ShortArray): DiarizationResult = withContext(Dispatchers.Default) {
        if (pcm.isEmpty()) return@withContext DiarizationResult(emptyList(), emptyMap())
        val windowSamples = config.windowSeconds * Pcm.SAMPLE_RATE
        var diarization: OfflineSpeakerDiarization? = null
        var extractor: SpeakerEmbeddingExtractor? = null
        var linker: SpeakerLinker? = null
        try {
            diarization = OfflineSpeakerDiarization(appContext.assets, buildDiarizationConfig())
            // Fail-soft on purpose: an extractor that can't be built (a missing asset, an
            // out-of-memory at the worst moment) degrades cross-window linking, not the whole
            // feature — see this class's doc.
            extractor = runCatching {
                SpeakerEmbeddingExtractor(appContext.assets, buildEmbeddingConfig())
            }.onFailure { Log.w(TAG, "speaker embedding extractor unavailable, links will not persist across windows: $it") }
                .getOrNull()
            linker = extractor?.let { SpeakerLinker(SpeakerEmbeddingManager(it.dim())) }

            val allSegments = mutableListOf<SpeakerSegment>()
            // SPK-04: per global speaker, the window-level embeddings and how many seconds of
            // speech each stood for, folded into one session-wide voice at the end.
            val voiceSamples = HashMap<Int, MutableList<Pair<FloatArray, Double>>>()
            var windowIndex = 0
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + windowSamples, pcm.size)
                val windowFloats = FloatArray(end - offset) { i -> pcm[offset + i] / 32768f }
                val offsetSeconds = offset.toFloat() / Pcm.SAMPLE_RATE
                val localSegments = try {
                    diarization.process(windowFloats)
                } catch (e: Exception) {
                    // One bad window is not a reason to lose every other window's labels.
                    Log.w(TAG, "diarization window at ${offset}s failed: $e")
                    emptyArray()
                }
                val localToGlobal = mutableMapOf<Int, Int>()
                for ((localId, segs) in localSegments.groupBy { it.speaker }) {
                    val voice = voiceFor(extractor, windowFloats, segs)
                    val globalTag = if (voice != null && linker != null) {
                        linker.globalIdFor(voice.first, config.clusterThreshold)
                    } else {
                        windowIndex * FALLBACK_TAG_STRIDE + localId
                    }
                    localToGlobal[localId] = globalTag
                    if (voice != null && linker != null) voiceSamples.getOrPut(globalTag) { mutableListOf() }.add(voice)
                }
                for (seg in localSegments) {
                    allSegments += SpeakerSegment(
                        startSeconds = offsetSeconds + seg.start,
                        endSeconds = offsetSeconds + seg.end,
                        speakerTag = localToGlobal.getValue(seg.speaker),
                    )
                }
                offset = end
                windowIndex++
            }
            val voices = voiceSamples.mapNotNull { (tag, samples) -> VoiceMath.weightedMean(samples)?.let { tag to it } }.toMap()
            DiarizationResult(allSegments, voices)
        } catch (e: Exception) {
            Log.w(TAG, "sherpa-onnx diarization failed: $e")
            DiarizationResult(emptyList(), emptyMap())
        } finally {
            runCatching { diarization?.release() }
            runCatching { extractor?.release() }
            linker?.release()
        }
    }

    /**
     * One local speaker's voice in one window: the weighted mean of the embeddings of its
     * longest few segments, with the seconds of speech they cover as the weight. Null when the
     * extractor is unavailable or no segment is long enough to embed reliably.
     */
    private fun voiceFor(
        extractor: SpeakerEmbeddingExtractor?,
        windowFloats: FloatArray,
        segments: List<OfflineSpeakerDiarizationSegment>,
    ): Pair<FloatArray, Double>? {
        if (extractor == null) return null
        val longest = segments.sortedByDescending { it.end - it.start }.take(MAX_CLIPS_PER_SPEAKER)
        val clips = longest.mapNotNull { seg ->
            embeddingFor(extractor, windowFloats, seg)?.let { it to (seg.end - seg.start).toDouble().coerceAtMost(MAX_CLIP_SECONDS.toDouble()) }
        }
        val mean = VoiceMath.weightedMean(clips) ?: return null
        return mean to clips.sumOf { it.second }
    }

    /** The voice embedding for one segment's own audio slice within [windowFloats], or null if
     *  the clip is too short to embed reliably or the SDK call itself fails. */
    private fun embeddingFor(
        extractor: SpeakerEmbeddingExtractor,
        windowFloats: FloatArray,
        seg: OfflineSpeakerDiarizationSegment,
    ): FloatArray? = runCatching {
        val startSample = (seg.start * Pcm.SAMPLE_RATE).toInt().coerceIn(0, windowFloats.size)
        val endSample = (seg.end * Pcm.SAMPLE_RATE).toInt().coerceIn(startSample, minOf(windowFloats.size, startSample + MAX_CLIP_SECONDS * Pcm.SAMPLE_RATE))
        if (endSample - startSample < MIN_EMBEDDING_SAMPLES) return@runCatching null
        val clip = windowFloats.copyOfRange(startSample, endSample)
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(clip, Pcm.SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }.onFailure { Log.w(TAG, "embedding extraction failed for one segment: $it") }.getOrNull()

    private fun buildDiarizationConfig() = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = config.segmentationAssetPath,
                windowShiftRatio = 0.1f,
            ),
            numThreads = config.numThreads,
            debug = false,
            provider = "cpu",
        ),
        embedding = SpeakerEmbeddingExtractorConfig(
            model = config.embeddingAssetPath,
            numThreads = config.numThreads,
            debug = false,
            provider = "cpu",
        ),
        clustering = FastClusteringConfig(
            numClusters = -1, // unknown speaker count — cluster by threshold instead
            threshold = config.clusterThreshold,
            computeConfidence = false,
        ),
        minDurationOn = config.minDurationOnSeconds,
        minDurationOff = config.minDurationOffSeconds,
    )

    /** Same embedding model the diarization config above already loads internally — a second,
     *  separate load, since `OfflineSpeakerDiarization` doesn't expose its own internal
     *  extractor for reuse. A known, accepted redundancy (~26 MB native, not Dalvik-heap
     *  budget); reusing one instance across both would need an SDK change upstream. */
    private fun buildEmbeddingConfig() = SpeakerEmbeddingExtractorConfig(
        model = config.embeddingAssetPath,
        numThreads = config.numThreads,
        debug = false,
        provider = "cpu",
    )

    private companion object {
        const val TAG = "TrailMixDiarize"

        // Segments shorter than this are too brief to embed reliably (per sherpa-onnx's own
        // guidance for speaker-embedding models) — they fall back to the per-window-offset tag
        // instead of a garbage-in embedding match.
        const val MIN_EMBEDDING_SAMPLES = Pcm.SAMPLE_RATE / 2 // 0.5s

        // SPK-04: a speaker's voice in a window is the mean of its few longest segments, each
        // capped, so embedding cost per window stays bounded however much anyone talks.
        const val MAX_CLIPS_PER_SPEAKER = 3
        const val MAX_CLIP_SECONDS = 20

        // Fallback-path only (no working extractor/linker): spreads each window's own local
        // tags (0, 1, 2…, never more than a handful of speakers per window in practice) into a
        // disjoint integer range per window, so two different windows' local tag 0 never
        // collide into looking like the same global speaker by accident.
        const val FALLBACK_TAG_STRIDE = 1_000
    }
}
