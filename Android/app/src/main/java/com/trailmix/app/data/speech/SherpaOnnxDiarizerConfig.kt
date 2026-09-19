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
    // AI-16 (2026-09-19): dynamically int8-quantized (weights only) from the original fp32
    // model via onnxruntime.quantization.quantize_dynamic — 26.5 MB -> 6.7 MB (~75% smaller).
    // Verified via SherpaOnnxDiarizationSmokeTest against the real two-speaker fixture on a
    // real device/emulator (this project's own AI-01 Trap: never trust this class of native
    // model-swap without one) before the fp32 asset was removed: same speaker count, >=90%
    // per-second label agreement between the two. The fp32 original is recoverable from git
    // history if this ever needs revisiting.
    val embeddingAssetPath: String = "sherpa/wespeaker_en_voxceleb_resnet34_LM.int8.onnx",
    val numThreads: Int = 2,
    val clusterThreshold: Float = 0.5f,
    val minDurationOnSeconds: Float = 0.3f,
    val minDurationOffSeconds: Float = 0.5f,
    /**
     * CAP-30 (2026-09-19): [SherpaOnnxDiarizer] now diarizes in windows of this length rather
     * than one whole-session batch call — see its own doc for why. 5 minutes by default:
     * small enough that one window's float conversion is a rounding error next to a retained
     * session's own memory footprint, large enough that a 90-minute session is only ~18
     * separate diarization calls. Configurable (not just an internal constant) so
     * [SherpaOnnxDiarizationSmokeTest] can force many small windows against the short real
     * fixture clip and actually exercise cross-window speaker linking on a real device, not
     * just the single-window pass-through case a 16-second clip would otherwise always hit.
     */
    val windowSeconds: Int = 5 * 60,
)
