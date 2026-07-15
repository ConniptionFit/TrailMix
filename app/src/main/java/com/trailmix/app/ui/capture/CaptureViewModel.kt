package com.trailmix.app.ui.capture

import android.content.Intent
import android.media.AudioDeviceInfo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.speech.CaptureSessionManager
import com.trailmix.app.data.speech.EngineKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A selectable input in the capture menu; null device = automatic routing. */
data class InputOption(val label: String, val device: AudioDeviceInfo?)

/** What the screen should do about the device-audio consent right now. */
enum class DeviceAudioPrompt { NONE, EXPLAIN_THEN_ASK, ASK }

data class CaptureUiState(
    val recording: Boolean = false,
    val elapsedLabel: String = "0:00",
    val livePartial: String = "",
    val lastFinalLine: String = "",
    val merging: Boolean = false,
    val speechAvailable: Boolean = true,
    val engineKind: EngineKind = EngineKind.NONE,
    val deviceAudioActive: Boolean = false,
    /** Device audio attached but delivering pure silence for a while. */
    val deviceAudioSilent: Boolean = false,
    val inputOptions: List<InputOption> = emptyList(),
    val selectedInputIndex: Int = 0,
    val deviceAudioPrompt: DeviceAudioPrompt = DeviceAudioPrompt.NONE,
    /** Calendar event this capture is for (from the Home card or detected live). */
    val meetingTitle: String? = null,
)

/**
 * Thin UI-state adapter over [CaptureSessionManager] (CAP-10). The manager — not this
 * ViewModel — owns the actual recording session, so navigating away from Capture (Home
 * chip, system back) and returning later re-attaches to the same in-progress session
 * instead of losing it. This ViewModel only decides whether to *start* a new session
 * (nothing active yet) versus observe one that's already running.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val manager: CaptureSessionManager,
) : ViewModel() {

    private val requestedResumeNoteId: Long =
        savedStateHandle.get<Long>("resumeNoteId")?.takeIf { it > 0 } ?: -1L
    private val requestedTitle: String? =
        savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() }

    val state: StateFlow<CaptureUiState> = manager.state
    val fragments: MutableStateFlow<String> get() = manager.fragments
    val liveLines: StateFlow<List<TranscriptLine>> = manager.liveLines
    val deviceAudioSupported: Boolean get() = manager.deviceAudioSupported
    val currentTemplate: SummaryTemplate get() = manager.currentTemplate

    /** True if we're observing a session that was already running before this VM existed. */
    val reattached: Boolean get() = manager.state.value.recording || manager.state.value.merging

    fun startRecording() {
        // Already active (either a fresh call racing init, or we navigated back to an
        // in-progress session) — nothing to start, just keep observing.
        if (manager.state.value.recording || manager.state.value.merging) return
        manager.beginSession(requestedResumeNoteId, requestedTitle)
    }

    fun setTemplate(template: SummaryTemplate) = manager.setTemplate(template)

    fun requestDeviceAudio() = manager.requestDeviceAudio()
    fun consumeDeviceAudioPrompt() = manager.consumeDeviceAudioPrompt()
    fun markExplainerShown() = manager.markExplainerShown()
    fun selectInput(index: Int) = manager.selectInput(index)
    fun onProjectionGranted(resultCode: Int, data: Intent) = manager.onProjectionGranted(resultCode, data)
    fun disableDeviceAudio() = manager.disableDeviceAudio()

    fun endAndMerge(onDone: (Long) -> Unit) = manager.endAndMerge(onDone)

    /** Explicit discard — the only path that actually tears down the session. */
    fun cancel() = manager.cancel()

    // Deliberately no onCleared() teardown: this ViewModel does not own the session, so
    // the Compose back-stack entry being destroyed (navigating to Home, etc.) must not
    // stop the recording. That was CAP-10's real bug.
}
