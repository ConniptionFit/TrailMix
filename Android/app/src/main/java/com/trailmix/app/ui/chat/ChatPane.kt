package com.trailmix.app.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.ui.components.TmBackButton
import com.trailmix.app.ui.components.TmButton
import com.trailmix.app.ui.components.TmButtonIcon
import com.trailmix.app.ui.components.TmFilterChip
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmSnackbarHost
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.delay

/** One message as the pane draws it, whichever table it came from. */
data class ChatBubble(val id: Long, val isUser: Boolean, val text: String, val recipeName: String? = null)

/**
 * K1-K4: the one chat component behind both the single-note chat and the cross-note chat. No
 * bubbles for answers (they read as text on the page), a tonal block for your own questions,
 * Markdown rendering, an empty state with starter questions, a typing indicator you can stop,
 * and Copy, Share and Add to note under each answer.
 */
@Composable
fun ChatPane(
    title: String,
    onBack: () -> Unit,
    bubbles: List<ChatBubble>,
    busy: Boolean,
    aiAvailable: Boolean?,
    inputHint: String,
    emptyTitle: String,
    starters: List<String>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    scopeTitles: List<String> = emptyList(),
    recipes: List<Recipe> = emptyList(),
    onRunRecipe: (Recipe) -> Unit = {},
    onAddToNote: ((String) -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val c = TrailMix.colors
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(bubbles.size, busy) {
        val last = bubbles.size + (if (busy) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TmTopBar(title = title, navigation = { TmBackButton(onClick = onBack) })
            if (scopeTitles.isNotEmpty()) ScopeChips(scopeTitles)
            if (aiAvailable == false) NoAiBanner()

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                if (bubbles.isEmpty() && !busy) {
                    EmptyState(
                        title = emptyTitle,
                        starters = starters,
                        enabled = aiAvailable != false,
                        onPick = onSend,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = TmSpacing.l,
                            vertical = TmSpacing.l,
                        ),
                        verticalArrangement = Arrangement.spacedBy(TmSpacing.l),
                    ) {
                        items(bubbles, key = { it.id }) { bubble ->
                            if (bubble.isUser) {
                                UserMessage(bubble.text)
                            } else {
                                AnswerMessage(bubble, onAddToNote)
                            }
                        }
                        if (busy) item(key = "typing") { TypingIndicator(onStop) }
                    }
                }
            }

            Composer(
                input = input,
                onInput = { input = it },
                hint = inputHint,
                busy = busy,
                recipes = recipes,
                onRunRecipe = onRunRecipe,
                onSend = {
                    onSend(input)
                    input = ""
                },
                onStop = onStop,
            )
        }
        TmSnackbarHost(state = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ScopeChips(titles: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TmSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        titles.forEach { title ->
            Text(
                text = title,
                style = TrailMix.type.caption,
                color = TrailMix.colors.text,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clip(TrailMix.shapes.small)
                    .background(TrailMix.colors.card)
                    .padding(horizontal = TmSpacing.m, vertical = TmSpacing.s),
            )
        }
    }
}

@Composable
private fun NoAiBanner() {
    val c = TrailMix.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.s)
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(TmSpacing.m),
    ) {
        Text(stringResource(R.string.chat_no_ai_banner), style = TrailMix.type.overline, color = c.dim)
        Text(
            stringResource(R.string.chat_no_ai_body),
            style = TrailMix.type.bodySmall,
            color = c.text,
            modifier = Modifier.padding(top = TmSpacing.xs),
        )
    }
}

@Composable
private fun EmptyState(title: String, starters: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    val c = TrailMix.colors
    Column(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(TmSpacing.l),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Text(title, style = TrailMix.type.title, color = c.text)
        Text(stringResource(R.string.chat_empty_body), style = TrailMix.type.bodySmall, color = c.dim)
        Text(
            stringResource(R.string.chat_starters_label),
            style = TrailMix.type.overline,
            color = c.dim,
            modifier = Modifier.padding(top = TmSpacing.m),
        )
        starters.forEach { starter ->
            Text(
                text = starter,
                style = TrailMix.type.body,
                color = if (enabled) c.text else c.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TrailMix.shapes.medium)
                    .border(1.dp, c.outline, TrailMix.shapes.medium)
                    .clickableIf(enabled) { onPick(starter) }
                    .padding(horizontal = TmSpacing.m, vertical = TmSpacing.m),
            )
        }
    }
}

private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.then(Modifier.clickable(onClick = onClick)) else this

@Composable
private fun UserMessage(text: String) {
    val c = TrailMix.colors
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            style = TrailMix.type.body,
            color = c.text,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .clip(TrailMix.shapes.medium)
                .background(c.cardHigh)
                .padding(horizontal = TmSpacing.m, vertical = TmSpacing.m),
        )
    }
}

@Composable
private fun AnswerMessage(bubble: ChatBubble, onAddToNote: ((String) -> Unit)?) {
    val c = TrailMix.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(bubble.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val chooser = stringResource(R.string.chat_share_chooser)
    Column(modifier = Modifier.fillMaxWidth()) {
        bubble.recipeName?.let {
            Text(
                stringResource(R.string.chat_recipe_label, it),
                style = TrailMix.type.overline,
                color = c.dim,
                modifier = Modifier.padding(bottom = TmSpacing.xs),
            )
        }
        ChatMarkdownText(bubble.text)
        Row(modifier = Modifier.padding(top = TmSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            TmTextButton(
                label = stringResource(if (copied) R.string.chat_action_copied else R.string.chat_action_copy),
                onClick = {
                    clipboard.setText(AnnotatedString(ChatMarkdown.plain(bubble.text)))
                    copied = true
                },
            )
            TmTextButton(
                label = stringResource(R.string.chat_action_share),
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ChatMarkdown.plain(bubble.text))
                            },
                            chooser,
                        ),
                    )
                },
            )
            if (onAddToNote != null) {
                TmTextButton(
                    label = stringResource(R.string.chat_action_add),
                    onClick = { onAddToNote(bubble.text) },
                )
            }
        }
    }
}

@Composable
private fun ChatMarkdownText(text: String) {
    val blocks = remember(text) { ChatMarkdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(TmSpacing.s)) {
        blocks.forEach { block ->
            when (block) {
                is ChatBlock.Heading -> Text(
                    inline(block.text),
                    style = TrailMix.type.heading,
                    color = TrailMix.colors.text,
                )

                is ChatBlock.Paragraph -> Text(inline(block.text), style = TrailMix.type.body, color = TrailMix.colors.text)

                is ChatBlock.Bullet -> ListLine("•", block.text)

                is ChatBlock.Numbered -> ListLine("${block.number}.", block.text)
            }
        }
    }
}

@Composable
private fun ListLine(marker: String, text: String) {
    Row {
        Text(marker, style = TrailMix.type.body, color = TrailMix.colors.dim, modifier = Modifier.widthIn(min = 20.dp))
        Text(inline(text), style = TrailMix.type.body, color = TrailMix.colors.text)
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    ChatMarkdown.spans(text).forEach { span ->
        if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) } else append(span.text)
    }
}

@Composable
private fun TypingIndicator(onStop: () -> Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds++
        }
    }
    val label = "%d:%02d".format(seconds / 60, seconds % 60)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.chat_thinking, label),
            style = TrailMix.type.bodySmall,
            color = TrailMix.colors.dim,
            modifier = Modifier.weight(1f),
        )
        TmTextButton(label = stringResource(R.string.chat_stop), onClick = onStop)
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    hint: String,
    busy: Boolean,
    recipes: List<Recipe>,
    onRunRecipe: (Recipe) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = TrailMix.colors
    val canSend = input.isNotBlank() && !busy
    Column(modifier = Modifier.navigationBarsPadding().padding(bottom = TmSpacing.s)) {
        if (recipes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = TmSpacing.l),
                horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
            ) {
                recipes.forEach { recipe ->
                    TmFilterChip(label = recipe.name, selected = false, onClick = { if (!busy) onRunRecipe(recipe) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier
                    .weight(1f)
                    .clip(TrailMix.shapes.large)
                    .background(c.card)
                    .padding(horizontal = TmSpacing.l, vertical = TmSpacing.m)
                    .semantics { contentDescription = hint },
                textStyle = TrailMix.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                decorationBox = { inner ->
                    if (input.isEmpty()) Text(hint, style = TrailMix.type.body, color = c.dim)
                    inner()
                },
            )
            if (busy) {
                TmButton(
                    label = stringResource(R.string.chat_stop),
                    onClick = onStop,
                    icon = TmButtonIcon.Drawable(TmIcons.Stop),
                )
            } else {
                SendButton(enabled = canSend, onClick = onSend)
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val c = TrailMix.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (enabled) c.text else c.cardHigh)
            .clickableIf(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        TmIcon(
            id = TmIcons.Send,
            contentDescription = stringResource(R.string.chat_send),
            tint = if (enabled) c.background else c.dim,
        )
    }
}
