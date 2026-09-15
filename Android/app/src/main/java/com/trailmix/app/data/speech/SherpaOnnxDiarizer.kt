package com.trailmix.app.data.speech

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-01 (sherpa-onnx path, replacing Picovoice Falcon 2026-09-13): thin wrapper around
 * sherpa-onnx's `OfflineSpeakerDiarization`, mirroring [FalconDiarizer]'s fail-soft shape one
 * call site later removes — the rest of the app never touches the SDK directly, only
 * [SpeakerDiarizer]. Constructs and releases a fresh native engine per call rather than holding
 * one open on this singleton: diarization only ever runs once per session, at merge time, so
 * there's no benefit to keeping ONNX Runtime plus two loaded models resident between captures,
 * only memory cost.
 *
 * Verified against the real AAR (decompiled directly, not just the Kotlin source on GitHub) and
 * a real end-to-end instrumented smoke test on the local emulator before this class was written
 * — see the plan's Phase 0.
 */
@Singleton
class SherpaOnnxDiarizer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val config: SherpaOnnxDiarizerConfig,
) : SpeakerDiarizer {

    override suspend fun diarize(pcm: ShortArray): List<SpeakerSegment> = withContext(Dispatchers.Default) {
        if (pcm.isEmpty()) return@withContext emptyList()
        var diarization: OfflineSpeakerDiarization? = null
        try {
            diarization = OfflineSpeakerDiarization(appContext.assets, buildSherpaConfig())
            val samples = FloatArray(pcm.size) { i -> pcm[i] / 32768f }
            diarization.process(samples).map { SpeakerSegment(it.start, it.end, it.speaker) }
        } catch (e: Exception) {
            Log.w(TAG, "sherpa-onnx diarization failed: $e")
            emptyList()
        } finally {
            runCatching { diarization?.release() }
        }
    }

    private fun buildSherpaConfig() = OfflineSpeakerDiarizationConfig(
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

    private companion object {
        const val TAG = "TrailMixDiarize"
    }
}
