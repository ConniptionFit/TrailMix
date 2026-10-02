package com.trailmix.app

import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.data.settings.ThemeMode
import com.trailmix.app.ui.TrailMixNavHost
import com.trailmix.app.ui.theme.TrailMix
import com.trailmix.app.ui.theme.TrailMixTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeModeFlow = settingsRepository.themeMode
            .stateIn(lifecycleScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
        setContent {
            val themeMode by themeModeFlow.collectAsStateWithLifecycle()
            val darkOn = themeMode.darkOverride ?: isSystemInDarkTheme()
            // E1 (2026-09-19): enableEdgeToEdge()'s default SystemBarStyle.auto reads the
            // *system's* dark/light config at the moment it's called — it has no idea about
            // this app's own in-app dark-mode override, so whenever the override disagreed
            // with the system setting, the status/nav bar icon contrast was wrong (dark icons
            // on a dark bar, or the reverse) for the whole session, and nothing ever
            // re-invoked it when the override changed. Re-called here with an explicit style
            // keyed on the *resolved* theme, transparent either way — TrailMix draws its own
            // background behind the bars, so the platform scrim would just be extra, wrong-
            // toned paint.
            LaunchedEffect(darkOn) {
                val style = if (darkOn) {
                    SystemBarStyle.dark(TRANSPARENT)
                } else {
                    SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TrailMixTheme(themeMode = themeMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TrailMix.colors.background),
                ) {
                    TrailMixNavHost()
                }
            }
        }
    }
}
