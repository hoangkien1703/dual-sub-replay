package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiModelCheck
import com.kienhoang.dualsubreplay.assistant.AiModelInfo
import com.kienhoang.dualsubreplay.assistant.AiModelList
import com.kienhoang.dualsubreplay.assistant.AiPanelPage
import com.kienhoang.dualsubreplay.assistant.AiThinking

private val ModelButtonMaxWidth = 180.dp

/**
 * Under the chat box, as in most chat apps: [leading] (the + button), the model, with a few
 * suggestions and the service's whole list, and the thinking level. A new model answers a tiny
 * test before the chat uses it.
 */
@Composable
internal fun AiChatOptionsBar(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    leading: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
        aiState.modelCheck?.let { check -> AiModelCheckRow(check, aiState.settings.modelFor(aiState.settings.provider), controller) }
        // On a narrow panel the model name gives way first and ends with "…".
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            leading()
            AiModelMenu(aiState, controller, Modifier.weight(1f, fill = false))
            AiThinkingMenu(aiState.settings.thinking, controller)
        }
    }
}

@Composable
private fun AiModelMenu(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    modifier: Modifier = Modifier,
) {
    val provider = aiState.settings.provider
    val current = aiState.settings.modelFor(provider)
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("ai_model_button")) {
            Text(
                current,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = ModelButtonMaxWidth),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(current) + provider.suggestedModels).distinct().forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        open = false
                        controller.chooseModel(model)
                    },
                    leadingIcon = { SelectedMark(model == current) },
                    modifier = Modifier.testTag("ai_model_option"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_models_more, stringResource(provider.labelRes))) },
                onClick = {
                    open = false
                    controller.showPage(AiPanelPage.MODELS)
                },
                modifier = Modifier.testTag("ai_models_more"),
            )
        }
    }
}

@Composable
private fun AiThinkingMenu(
    thinking: AiThinking,
    controller: AiAssistantController,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("ai_effort_button")) {
            // The brain icon says "Thinking", so the button shows only the level and stays short.
            Icon(
                Icons.Default.Psychology,
                contentDescription = stringResource(R.string.ai_effort_button, stringResource(thinking.labelRes)),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(thinking.labelRes), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AiThinking.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(option.labelRes))
                            Text(
                                stringResource(option.noteRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        controller.setThinking(option)
                    },
                    leadingIcon = { SelectedMark(option == thinking) },
                    modifier = Modifier.testTag("ai_effort_${option.key}"),
                )
            }
        }
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) {
        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    } else {
        Spacer(Modifier.size(24.dp))
    }
}

/** "Trying gpt-5…", or why the chosen model did not answer and which one the chat keeps. */
@Composable
private fun AiModelCheckRow(
    check: AiModelCheck,
    current: String,
    controller: AiAssistantController,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("ai_model_check"), verticalAlignment = Alignment.CenterVertically) {
        when (check) {
            is AiModelCheck.Checking -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ai_model_trying, check.model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            is AiModelCheck.Failed -> {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ai_model_failed, check.model, current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(stringResource(check.failure.kind.messageRes()), style = MaterialTheme.typography.bodySmall)
                    check.failure.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = controller::dismissModelCheck) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ai_dismiss))
                }
            }
        }
    }
}

/** Every model the service offers, with search; a typed name that is not listed can be used too. */
@Composable
internal fun AiModelsPage(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    modifier: Modifier = Modifier,
) {
    val provider = aiState.settings.provider
    val current = aiState.settings.modelFor(provider)
    val list = aiState.modelList
    val loaded = (list as? AiModelList.Loaded)?.takeIf { it.provider == provider }
    LaunchedEffect(provider) { if (loaded == null && list != AiModelList.Loading) controller.loadModels() }
    var query by rememberSaveable { mutableStateOf("") }
    val choose = { model: String ->
        controller.chooseModel(model)
        controller.showPage(AiPanelPage.CHAT)
    }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("ai_models_search"),
            placeholder = { Text(stringResource(R.string.ai_models_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
        )
        val needle = query.trim()
        val models = loaded?.models.orEmpty()
        val shown = if (needle.isEmpty()) models else models.filter { it.id.contains(needle, ignoreCase = true) }
        LazyColumn(Modifier.weight(1f).testTag("ai_models_list"), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (needle.isNotEmpty() && models.none { it.id == needle }) {
                item(key = "typed") { AiModelRow(AiModelInfo(needle), selected = false, typed = true) { choose(needle) } }
            }
            when {
                list == AiModelList.Loading -> item(key = "loading") { AiModelsLoading() }
                list is AiModelList.Failed -> item(key = "failed") { AiModelsFailed(list, controller) }
                loaded != null && shown.isEmpty() && needle.isEmpty() -> item(key = "empty") { AiModelsEmpty() }
            }
            items(shown, key = { it.id }) { model -> AiModelRow(model, selected = model.id == current) { choose(model.id) } }
        }
    }
}

@Composable
private fun AiModelRow(
    model: AiModelInfo,
    selected: Boolean,
    typed: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(if (typed) "ai_models_use_typed" else "ai_models_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectedMark(selected)
        Spacer(Modifier.width(8.dp))
        Text(
            if (typed) stringResource(R.string.ai_models_use, model.id) else model.id,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (model.free) AiModelTag(stringResource(R.string.ai_model_free))
        if (model.pictures) AiModelTag(stringResource(R.string.ai_model_pictures))
    }
}

@Composable
private fun AiModelTag(text: String) {
    Surface(
        modifier = Modifier.padding(start = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun AiModelsLoading() {
    Row(Modifier.padding(16.dp).testTag("ai_models_loading"), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.ai_models_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AiModelsFailed(
    list: AiModelList.Failed,
    controller: AiAssistantController,
) {
    Column(Modifier.padding(16.dp).testTag("ai_models_failed"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(list.failure.kind.messageRes()), color = MaterialTheme.colorScheme.error)
        list.failure.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        OutlinedButton(onClick = controller::loadModels) { Text(stringResource(R.string.ai_retry)) }
    }
}

@Composable
private fun AiModelsEmpty() {
    Text(
        stringResource(R.string.ai_models_empty),
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
