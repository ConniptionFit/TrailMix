package com.trailmix.app.ui.export

import android.Manifest
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailmix.app.data.media.MatchedPhoto
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photo-export feature: lets the user attach photos taken during a note's session to its
 * export. Read-only, opt-in — the permission request only fires when this sheet is opened,
 * never up front, mirroring the app's existing calendar opt-in flow.
 *
 * Photos are tied to the SAF export folder, not the ad-hoc share sheet — sharing sends plain
 * text via an intent with no attached files, so a selection here only ever affects what
 * [com.trailmix.app.data.db.NotesRepository.setSelectedPhotos] writes to disk.
 */
@Composable
fun PhotoPickerSheet(
    hasPermission: Boolean,
    photos: List<MatchedPhoto>,
    initiallySelected: Set<String>,
    onPermissionGranted: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    var selected by remember(photos) { mutableStateOf(initiallySelected) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onPermissionGranted() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Photos", color = c.text, fontWeight = FontWeight.SemiBold) },
        text = {
            when {
                !hasPermission -> Column {
                    Text(
                        text = "Attach photos taken during this session — nothing is scanned " +
                            "until you allow access, and photos never leave your export folder.",
                        color = c.dim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                    Text(
                        text = "Allow access",
                        color = c.amber,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { permissionLauncher.launch(permission) },
                    )
                }
                photos.isEmpty() -> Text(
                    text = "No photos found from around this session.",
                    color = c.dim,
                    fontSize = 13.sp,
                )
                else -> Column {
                    Text(
                        text = if (selected.size == photos.size) "Deselect all" else "Select all",
                        color = c.amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                selected = if (selected.size == photos.size) {
                                    emptySet()
                                } else {
                                    photos.map { it.uri.toString() }.toSet()
                                }
                            }
                            .padding(bottom = 10.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        items(photos, key = { it.uri.toString() }) { photo ->
                            val uriString = photo.uri.toString()
                            val isSelected = uriString in selected
                            Box(
                                modifier = Modifier
                                    .testTag("photo:$uriString")
                                    .padding(3.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        selected = if (isSelected) selected - uriString else selected + uriString
                                    },
                            ) {
                                PhotoThumbnail(photo.uri, Modifier.fillMaxSize())
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (hasPermission && photos.isNotEmpty()) {
                Text(
                    text = "Save",
                    color = c.amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onConfirm(selected.toList()) }.padding(12.dp),
                )
            }
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

@Composable
private fun PhotoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(200, 200), null).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier = modifier.background(TrailMix.colors.card)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
