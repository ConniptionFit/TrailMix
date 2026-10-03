package com.trailmix.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.ui.theme.TrailMix

/**
 * A8: icons that are not in `material-icons-core` ship as local vector drawables (Material
 * Symbols, Apache-2.0). Use [TmIcon] so the tint follows the theme; the drawables carry no tint.
 */
object TmIcons {
    @DrawableRes val Pause = R.drawable.ic_pause
    @DrawableRes val Flag = R.drawable.ic_flag
    @DrawableRes val Mic = R.drawable.ic_tm_mic
    @DrawableRes val MicOff = R.drawable.ic_tm_mic_off
    @DrawableRes val Folder = R.drawable.ic_tm_folder
    @DrawableRes val Visibility = R.drawable.ic_tm_visibility
    @DrawableRes val Undo = R.drawable.ic_tm_undo
    @DrawableRes val Chat = R.drawable.ic_tm_chat
    @DrawableRes val Subject = R.drawable.ic_tm_subject
    @DrawableRes val Title = R.drawable.ic_tm_title
    @DrawableRes val Help = R.drawable.ic_tm_help
    @DrawableRes val TaskAlt = R.drawable.ic_tm_task_alt
    @DrawableRes val ExpandContent = R.drawable.ic_tm_expand_content
    @DrawableRes val DragIndicator = R.drawable.ic_tm_drag_indicator
    @DrawableRes val Devices = R.drawable.ic_tm_devices
    @DrawableRes val ArrowUp = R.drawable.ic_tm_keyboard_arrow_up
    @DrawableRes val ArrowDown = R.drawable.ic_tm_keyboard_arrow_down
    @DrawableRes val Calendar = R.drawable.ic_tm_calendar
}

@Composable
fun TmIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = TrailMix.colors.text,
    size: Dp = 24.dp,
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
