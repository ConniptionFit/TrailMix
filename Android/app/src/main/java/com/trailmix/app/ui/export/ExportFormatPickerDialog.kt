package com.trailmix.app.ui.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.ui.theme.TrailMix

/**
 * The share sheet's one-off format override (export-format dropdown feature) — starts from
 * the persisted Settings default but doesn't change it, matching this app's existing pattern
 * of a persisted setting plus a live, one-off view toggle (e.g. the Sources pill next to the
 * persisted `showSources` flag).
 */
@Composable
fun ExportFormatPickerDialog(
    initialFormat: ExportFormat,
    onConfirm: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    var selected by remember { mutableStateOf(initialFormat) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Share as", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                ExportFormat.entries.forEach { option ->
                    Text(
                        text = option.label,
                        color = if (option == selected) c.amber else c.text,
                        fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable { selected = option }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            Text(
                text = "Share",
                color = c.amber,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onConfirm(selected) }.padding(12.dp),
            )
        },
        dismissButton = {
            Text(
                text = "Cancel",
                color = c.dim,
                modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp),
            )
        },
    )
}
