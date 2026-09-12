package com.trailmix.app.ui.export

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.ui.theme.TrailMixTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First Compose UI tests in this project (BLD-01/Phase 1c). Deliberately scoped to a
 * dependency-free composable — plain data and callbacks in, no ViewModel/Hilt/DataStore —
 * because [com.trailmix.app.data.settings.SettingsRepository] and friends share the real
 * app's DataStore file name, so an instrumented test that constructed one for real would read
 * and write actual user settings on whatever device eventually runs this. See project memory
 * (`feedback_never_connected_test_on_production_device.md`) for the incident that surfaced
 * that risk.
 */
@RunWith(AndroidJUnit4::class)
class ExportFormatPickerDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startsOnTheInitialFormatAndConfirmsIt() {
        var confirmed: ExportFormat? = null
        composeRule.setContent {
            TrailMixTheme {
                ExportFormatPickerDialog(
                    initialFormat = ExportFormat.LLM_OPTIMIZED,
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("LLM-optimized").assertIsDisplayed()
        composeRule.onNodeWithText("Share").performClick()

        assertEquals(ExportFormat.LLM_OPTIMIZED, confirmed)
    }

    @Test
    fun tappingAnotherFormatChangesWhatConfirmSends() {
        var confirmed: ExportFormat? = null
        composeRule.setContent {
            TrailMixTheme {
                ExportFormatPickerDialog(
                    initialFormat = ExportFormat.LLM_OPTIMIZED,
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Plain text").performClick()
        composeRule.onNodeWithText("Share").performClick()

        assertEquals(ExportFormat.PLAIN_TEXT, confirmed)
    }

    @Test
    fun cancelNeverConfirms() {
        var confirmed: ExportFormat? = null
        var dismissed = false
        composeRule.setContent {
            TrailMixTheme {
                ExportFormatPickerDialog(
                    initialFormat = ExportFormat.HUMAN_READABLE,
                    onConfirm = { confirmed = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(null, confirmed)
        assertEquals(true, dismissed)
    }
}
