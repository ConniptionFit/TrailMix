package com.trailmix.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.speech.MergeStatus
import com.trailmix.app.ui.capture.CaptureUiState
import com.trailmix.app.ui.home.ActiveCaptureUi
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.flow.StateFlow

/**
 * H5/H6/UX-41: the one in-progress card for Home and Note detail. While a capture runs it shows
 * the live level and timer and an Open button; while the capture or a rebuild is merging it
 * shows "Building" with N of M, never "Recording". Collects its own flows (PERF-04): the timer
 * ticks once a second and the level ten times, and neither may recompose the host screen.
 */
@Composable
fun ActiveCaptureCard(
    activeCapture: StateFlow<ActiveCaptureUi?>,
    level: StateFlow<Float>,
    mergeStatus: StateFlow<MergeStatus?>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Note detail passes false while it shows its own rebuild card, so the work isn't shown twice. */
    showBuilding: Boolean = true,
) {
    val active by activeCapture.collectAsStateWithLifecycle()
    val merge by mergeStatus.collectAsStateWithLifecycle()
    val current = active
    if (current == null && merge == null) return
    val c = TrailMix.colors

    if (merge != null || current?.merging == true) {
        if (!showBuilding) return
        val title = merge?.title?.takeUnless { it == CaptureUiState.UNTITLED }
            ?: current?.meetingTitle
        TmLongTaskProgress(
            title = title?.let { stringResource(R.string.home_building_title, it) }
                ?: stringResource(R.string.home_building_title_untitled),
            detail = stringResource(R.string.home_building_detail),
            progress = merge?.progress(),
            counter = merge?.counter(),
            reassurance = null,
            modifier = modifier,
        )
        return
    }
    if (current == null) return

    val levelNow by level.collectAsStateWithLifecycle()
    val state = if (current.paused) RecordingState.PAUSED else RecordingState.RECORDING
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(start = TmSpacing.l, top = TmSpacing.m, bottom = TmSpacing.m, end = TmSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TmSpacing.s)) {
            Text(
                text = current.meetingTitle ?: stringResource(R.string.home_new_note),
                style = TrailMix.type.heading,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TmRecordingIndicator(
                state = state,
                elapsedLabel = current.elapsedLabel,
                level = levelNow,
                compact = true,
                stateLabel = stringResource(
                    if (current.paused) R.string.capture_state_paused else R.string.capture_state_recording,
                ),
            )
        }
        TmTonalButton(label = stringResource(R.string.home_open), onClick = onOpen)
    }
}
