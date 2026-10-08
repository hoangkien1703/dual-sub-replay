package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiChat
import com.kienhoang.dualsubreplay.assistant.AiChatMessage
import com.kienhoang.dualsubreplay.assistant.AiFailure
import com.kienhoang.dualsubreplay.assistant.AiRole
import com.kienhoang.dualsubreplay.assistant.AiSpanStyle
import com.kienhoang.dualsubreplay.assistant.formatAiReply
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AppProblemCard(
    problem: AppProblem,
    canAsk: Boolean,
    onAsk: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai_problem_${problem.key}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (problem.mild) MildProblemTint else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(problem.title, style = MaterialTheme.typography.titleSmall)
            }
            problem.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            problem.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (canAsk) {
                    TextButton(onClick = onAsk, modifier = Modifier.testTag("ai_problem_ask")) {
                        Text(stringResource(R.string.ai_problem_ask))
                    }
                }
                TextButton(onClick = problem.action, modifier = Modifier.testTag("ai_problem_action")) { Text(problem.actionLabel) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiWelcome(
    currentLineQuestion: AiQuickQuestion?,
    enabled: Boolean,
    onAsk: (AiQuickQuestion) -> Unit,
) {
    val questions =
        listOfNotNull(
            AiQuickQuestion(stringResource(R.string.ai_chip_features)),
            AiQuickQuestion(stringResource(R.string.ai_chip_bigger_subtitles)),
            currentLineQuestion,
        )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ai_welcome_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.ai_welcome_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            questions.forEach { question ->
                SuggestionChip(
                    onClick = { onAsk(question) },
                    enabled = enabled,
                    label = { Text(question.text) },
                    modifier = Modifier.testTag("ai_chip"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiMessageBubble(message: AiChatMessage) {
    val user = message.role == AiRole.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp).testTag(if (user) "ai_user_message" else "ai_reply"),
            shape = RoundedCornerShape(16.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.attachments.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) { message.attachments.forEach { AiAttachmentChip(it, onRemove = null) } }
                }
                message.contextLabel?.let {
                    Text(
                        stringResource(R.string.ai_message_about, it),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SelectionContainer {
                    if (user) Text(message.text) else Text(rememberAiReply(message.text))
                }
            }
        }
    }
}

@Composable
private fun rememberAiReply(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, codeBackground) {
        val formatted = formatAiReply(text)
        buildAnnotatedString {
            append(formatted.text)
            formatted.spans.forEach { span ->
                val style =
                    when (span.style) {
                        AiSpanStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        AiSpanStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                    }
                addStyle(style, span.start, span.end)
            }
        }
    }
}

@Composable
internal fun AiThinkingRow() {
    Row(Modifier.padding(4.dp).testTag("ai_thinking"), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.ai_thinking), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Why the last question got no answer. [onRetry] is null while the key needs a new check first. */
@Composable
internal fun AiFailureRow(
    failure: AiFailure,
    onRetry: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().testTag("ai_failure")) {
        Text(stringResource(failure.kind.messageRes()), color = MaterialTheme.colorScheme.error)
        failure.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp).testTag("ai_retry")) {
                Text(stringResource(R.string.ai_retry))
            }
        }
    }
}

@Composable
internal fun AiChatHistoryList(
    chats: List<AiChat>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chats.isEmpty()) {
        Text(
            stringResource(R.string.ai_history_empty),
            modifier = modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val locale = currentInterfaceLocale()
    val format = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    LazyColumn(modifier.fillMaxWidth().testTag("ai_history_list"), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(chats, key = { it.id }) { chat ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onOpen(chat.id) }, modifier = Modifier.weight(1f).testTag("ai_history_chat")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            format.format(Date(chat.updatedMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onDelete(chat.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ai_delete_chat))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
