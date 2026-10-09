package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiMemory
import com.kienhoang.dualsubreplay.assistant.AiMemoryUse
import com.kienhoang.dualsubreplay.assistant.AiPanelPage
import com.kienhoang.dualsubreplay.assistant.MAX_AI_INSTRUCTIONS_CHARS
import com.kienhoang.dualsubreplay.assistant.MAX_AI_MEMORIES
import com.kienhoang.dualsubreplay.assistant.MAX_AI_MEMORY_CHARS
import com.kienhoang.dualsubreplay.assistant.aiMemoryText

/** The panel's Memory page, which Manage on a memory chip and the what's-new card open. */
@Composable
internal fun AiMemoryPage(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("ai_memory_page"),
    ) { AiMemorySettings(aiState, controller) }
}

/** Use memory, the user's instructions, and the saved memories with edit, delete and Delete all. */
@Composable
internal fun AiMemorySettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val memory = aiState.memory
    SettingsSwitchRow(
        title = stringResource(R.string.ai_memory_enabled_title),
        description = stringResource(R.string.ai_memory_enabled_description),
        checked = aiState.settings.memoryEnabled,
        onCheckedChange = controller::setMemoryEnabled,
        testTag = "ai_memory_switch",
    )
    AiInstructionsField(memory.instructions, controller::setInstructions)
    SettingsSubheading(stringResource(R.string.ai_memory_saved_title, memory.memories.size, MAX_AI_MEMORIES))
    if (memory.memories.isEmpty()) {
        SettingsHint(stringResource(R.string.ai_memory_empty))
    } else {
        AiMemoryList(memory.memories, controller)
    }
    SettingsHint(stringResource(R.string.ai_memory_note))
}

/** The instructions box keeps its text until Save, so a half-typed instruction is never sent. */
@Composable
private fun AiInstructionsField(
    saved: String,
    onSave: (String) -> Unit,
) {
    var text by remember(saved) { mutableStateOf(saved) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(MAX_AI_INSTRUCTIONS_CHARS) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_instructions"),
        label = { Text(stringResource(R.string.ai_instructions_title)) },
        placeholder = { Text(stringResource(R.string.ai_instructions_placeholder)) },
        supportingText = { Text(stringResource(R.string.ai_instructions_count, text.length, MAX_AI_INSTRUCTIONS_CHARS)) },
        minLines = 3,
        maxLines = 8,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.ai_instructions_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSave(text.trim()) }, enabled = text.trim() != saved, modifier = Modifier.testTag("ai_instructions_save")) {
            Text(stringResource(R.string.ai_memory_save))
        }
    }
}

@Composable
private fun AiMemoryList(
    memories: List<AiMemory>,
    controller: AiAssistantController,
) {
    var editing by remember { mutableStateOf<AiMemory?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        memories.forEach { memory ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("ai_memory_item"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(memory.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(onClick = { editing = memory }, modifier = Modifier.testTag("ai_memory_edit")) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.ai_memory_edit))
                    }
                    IconButton(onClick = { controller.deleteMemory(memory.id) }, modifier = Modifier.testTag("ai_memory_delete")) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ai_memory_delete))
                    }
                }
            }
        }
    }
    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp).testTag("ai_memory_clear")) {
        Text(stringResource(R.string.ai_memory_clear))
    }
    editing?.let { memory -> AiMemoryEditDialog(memory, onDismiss = { editing = null }) { controller.editMemory(memory.id, it) } }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ai_memory_clear_title)) },
            text = { Text(stringResource(R.string.ai_memory_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        controller.deleteAllMemories()
                    },
                    modifier = Modifier.testTag("ai_memory_clear_confirm"),
                ) { Text(stringResource(R.string.ai_memory_clear_button)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.ai_action_cancel)) } },
        )
    }
}

@Composable
private fun AiMemoryEditDialog(
    memory: AiMemory,
    onDismiss: () -> Unit,
    onSave: (String) -> Boolean,
) {
    var text by remember(memory.id) { mutableStateOf(memory.text) }
    val valid = aiMemoryText(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_memory_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_AI_MEMORY_CHARS) },
                modifier = Modifier.fillMaxWidth().testTag("ai_memory_edit_field"),
                supportingText = { Text(stringResource(R.string.ai_instructions_count, text.length, MAX_AI_MEMORY_CHARS)) },
                isError = !valid,
                maxLines = 4,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (onSave(text)) onDismiss() }, enabled = valid, modifier = Modifier.testTag("ai_memory_edit_save")) {
                Text(stringResource(R.string.ai_memory_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_action_cancel)) } },
    )
}

/** With Use memory on: the chip on a new, empty chat, or the note in a chat started without memory. */
internal fun aiChatMemoryShown(aiState: AiAssistantUiState): Boolean =
    aiState.settings.memoryEnabled && (aiState.messages.isEmpty() || aiState.memoryUse == AiMemoryUse.OFF_IN_CHAT)

/** On a new, empty chat: whether it reads and saves memories. In a chat started without memory, a note says so. */
@Composable
internal fun AiChatMemoryChip(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    if (aiState.messages.isEmpty()) {
        val on = aiState.newChatMemory
        FilterChip(
            selected = on,
            onClick = { controller.setNewChatMemory(!on) },
            label = { Text(stringResource(R.string.ai_chat_memory_chip)) },
            leadingIcon = {
                Icon(if (on) Icons.Default.Check else Icons.Default.Psychology, null, Modifier.size(FilterChipDefaults.IconSize))
            },
            modifier = Modifier.testTag("ai_chat_memory_chip"),
        )
    } else if (aiState.memoryUse == AiMemoryUse.OFF_IN_CHAT) {
        Text(
            stringResource(R.string.ai_chat_memory_off),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ai_chat_memory_off"),
        )
    }
}

/** Once, for people who answered the intro before actions and memory: what is new, with Manage memory and Got it. */
@Composable
internal fun AiNewsCard(controller: AiAssistantController) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai_news_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.ai_news_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                stringResource(R.string.ai_news_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, end = 6.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        controller.dismissNews()
                        controller.showPage(AiPanelPage.MEMORY)
                    },
                    modifier = Modifier.testTag("ai_news_manage"),
                ) { Text(stringResource(R.string.ai_news_manage)) }
                Button(onClick = controller::dismissNews, modifier = Modifier.testTag("ai_news_dismiss")) {
                    Text(stringResource(R.string.ai_news_dismiss))
                }
            }
        }
    }
}
