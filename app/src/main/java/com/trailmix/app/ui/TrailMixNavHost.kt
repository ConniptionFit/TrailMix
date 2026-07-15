package com.trailmix.app.ui

import android.net.Uri
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
import com.trailmix.app.ui.meetings.MeetingsScreen
import com.trailmix.app.ui.note.NoteDetailScreen
import com.trailmix.app.ui.settings.SettingsScreen
import com.trailmix.app.ui.transcript.TranscriptScreen

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture?title={title}&resumeNoteId={resumeNoteId}"
    const val NOTE = "note/{noteId}"
    const val TRANSCRIPT = "transcript/{noteId}"
    const val CHAT = "chat/{noteId}"
    const val SETTINGS = "settings"
    const val MEETINGS = "meetings"

    fun capture(title: String? = null) =
        if (title.isNullOrBlank()) "capture" else "capture?title=${Uri.encode(title)}"

    /** Continue adding to an existing note (re-merges into it on End & Merge). */
    fun resumeCapture(noteId: Long) = "capture?resumeNoteId=$noteId"
    fun note(id: Long) = "note/$id"
    fun transcript(id: Long) = "transcript/$id"
    fun chat(id: Long) = "chat/$id"
}

@Composable
fun TrailMixNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewCapture = { navController.navigate(Routes.capture()) },
                onCaptureMeeting = { title -> navController.navigate(Routes.capture(title)) },
                onOpenMeetings = { navController.navigate(Routes.MEETINGS) },
                onOpenNote = { id -> navController.navigate(Routes.note(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                // CAP-10: reopen the still-running capture session — no title/resumeNoteId
                // args needed, CaptureSessionManager already knows what's active.
                onOpenActiveCapture = { navController.navigate(Routes.capture()) },
            )
        }
        composable(Routes.MEETINGS) {
            MeetingsScreen(
                onBack = { navController.popBackStack() },
                onStartCapture = { title ->
                    navController.navigate(Routes.capture(title)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(
            Routes.CAPTURE,
            arguments = listOf(
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumeNoteId") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            CaptureScreen(
                onMerged = { noteId ->
                    navController.navigate(Routes.note(noteId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() },
                onMinimize = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
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
                onResume = {
                    navController.navigate(Routes.resumeCapture(noteId)) {
                        popUpTo(Routes.HOME)
                    }
                },
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
