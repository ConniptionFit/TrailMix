package com.trailmix.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trailmix.app.R
import com.trailmix.app.ui.components.TmBackButton
import com.trailmix.app.ui.components.TmSnackbarHost
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.theme.TrailMix

/**
 * S1 to S3: Settings is six groups ordered by how often you touch them, each a sub-page. The
 * page is plain state (not a nav route): Back returns to the list first, then leaves Settings,
 * and the list rows show each group's current state so most visits never open a page.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val c = TrailMix.colors
    val snackbarHostState = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TmTopBar(
                title = stringResource(pageTitle(page)),
                navigation = {
                    TmBackButton(
                        onClick = { if (page == SettingsPage.ROOT) onBack() else page = SettingsPage.ROOT },
                        contentDescription = stringResource(R.string.action_back),
                    )
                },
            )
            Column(modifier = Modifier.widthIn(max = 640.dp).weight(1f)) {
                when (page) {
                    SettingsPage.ROOT -> SettingsRoot(viewModel) { page = it }
                    SettingsPage.APPEARANCE -> AppearancePage(viewModel, systemDark = isSystemInDarkTheme())
                    SettingsPage.CAPTURE -> CapturePage(viewModel)
                    SettingsPage.NOTES -> NotesPage(viewModel)
                    SettingsPage.EXPORT -> ExportPage(viewModel, snackbarHostState)
                    SettingsPage.PRIVACY -> PrivacyPage()
                    SettingsPage.ABOUT -> AboutPage()
                }
            }
        }
        TmSnackbarHost(state = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private fun pageTitle(page: SettingsPage): Int = when (page) {
    SettingsPage.ROOT -> R.string.settings_title
    SettingsPage.APPEARANCE -> R.string.settings_appearance
    SettingsPage.CAPTURE -> R.string.settings_capture
    SettingsPage.NOTES -> R.string.settings_notes
    SettingsPage.EXPORT -> R.string.settings_export
    SettingsPage.PRIVACY -> R.string.settings_privacy
    SettingsPage.ABOUT -> R.string.settings_about
}
