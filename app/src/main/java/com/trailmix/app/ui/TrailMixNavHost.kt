package com.trailmix.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trailmix.app.ui.detail.NoteDetailScreen
import com.trailmix.app.ui.notes.NotesScreen
import com.trailmix.app.ui.recording.RecordingScreen
import com.trailmix.app.ui.settings.SettingsScreen

object Routes {
    const val Notes = "notes"
    const val Recording = "recording"
    const val Settings = "settings"
    const val Detail = "detail/{noteId}"
    fun detail(noteId: Long) = "detail/$noteId"
}

@Composable
fun TrailMixNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Notes) {
        composable(Routes.Notes) {
            NotesScreen(
                onNewNote = { navController.navigate(Routes.Recording) },
                onOpenNote = { id -> navController.navigate(Routes.detail(id)) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
            )
        }
        composable(Routes.Recording) {
            RecordingScreen(
                onFinished = { noteId ->
                    navController.popBackStack()
                    navController.navigate(Routes.detail(noteId))
                },
                onDismiss = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.Detail,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) {
            NoteDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
