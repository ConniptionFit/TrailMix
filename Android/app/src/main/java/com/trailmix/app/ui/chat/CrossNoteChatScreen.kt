package com.trailmix.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R

/**
 * Cross-note chat (AI-10) over a set of notes picked on Home. Same pane as the single-note
 * chat; no recipes (they are written assuming one note) and no Add to note (there is no single
 * note to add to).
 */
@Composable
fun CrossNoteChatScreen(
    onBack: () -> Unit,
    viewModel: CrossNoteChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val noteTitles by viewModel.noteTitles.collectAsStateWithLifecycle()
    val aiAvailable by viewModel.aiAvailable.collectAsStateWithLifecycle()

    ChatPane(
        title = if (noteTitles.isEmpty()) {
            stringResource(R.string.chat_title)
        } else {
            stringResource(R.string.chat_title_across, noteTitles.size)
        },
        onBack = onBack,
        bubbles = messages.map { ChatBubble(it.id, it.role == "user", it.text) },
        busy = busy,
        aiAvailable = aiAvailable,
        inputHint = stringResource(R.string.chat_input_hint_across),
        emptyTitle = stringResource(R.string.chat_empty_title_across),
        starters = listOf(
            stringResource(R.string.chat_starter_across_agree),
            stringResource(R.string.chat_starter_across_changed),
            stringResource(R.string.chat_starter_across_repeat),
        ),
        onSend = viewModel::send,
        onStop = viewModel::stop,
        scopeTitles = noteTitles,
    )
}
