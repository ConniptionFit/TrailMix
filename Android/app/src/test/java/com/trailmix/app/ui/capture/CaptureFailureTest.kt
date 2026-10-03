package com.trailmix.app.ui.capture

import com.trailmix.app.data.speech.CaptureSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureFailureTest {
    @Test fun healthyCaptureHasNoBanner() = assertNull(captureFailureOf(null, speechAvailable = true))

    @Test fun busyMicIsItsOwnFailureEvenThoughSpeechIsUnavailableToo() {
        assertEquals(
            CaptureFailure.MIC_BUSY,
            captureFailureOf(CaptureSessionManager.MIC_UNAVAILABLE, speechAvailable = false),
        )
    }

    @Test fun recognizerStoppingIsNotReportedAsAMicProblem() {
        assertEquals(
            CaptureFailure.RECOGNIZER_STOPPED,
            captureFailureOf(CaptureSessionManager.RECOGNIZER_UNAVAILABLE, speechAvailable = false),
        )
    }

    @Test fun noEngineAtAllIsADevicePropertyNotARetryableError() {
        assertEquals(CaptureFailure.NO_RECOGNIZER, captureFailureOf(null, speechAvailable = false))
    }
}
