package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiActionKind
import com.kienhoang.dualsubreplay.assistant.AiActionRecord
import com.kienhoang.dualsubreplay.assistant.AiActionState

/** Undo, the card's button, and Cancel, each with the action's id. */
internal class AiActionHandlers(
    val onUndo: (String) -> Unit,
    val onConfirm: (String) -> Unit,
    val onCancel: (String) -> Unit,
)

private val AiActionKind.icon: ImageVector
    get() =
        when (this) {
            AiActionKind.LOOK -> Icons.Default.Subtitles
            AiActionKind.PLAYBACK -> Icons.Default.Replay
            AiActionKind.SETTING -> Icons.Default.Tune
            AiActionKind.WORD -> Icons.Default.BookmarkAdd
            AiActionKind.VIDEO -> Icons.Default.OndemandVideo
            AiActionKind.TRANSLATION -> Icons.Default.Translate
        }

/** What an answer did or wants to do: chips for actions that ran, cards for those waiting for a tap. */
@Composable
internal fun AiActionList(
    actions: List<AiActionRecord>,
    handlers: AiActionHandlers,
) {
    Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.forEach { action ->
            if (action.state == AiActionState.WAITING) AiActionCard(action, handlers) else AiActionChip(action, handlers)
        }
    }
}

@Composable
private fun AiActionCard(
    action: AiActionRecord,
    handlers: AiActionHandlers,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("ai_action_card"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(action.kind.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(action.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { handlers.onCancel(action.id) }, modifier = Modifier.testTag("ai_action_cancel")) {
                    Text(stringResource(R.string.ai_action_cancel))
                }
                Button(onClick = { handlers.onConfirm(action.id) }, modifier = Modifier.testTag("ai_action_confirm")) {
                    Text(action.button ?: stringResource(R.string.ai_action_apply))
                }
            }
        }
    }
}

@Composable
private fun AiActionChip(
    action: AiActionRecord,
    handlers: AiActionHandlers,
) {
    val (icon, tint) =
        when (action.state) {
            AiActionState.DONE ->
                (if (action.kind == AiActionKind.LOOK) action.kind.icon else Icons.Default.Check) to
                    MaterialTheme.colorScheme.primary
            AiActionState.UNDONE -> Icons.AutoMirrored.Filled.Undo to MaterialTheme.colorScheme.outline
            AiActionState.CANCELLED, AiActionState.WAITING -> Icons.Default.Close to MaterialTheme.colorScheme.outline
            AiActionState.FAILED -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        }
    val status =
        when (action.state) {
            AiActionState.UNDONE -> R.string.ai_action_undone
            AiActionState.CANCELLED -> R.string.ai_action_not_done
            AiActionState.FAILED -> R.string.ai_action_failed
            AiActionState.DONE, AiActionState.WAITING -> null
        }
    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("ai_action_chip"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = if (action.undoable) 2.dp else 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f, fill = false).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = if (status != null) TextDecoration.LineThrough else null,
                    color = if (status != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                status?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (action.undoable && action.state == AiActionState.DONE) {
                TextButton(onClick = { handlers.onUndo(action.id) }, modifier = Modifier.testTag("ai_action_undo")) {
                    Text(stringResource(R.string.ai_action_undo))
                }
            }
        }
    }
}
