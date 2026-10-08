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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAppSnapshot
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiPanelPage
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
 * then the setup or key-check card, or the chat. The gear opens the AI settings inside the panel;
 * Back returns from settings or history, then closes the panel.
 */
@Composable
internal fun AiAssistantPanel(
    host: AiAssistantHost,
    problems: List<AppProblem>,
    snapshot: () -> AiAppSnapshot,
    currentLineQuestion: AiQuickQuestion?,
) {
    val controller = host.controller
    val aiState by controller.state.collectAsStateWithLifecycle()
    val open = aiState.panelOpen && aiState.settings.enabled
    val systemPrompt = { aiSystemPrompt(host.guide(), snapshot()) }
    BackHandler(open) { if (aiState.page != AiPanelPage.CHAT) controller.showPage(AiPanelPage.CHAT) else controller.closePanel() }
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
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
                    AiPanelHeader(aiState, controller)
                    when (aiState.page) {
                        AiPanelPage.HISTORY ->
                            AiChatHistoryList(
                                aiState.savedChats,
                                onOpen = controller::openChat,
                                onDelete = controller::deleteChat,
                                modifier = Modifier.weight(1f),
                            )
                        AiPanelPage.SETTINGS ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .testTag("ai_settings_page"),
                            ) { AiAssistantSettingsSection(host) }
                        AiPanelPage.MODELS -> AiModelsPage(aiState, controller, Modifier.weight(1f))
                        AiPanelPage.CHAT -> {
                            AiChatContent(
                                aiState = aiState,
                                controller = controller,
                                problems = problems,
                                currentLineQuestion = currentLineQuestion,
                                onAsk = { question ->
                                    controller.send(question.text, systemPrompt(), question.context, question.contextLabel)
                                },
                                onRetry = { controller.retry(systemPrompt()) },
                                modifier = Modifier.weight(1f),
                            )
                            if (aiState.ready) {
                                AiComposer(
                                    aiState,
                                    controller,
                                ) { text, files -> controller.send(text, systemPrompt(), attachments = files) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The chat page shows history, new chat and settings; the other pages show Back and their title. */
@Composable
private fun AiPanelHeader(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val page = aiState.page
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page == AiPanelPage.CHAT) {
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = { controller.showPage(AiPanelPage.CHAT) }, modifier = Modifier.testTag("ai_back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ai_back))
            }
        }
        Text(
            stringResource(
                when (page) {
                    AiPanelPage.CHAT -> R.string.ai_panel_title
                    AiPanelPage.HISTORY -> R.string.ai_history
                    AiPanelPage.SETTINGS -> R.string.ai_open_settings
                    AiPanelPage.MODELS -> R.string.ai_models_title
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (page == AiPanelPage.CHAT) {
            if (aiState.settings.historyRetention != ChatHistoryRetention.OFF) {
                IconButton(onClick = { controller.showPage(AiPanelPage.HISTORY) }, modifier = Modifier.testTag("ai_history_button")) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.ai_history))
                }
            }
            IconButton(onClick = controller::newChat, enabled = aiState.chat != null, modifier = Modifier.testTag("ai_new_chat_button")) {
                Icon(Icons.Default.AddComment, contentDescription = stringResource(R.string.ai_new_chat))
            }
            IconButton(onClick = { controller.showPage(AiPanelPage.SETTINGS) }, modifier = Modifier.testTag("ai_settings_button")) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.ai_open_settings))
            }
        }
        IconButton(onClick = controller::closePanel, modifier = Modifier.testTag("ai_close_button")) {
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
            item(key = "setup") { AiConnectCard(aiState, controller) { controller.showPage(AiPanelPage.SETTINGS) } }
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
    aiState.failure?.let { failure -> item(key = "failure") { AiFailureRow(failure, onRetry.takeIf { aiState.ready }) } }
}
