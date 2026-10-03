package com.trailmix.app.ui.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val aiAvailable by viewModel.aiAvailable.collectAsStateWithLifecycle()
    val canAddToNote by viewModel.canAddToNote.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.addedToNote.collect {
            snackbarHostState.showSnackbar(resources.getString(R.string.chat_added_to_note))
        }
    }

    ChatPane(
        title = stringResource(R.string.chat_title),
        onBack = onBack,
        bubbles = messages.map { ChatBubble(it.id, it.role == "user", it.text, it.recipeName) },
        busy = busy,
        aiAvailable = aiAvailable,
        inputHint = stringResource(R.string.chat_input_hint),
        emptyTitle = stringResource(R.string.chat_empty_title),
        starters = listOf(
            stringResource(R.string.chat_starter_decisions),
            stringResource(R.string.chat_starter_commitments),
            stringResource(R.string.chat_starter_open),
        ),
        onSend = viewModel::send,
        onStop = viewModel::stop,
        recipes = recipes,
        onRunRecipe = viewModel::runRecipe,
        onAddToNote = if (canAddToNote) viewModel::addToNote else null,
        snackbarHostState = snackbarHostState,
    )
}
