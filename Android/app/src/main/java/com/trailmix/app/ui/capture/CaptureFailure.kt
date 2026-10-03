package com.trailmix.app.ui.capture

import com.trailmix.app.data.speech.CaptureSessionManager

/** C4: the audio failures that share one layout; only the banner differs. */
enum class CaptureFailure { MIC_BUSY, RECOGNIZER_STOPPED, NO_RECOGNIZER }

/**
 * Maps session state to the banner to show, or null when capture is healthy. Order matters:
 * a situational error is checked before the generic "no recognizer" because the latter would
 * be actively misleading for a mic that is merely busy (REL-11).
 */
fun captureFailureOf(captureError: String?, speechAvailable: Boolean): CaptureFailure? = when {
    captureError == CaptureSessionManager.MIC_UNAVAILABLE -> CaptureFailure.MIC_BUSY
    captureError != null -> CaptureFailure.RECOGNIZER_STOPPED
    !speechAvailable -> CaptureFailure.NO_RECOGNIZER
    else -> null
}
