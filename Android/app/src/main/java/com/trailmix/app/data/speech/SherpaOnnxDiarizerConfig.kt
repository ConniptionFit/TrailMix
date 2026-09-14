package com.trailmix.app.data.speech

/**
 * Everything [SherpaOnnxDiarizer]-specific that must never leak into [SpeakerDiarizer] — asset
 * paths and tuning knobs live here instead, so bumping to a newer sherpa-onnx release or better
 * defaults (found via real-world testing) is a diff to this one file, never to the interface,
 * [CaptureSessionManager], or any test that depends on the interface.
 *
 * Asset paths are relative to `app/src/main/assets/` and resolved via Android's [android.content.res.AssetManager]
 * (sherpa-onnx's constructors accept one directly — no manual extraction to app-private storage
 * needed, unlike Falcon's AAR-bundled-and-extracted-at-first-use model).
 *
 * [clusterThreshold]/[minDurationOnSeconds]/[minDurationOffSeconds] are placeholders carried
 * over from the Phase 0 spike, not yet tuned against a real, varied corpus of recordings — see
 * the plan's Phase 0 write-up. `numClusters = -1` (unknown speaker count, cluster by threshold)
 * is hardcoded in [SherpaOnnxDiarizer] rather than exposed here, since TrailMix never knows the
 * real speaker count up front either.
 */
data class SherpaOnnxDiarizerConfig(
    val segmentationAssetPath: String = "sherpa/pyannote-segmentation-3-0.int8.onnx",
    val embeddingAssetPath: String = "sherpa/wespeaker_en_voxceleb_resnet34_LM.onnx",
    val numThreads: Int = 2,
    val clusterThreshold: Float = 0.5f,
    val minDurationOnSeconds: Float = 0.3f,
    val minDurationOffSeconds: Float = 0.5f,
)
