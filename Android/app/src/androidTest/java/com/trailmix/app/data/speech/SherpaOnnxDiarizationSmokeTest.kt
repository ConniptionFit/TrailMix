package com.trailmix.app.data.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0 feasibility spike (Falcon→sherpa-onnx migration): proves the real sherpa-onnx AAR
 * actually links and runs real diarization on a real device/emulator, against the bundled
 * production assets (`app/src/main/assets/sherpa/`) — before any production wrapper class
 * exists. Deliberately calls the raw SDK directly rather than through a not-yet-written
 * `SpeakerDiarizer`/`SherpaOnnxDiarizer`, same reasoning [PhotoSourceInstrumentedTest] documents
 * for why this class of behavior can't be trusted from a JVM unit test or Robolectric shadow —
 * real JNI, real native inference.
 *
 * Test fixture (`two-speakers-en.wav`, `androidTest/assets/`) is sherpa-onnx's own published
 * two-speaker English sample (`speaker-segmentation-models` release), 16kHz mono PCM16 — the
 * same format TrailMix's own [Pcm] already uses, so no format conversion beyond int16→float32
 * normalization is a realistic stand-in for this app's real capture output.
 *
 * Run via `:app:connectedDebugAndroidTest` against the local emulator ONLY — never the tethered
 * Pixel 9 Pro (see the project's standing rule on `connectedAndroidTest` against that device).
 */
@RunWith(AndroidJUnit4::class)
class SherpaOnnxDiarizationSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun realTwoSpeakerClip_diarizesWithoutCrashing_andFindsMultipleSpeakers() {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = "sherpa/pyannote-segmentation-3-0.int8.onnx",
                    windowShiftRatio = 0.1f,
                ),
                numThreads = 2,
                debug = true,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = "sherpa/wespeaker_en_voxceleb_resnet34_LM.onnx",
                numThreads = 2,
                debug = true,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(
                numClusters = -1, // unknown speaker count — cluster by threshold instead
                threshold = 0.5f,
                computeConfidence = true,
            ),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )

        val diarization = OfflineSpeakerDiarization(context.assets, config)
        try {
            assertTrue("sherpa-onnx's declared sample rate should be the usual 16kHz", diarization.sampleRate() == 16000)

            // The test fixture lives in the *test* APK's assets (androidTest/assets/), not the
            // target app's — InstrumentationRegistry's own context, not ApplicationProvider's,
            // is what resolves it. The models above correctly use the app's own AssetManager.
            val testContext = InstrumentationRegistry.getInstrumentation().context
            val samples = readWavAsFloat32(testContext.assets.open("two-speakers-en.wav"))
            assertTrue("test fixture should decode to a non-trivial amount of audio", samples.size > 16_000)

            val segments = diarization.process(samples)

            assertTrue("expected at least one diarized segment from a real two-speaker clip", segments.isNotEmpty())
            segments.forEach { segment ->
                assertTrue("segment start should be non-negative", segment.start >= 0f)
                assertTrue("segment end should come after its start", segment.end > segment.start)
                assertTrue("speaker tag should be a real cluster id, not a sentinel", segment.speaker >= 0)
            }
            val distinctSpeakers = segments.map { it.speaker }.toSet()
            assertTrue(
                "a file named/published as two-speaker should not collapse to a single cluster " +
                    "(found: $distinctSpeakers) — if this fails, the threshold needs real tuning, " +
                    "not necessarily a broken integration",
                distinctSpeakers.size >= 2,
            )
        } finally {
            diarization.release()
        }
    }

    /** Skips the 44-byte canonical PCM WAV header, converts 16-bit LE PCM to normalized Float32
     *  — the exact conversion a real [SherpaOnnxDiarizer] will need to apply to
     *  [AudioRetentionBuffer]'s [ShortArray] output, proven here against a real file first. */
    private fun readWavAsFloat32(input: java.io.InputStream): FloatArray = input.use { stream ->
        val bytes = stream.readBytes()
        val headerSize = 44
        val sampleCount = (bytes.size - headerSize) / 2
        FloatArray(sampleCount) { i ->
            val offset = headerSize + i * 2
            val sample = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xFF)).toShort()
            sample / 32768f
        }
    }
}
