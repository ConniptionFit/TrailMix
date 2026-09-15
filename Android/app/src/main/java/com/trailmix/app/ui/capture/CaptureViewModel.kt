package com.trailmix.app.ui.capture

import android.content.Intent
import android.media.AudioDeviceInfo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.model.TemplateOption
import com.trailmix.app.data.model.TemplateOptions
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.data.speech.CaptureSessionManager
import com.trailmix.app.data.speech.EngineKind
import com.trailmix.app.data.speech.MergeStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A selectable input in the capture menu; null device = automatic routing. */
data class InputOption(val label: String, val device: AudioDeviceInfo?)

/** What the screen should do about the device-audio consent right now. */
enum class DeviceAudioPrompt { NONE, EXPLAIN_THEN_ASK, ASK }

data class CaptureUiState(
    val recording: Boolean = false,
    /** CAP-12: mic/engine torn down but the session (transcript so far, fragments,
     * elapsed time) is kept alive — distinct from [recording] so a paused session still
     * counts as "active" everywhere that matters (Home chip, back-confirm, re-attach). */
    val paused: Boolean = false,
    val elapsedLabel: String = "0:00",
    /** CAP-13: same elapsed time in ms — lets the notification drive a native
     *  chronometer instead of being re-posted once a second just to advance a string. */
    val elapsedMs: Long = 0L,
    val livePartial: String = "",
    val lastFinalLine: String = "",
    val merging: Boolean = false,
    val speechAvailable: Boolean = true,
    /**
     * REL-11: why this session cannot transcribe, when the reason is *situational* rather
     * than a property of the device. `speechAvailable = false` alone renders "isn't available
     * on this device", which is simply wrong for a mic held by a call or another app — the
     * device is fine and retrying later will work. Null when there is nothing to explain.
     */
    val captureError: String? = null,
    val engineKind: EngineKind = EngineKind.NONE,
    val deviceAudioActive: Boolean = false,
    /** Device audio attached but delivering pure silence for a while. */
    val deviceAudioSilent: Boolean = false,
    val inputOptions: List<InputOption> = emptyList(),
    val selectedInputIndex: Int = 0,
    val deviceAudioPrompt: DeviceAudioPrompt = DeviceAudioPrompt.NONE,
    /** Calendar event this capture is for (from the Home card or detected live). */
    val meetingTitle: String? = null,
    /**
     * CAP-13: the name of the note this capture is writing into, for the ongoing
     * notification. A resumed capture (CAP-07) carries the existing note's real title; a
     * fresh one has no title until the merge names it, so it falls back to the meeting name
     * and finally to "New note" — the same label the Capture screen shows.
     */
    val noteTitle: String = UNTITLED,
    /**
     * CAP-15: wall-clock instant the elapsed timer counts up from — i.e.
     * `now - elapsedMs` — which is what the notification's native chronometer needs as its
     * base.
     *
     * It is a separate field rather than something the notification derives, because it is
     * *stable* while recording (both `now` and `elapsedMs` advance together) and only moves
     * when the timeline itself is redefined: resuming into an existing note adopts that
     * note's prior duration, and pausing freezes it. That makes it the right thing to
     * compare when deciding whether the notification must be re-posted — comparing
     * `elapsedMs` instead would re-post every second and defeat the chronometer.
     */
    val elapsedBaseMs: Long = 0L,
    /** CAP-12: one-shot — the call this capture started during appears to have ended
     * (AudioManager left call/communication mode) while still recording. Screen shows a
     * "finish now or later?" dialog; [CaptureSessionManager.consumeCallEndedPrompt] clears it. */
    val callEndedPrompt: Boolean = false,
) {
    companion object {
        /**
         * Placeholder shown (and stored in [noteTitle]) until something better is known.
         *
         * It is also used as a *sentinel*: `CaptureSessionManager` checks against it to decide
         * whether a live-detected meeting may claim the note's name, so this must stay a
         * single shared constant. When it was duplicated as a literal across four sites, any
         * edit to one copy would have silently stopped that check from ever matching.
         */
        const val UNTITLED = "New note"
    }
}

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
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val requestedResumeNoteId: Long =
        savedStateHandle.get<Long>("resumeNoteId")?.takeIf { it > 0 } ?: -1L
    private val requestedTitle: String? =
        savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() }

    val state: StateFlow<CaptureUiState> = manager.state
    val fragments: MutableStateFlow<String> get() = manager.fragments
    val liveLines: StateFlow<List<TranscriptLine>> = manager.liveLines

    /** REL-10: chunk progress while the merge runs, so the screen shows the same "N of M"
     * the foreground notification does rather than a static "Merging on-device…". */
    val mergeStatus: StateFlow<MergeStatus?> = manager.mergeStatus

    /** AI-11: the in-progress capture's periodically-refreshed "so far" summary. */
    val rollingSummary: StateFlow<String?> = manager.rollingSummary
    val deviceAudioSupported: Boolean get() = manager.deviceAudioSupported

    /** The active session's stored template value (enum name or `custom:<name>`, AI-03). */
    val currentTemplate: String get() = manager.currentTemplate

    /** Built-ins + the user's custom templates (AI-03) — the Capture selector's chips. */
    val templateOptions: StateFlow<List<TemplateOption>> =
        settingsRepository.customSummaryTemplates
            .map { TemplateOptions.all(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateOptions.builtIns())

    /** True if we're observing a session that was already running before this VM existed. */
    val reattached: Boolean
        get() = manager.state.value.recording || manager.state.value.paused || manager.state.value.merging

    fun startRecording() {
        // Already active (either a fresh call racing init, or we navigated back to an
        // in-progress — possibly paused (CAP-12) — session) — nothing to start, just
        // keep observing.
        if (manager.state.value.recording || manager.state.value.paused || manager.state.value.merging) return
        manager.beginSession(requestedResumeNoteId, requestedTitle)
    }

    fun setTemplate(stored: String) = manager.setTemplate(stored)

    fun requestDeviceAudio() = manager.requestDeviceAudio()
    fun consumeDeviceAudioPrompt() = manager.consumeDeviceAudioPrompt()
    fun markExplainerShown() = manager.markExplainerShown()
    fun selectInput(index: Int) = manager.selectInput(index)
    fun onProjectionGranted(resultCode: Int, data: Intent) = manager.onProjectionGranted(resultCode, data)
    fun disableDeviceAudio() = manager.disableDeviceAudio()

    /** CAP-12: same pause/resume the notification's actions drive — releases the mic
     * without ending the session so the elapsed timer freezes and transcript/fragments
     * are kept for later. */
    fun pause() = manager.pause()
    fun resume() = manager.resume()
    fun consumeCallEndedPrompt() = manager.consumeCallEndedPrompt()

    /** CAP-24: flag the current moment; returns its `mm:ss` label for an on-screen confirmation, or null if there's nothing to flag right now (not recording/paused). */
    fun flagMoment(): String? = manager.flagMoment()

    fun endAndMerge(onDone: (Long) -> Unit) = manager.endAndMerge(onDone)

    /** Explicit discard — the only path that actually tears down the session. */
    fun cancel() = manager.cancel()

    // Deliberately no onCleared() teardown: this ViewModel does not own the session, so
    // the Compose back-stack entry being destroyed (navigating to Home, etc.) must not
    // stop the recording. That was CAP-10's real bug.
}
