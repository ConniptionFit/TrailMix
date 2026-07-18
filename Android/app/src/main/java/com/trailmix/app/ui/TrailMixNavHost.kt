package com.trailmix.app.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trailmix.app.ui.capture.CaptureScreen
import com.trailmix.app.ui.chat.ChatScreen
import com.trailmix.app.ui.home.HomeScreen
import com.trailmix.app.ui.home.RecentlyDeletedScreen
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
    const val RECENTLY_DELETED = "recently-deleted"

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
                onOpenRecentlyDeleted = { navController.navigate(Routes.RECENTLY_DELETED) },
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
            val context = LocalContext.current
            CaptureScreen(
                onMerged = { noteId ->
                    if (noteId > 0) {
                        navController.navigate(Routes.note(noteId)) {
                            popUpTo(Routes.HOME)
                        }
                    } else {
                        // CAP-11: nothing typed, nothing transcribed — no note was saved.
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "Nothing captured — no note saved", Toast.LENGTH_SHORT).show()
                        }
                        navController.popBackStack(Routes.HOME, inclusive = false)
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
                // Part 3.2: jump back into a capture that's live in the background while
                // viewing an unrelated note — no title/resumeNoteId needed, same as Home's
                // chip, CaptureSessionManager already knows what's active.
                onOpenActiveCapture = { navController.navigate(Routes.capture()) },
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
        composable(Routes.RECENTLY_DELETED) {
            RecentlyDeletedScreen(onBack = { navController.popBackStack() })
        }
    }
}
