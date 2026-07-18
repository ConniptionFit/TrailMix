package com.trailmix.app.ui.chat

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.components.BackTitleBar
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        BackTitleBar(title = "Chat & Recipes", onBack = onBack)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                val isUser = message.role == "user"
                val clipboard = LocalClipboardManager.current
                var copied by remember(message.id) { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(1_500)
                        copied = false
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Text(
                        text = message.text,
                        color = if (isUser) Color.White else c.text,
                        fontSize = 14.sp,
                        lineHeight = 19.6.sp, // 1.4
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .clip(
                                if (isUser) {
                                    RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp)
                                } else {
                                    RoundedCornerShape(14.dp, 14.dp, 14.dp, 2.dp)
                                },
                            )
                            .background(if (isUser) c.amber else c.card)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    // Copy-to-clipboard on assistant replies (OBS-01) — recipe outputs
                    // (follow-up emails, tickets) usually get pasted somewhere else next.
                    if (!isUser) {
                        Text(
                            text = if (copied) "Copied" else "Copy",
                            color = if (copied) c.teal else c.dim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable {
                                    clipboard.setText(AnnotatedString(message.text))
                                    copied = true
                                }
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (busy) {
                item {
                    Text(
                        text = "Thinking on-device…",
                        color = c.dim,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }

        // Pinned bottom: recipe chips + input
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recipes.forEach { recipe ->
                    Text(
                        text = recipe.name,
                        color = c.text,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .border(1.dp, c.border, RoundedCornerShape(100.dp))
                            .clickable(enabled = !busy) { viewModel.runRecipe(recipe) }
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.dp, c.border, RoundedCornerShape(100.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.amber),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            viewModel.send(input)
                            input = ""
                        },
                    ),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(text = "Ask anything…", color = c.dim, fontSize = 14.sp)
                        }
                        inner()
                    },
                )
            }
        }
    }
}
