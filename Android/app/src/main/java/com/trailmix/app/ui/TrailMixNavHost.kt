package com.trailmix.app.ui

import android.net.Uri
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
import com.trailmix.app.ui.chat.CrossNoteChatScreen
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
    const val TRANSCRIPT = "transcript/{noteId}?highlightLabel={highlightLabel}"
    const val CHAT = "chat/{noteId}"
    // AI-10: comma-joined note ids — Compose Navigation has no native list-arg type, and
    // this is the only spot in the app that's ever needed to pass more than one id.
    const val CROSS_NOTE_CHAT = "cross-note-chat/{noteIds}"
    const val SETTINGS = "settings"
    const val MEETINGS = "meetings"
    // REL-06: the Recently deleted screen (1-day soft-delete recovery window).
    const val RECENTLY_DELETED = "recently-deleted"

    fun capture(title: String? = null) =
        if (title.isNullOrBlank()) "capture" else "capture?title=${Uri.encode(title)}"

    /** Continue adding to an existing note (re-merges into it on End & Merge). */
    fun resumeCapture(noteId: Long) = "capture?resumeNoteId=$noteId"
    fun note(id: Long) = "note/$id"

    /** UX-19/UX-20: [highlightLabel] scrolls the transcript to and highlights that line. */
    fun transcript(id: Long, highlightLabel: String? = null) =
        "transcript/$id" + (highlightLabel?.let { "?highlightLabel=${Uri.encode(it)}" } ?: "")
    fun chat(id: Long) = "chat/$id"
    fun crossNoteChat(ids: Collection<Long>) = "cross-note-chat/${ids.joinToString(",")}"
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
                onOpenTranscriptMoment = { id, label ->
                    navController.navigate(Routes.transcript(id, label))
                },
                onOpenCrossNoteChat = { ids -> navController.navigate(Routes.crossNoteChat(ids)) },
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
                        // (CaptureSessionManager delivers this callback on the main thread.)
                        Toast.makeText(context, "Nothing captured — no note saved", Toast.LENGTH_SHORT).show()
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
            arguments = listOf(
                navArgument("noteId") { type = NavType.LongType },
                navArgument("highlightLabel") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            TranscriptScreen(
                onBack = { navController.popBackStack() },
                highlightLabel = entry.arguments?.getString("highlightLabel"),
            )
        }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("noteId") { type = NavType.LongType }),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.CROSS_NOTE_CHAT,
            arguments = listOf(navArgument("noteIds") { type = NavType.StringType }),
        ) {
            CrossNoteChatScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RECENTLY_DELETED) {
            RecentlyDeletedScreen(onBack = { navController.popBackStack() })
        }
    }
}
