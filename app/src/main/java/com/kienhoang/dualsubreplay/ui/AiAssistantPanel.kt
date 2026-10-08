package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.ChatHistoryRetention
import com.kienhoang.dualsubreplay.assistant.aiProblemContext
import com.kienhoang.dualsubreplay.assistant.aiSystemPrompt

private val PanelMaxWidth = 420.dp
private const val PANEL_WIDTH_FRACTION = 0.88f

/** A question the panel can send with extra context, for example the current subtitle line. */
internal data class AiQuickQuestion(
    val text: String,
    val context: String? = null,
    val contextLabel: String? = null,
)

/**
 * The assistant panel: slides in from the right over a dim background. Problems come first,
 * then the setup card (no key yet) or the chat. Back closes the history list, then the panel.
 */
@Composable
internal fun AiAssistantPanel(
    host: AiAssistantHost,
    problems: List<AppProblem>,
    snapshot: () -> AiAppSnapshot,
    currentLineQuestion: AiQuickQuestion?,
    onOpenSettings: () -> Unit,
) {
    val controller = host.controller
    val aiState by controller.state.collectAsStateWithLifecycle()
    val open = aiState.panelOpen && aiState.settings.enabled
    val systemPrompt = { aiSystemPrompt(host.guide(), snapshot()) }
    BackHandler(open) { if (aiState.showingHistory) controller.showHistory(false) else controller.closePanel() }
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = controller::closePanel)
                .testTag("ai_panel_scrim"),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = minOf(maxWidth * PANEL_WIDTH_FRACTION, PanelMaxWidth)
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Surface(
                modifier = Modifier.width(width).fillMaxHeight().testTag("ai_panel"),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    AiPanelHeader(aiState, onHistory = {
                        controller.showHistory(!aiState.showingHistory)
                    }, onNewChat = controller::newChat, onClose = controller::closePanel)
                    if (aiState.showingHistory) {
                        AiChatHistoryList(
                            aiState.savedChats,
                            onOpen = controller::openChat,
                            onDelete = controller::deleteChat,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        AiChatContent(
                            aiState = aiState,
                            controller = controller,
                            problems = problems,
                            currentLineQuestion = currentLineQuestion,
                            onAsk = { question -> controller.send(question.text, systemPrompt(), question.context, question.contextLabel) },
                            onRetry = { controller.retry(systemPrompt()) },
                            onOpenSettings = onOpenSettings,
                            modifier = Modifier.weight(1f),
                        )
                        if (aiState.ready) AiInputRow(sending = aiState.sending, onSend = { controller.send(it, systemPrompt()) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPanelHeader(
    aiState: AiAssistantUiState,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (aiState.showingHistory) R.string.ai_history else R.string.ai_panel_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (aiState.settings.historyRetention != ChatHistoryRetention.OFF) {
            IconButton(onClick = onHistory, modifier = Modifier.testTag("ai_history_button")) {
                Icon(Icons.Default.History, contentDescription = stringResource(R.string.ai_history))
            }
        }
        IconButton(onClick = onNewChat, enabled = aiState.chat != null, modifier = Modifier.testTag("ai_new_chat_button")) {
            Icon(Icons.Default.AddComment, contentDescription = stringResource(R.string.ai_new_chat))
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("ai_close_button")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ai_panel_close))
        }
    }
}

@Composable
private fun AiChatContent(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    problems: List<AppProblem>,
    currentLineQuestion: AiQuickQuestion?,
    onAsk: (AiQuickQuestion) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val messages = aiState.messages
    LaunchedEffect(messages.size, aiState.sending, aiState.failure) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (messages.isNotEmpty() && last >= 0) listState.animateScrollToItem(last)
    }
    val askAboutProblem = stringResource(R.string.ai_problem_ask_message)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().testTag("ai_chat_list"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(problems, key = { "problem_${it.key}" }) { problem ->
            AppProblemCard(
                problem = problem,
                canAsk = aiState.ready && !aiState.sending,
                onAsk = {
                    onAsk(
                        AiQuickQuestion(askAboutProblem, aiProblemContext(problem.title, problem.message, problem.detail), problem.title),
                    )
                },
            )
        }
        if (!aiState.ready) {
            item(key = "setup") { AiSetupCard(aiState, controller, onOpenSettings) }
        } else if (messages.isEmpty()) {
            item(key = "welcome") { AiWelcome(currentLineQuestion, enabled = !aiState.sending, onAsk = onAsk) }
        }
        aiMessages(aiState, onRetry)
    }
}

private fun LazyListScope.aiMessages(
    aiState: AiAssistantUiState,
    onRetry: () -> Unit,
) {
    items(aiState.messages, key = { it.id }) { message -> AiMessageBubble(message) }
    if (aiState.sending) item(key = "thinking") { AiThinkingRow() }
    aiState.failure?.let { failure -> item(key = "failure") { AiFailureRow(failure, onRetry) } }
}

@Composable
private fun AiInputRow(
    sending: Boolean,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).testTag("ai_input"),
            placeholder = { Text(stringResource(R.string.ai_input_hint)) },
            maxLines = 5,
            shape = RoundedCornerShape(20.dp),
        )
        IconButton(
            onClick = {
                onSend(text)
                text = ""
            },
            enabled = !sending && text.isNotBlank(),
            modifier = Modifier.testTag("ai_send_button"),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.ai_send))
        }
    }
}
