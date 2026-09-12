package com.trailmix.app.ui.export

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailmix.app.data.media.MatchedPhoto
import com.trailmix.app.ui.theme.TrailMixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * See [ExportFormatPickerDialogTest] for why this stays dependency-free: plain data and
 * callbacks in, no permission is ever actually requested (that would pop a real system
 * dialog a Compose UI test can't drive), no [com.trailmix.app.data.media.PhotoSource] query
 * runs, and thumbnails are fake `content://` URIs that fail to load harmlessly.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPickerSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun photo(id: Long, name: String) =
        MatchedPhoto(Uri.parse("content://media/external/images/media/$id"), name, takenAtEpochMs = 1_000L * id)

    @Test
    fun noPermissionShowsTheExplainerAndNoGrid() {
        composeRule.setContent {
            TrailMixTheme {
                PhotoPickerSheet(
                    hasPermission = false,
                    photos = emptyList(),
                    initiallySelected = emptySet(),
                    onPermissionGranted = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Allow access").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun permissionGrantedButNoPhotosShowsTheEmptyState() {
        composeRule.setContent {
            TrailMixTheme {
                PhotoPickerSheet(
                    hasPermission = true,
                    photos = emptyList(),
                    initiallySelected = emptySet(),
                    onPermissionGranted = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("No photos found from around this session.").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun selectAllThenSaveConfirmsEveryPhoto() {
        val photos = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        var confirmed: List<String>? = null
        composeRule.setContent {
            TrailMixTheme {
                PhotoPickerSheet(
                    hasPermission = true,
                    photos = photos,
                    initiallySelected = emptySet(),
                    onPermissionGranted = {},
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Select all").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(2, confirmed?.size)
        assertTrue(confirmed!!.containsAll(photos.map { it.uri.toString() }))
    }

    @Test
    fun tappingOnePhotoSelectsOnlyThatOne() {
        val photos = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        var confirmed: List<String>? = null
        composeRule.setContent {
            TrailMixTheme {
                PhotoPickerSheet(
                    hasPermission = true,
                    photos = photos,
                    initiallySelected = emptySet(),
                    onPermissionGranted = {},
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("photo:${photos[0].uri}").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(listOf(photos[0].uri.toString()), confirmed)
    }

    @Test
    fun startsFromWhateverWasAlreadySelected() {
        val photos = listOf(photo(1, "IMG_1.jpg"), photo(2, "IMG_2.jpg"))
        var confirmed: List<String>? = null
        composeRule.setContent {
            TrailMixTheme {
                PhotoPickerSheet(
                    hasPermission = true,
                    photos = photos,
                    initiallySelected = setOf(photos[1].uri.toString()),
                    onPermissionGranted = {},
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        // Tapping Save immediately, with nothing else touched, must reflect the selection
        // the sheet was opened with, not an empty one.
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(listOf(photos[1].uri.toString()), confirmed)
    }
}
