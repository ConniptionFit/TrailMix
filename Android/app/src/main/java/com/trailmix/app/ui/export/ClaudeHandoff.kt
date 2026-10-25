package com.trailmix.app.ui.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Hands a note to the Claude app through the ordinary Android share sheet contract
 * (ACTION_SEND), so nothing here needs a network permission or an API key. The user still
 * reviews and sends inside Claude, which keeps the hand-off an explicit, per-note act.
 */
object ClaudeHandoff {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** The text sent to Claude: a short instruction followed by the note's Markdown. */
    fun message(markdown: String): String =
        "Here are my meeting notes, captured and transcribed on-device. " +
            "Please tidy them into a clear summary with decisions and action items.\n\n$markdown"

    /** Tries the Claude app directly; falls back to the system chooser when it is not installed. */
    fun send(context: Context, title: String, markdown: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, message(markdown))
        }
        try {
            context.startActivity(Intent(send).setPackage(CLAUDE_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(send, chooserTitle))
        }
    }
}
