package com.trailmix.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trailmix.app.ui.capture.CaptureScreen
import com.trailmix.app.ui.chat.ChatScreen
import com.trailmix.app.ui.home.HomeScreen
import com.trailmix.app.ui.note.NoteDetailScreen
import com.trailmix.app.ui.settings.SettingsScreen
import com.trailmix.app.ui.transcript.TranscriptScreen

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val NOTE = "note/{noteId}"
    const val TRANSCRIPT = "transcript/{noteId}"
    const val CHAT = "chat/{noteId}"
    const val SETTINGS = "settings"

    fun note(id: Long) = "note/$id"
    fun transcript(id: Long) = "transcript/$id"
    fun chat(id: Long) = "chat/$id"
}

@Composable
fun TrailMixNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewCapture = { navController.navigate(Routes.CAPTURE) },
                onOpenNote = { id -> navController.navigate(Routes.note(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onMerged = { noteId ->
                    navController.navigate(Routes.note(noteId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            Routes.NOTE,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) { entry ->
            val noteId = entry.arguments?.getLong("noteId") ?: return@composable
            NoteDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenTranscript = { navController.navigate(Routes.transcript(noteId)) },
                onOpenChat = { navController.navigate(Routes.chat(noteId)) },
            )
        }
        composable(
            Routes.TRANSCRIPT,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) {
            TranscriptScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
