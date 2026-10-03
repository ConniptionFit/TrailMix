package com.trailmix.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/**
 * A labeled text field for forms and editors. The label sits above the box (so it never
 * disappears while typing and is read by TalkBack), the box is at least 48dp tall, and the
 * caret is ink: amber is for "typed by you" content, not for form chrome.
 */
@Composable
fun TmTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    minHeight: Int = 48,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = TrailMix.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = TrailMix.type.caption, color = c.dim, modifier = Modifier.padding(bottom = TmSpacing.xs))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                .clip(TrailMix.shapes.small)
                .background(c.card)
                .padding(horizontal = TmSpacing.m, vertical = TmSpacing.m)
                .semantics { contentDescription = label },
            textStyle = TrailMix.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) {
                    Text(hint, style = TrailMix.type.body, color = c.dim)
                }
                inner()
            },
        )
    }
}
