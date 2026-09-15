package com.trailmix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.trailmix.app.data.settings.SettingsRepository
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
        enableEdgeToEdge()
        val darkOverrideFlow = settingsRepository.darkModeOverride
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        setContent {
            val darkOverride by darkOverrideFlow.collectAsStateWithLifecycle()
            TrailMixTheme(darkModeOverride = darkOverride) {
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
