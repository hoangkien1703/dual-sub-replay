package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiConnectionTest
import com.kienhoang.dualsubreplay.assistant.AiProvider
import com.kienhoang.dualsubreplay.assistant.ChatHistoryRetention
import com.kienhoang.dualsubreplay.assistant.chatCompletionsUrl
import kotlinx.coroutines.launch

/**
 * Shown in the panel until the key has answered once: choose a service and paste its key, or
 * wait for, retry or start the check. Chat opens only after the check passes.
 */
@Composable
internal fun AiConnectCard(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai_setup_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (aiState.hasKey && aiState.addressValid) {
                AiKeyCheck(aiState, controller)
            } else {
                Text(stringResource(R.string.ai_setup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ai_setup_intro), style = MaterialTheme.typography.bodyMedium)
                AiServicePicker(aiState.settings.provider, includeCustom = false, onSelect = controller::selectProvider)
                AiSetupSteps(aiState, controller)
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("ai_open_settings")) {
                Text(stringResource(R.string.ai_open_settings))
            }
        }
    }
}

/** One row per service with what it costs; Other only in settings, for people who know their address. */
@Composable
private fun AiServicePicker(
    selected: AiProvider,
    includeCustom: Boolean,
    onSelect: (AiProvider) -> Unit,
) {
    Column(Modifier.selectableGroup().testTag("ai_service_picker")) {
        AiProvider.entries.filter { includeCustom || it != AiProvider.CUSTOM }.forEach { provider ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = provider == selected, role = Role.RadioButton, onClick = { onSelect(provider) })
                    .padding(vertical = 2.dp)
                    .testTag("ai_provider_${provider.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = provider == selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(provider.labelRes), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(provider.noteRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The chosen service's three steps and its key buttons, or where to enter Other's address. */
@Composable
private fun AiSetupSteps(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val provider = aiState.settings.provider
    if (provider == AiProvider.CUSTOM && !aiState.addressValid) {
        Text(stringResource(R.string.ai_setup_custom_address), style = MaterialTheme.typography.bodyMedium)
        return
    }
    listOf(provider.getKeyStepRes, provider.createKeyStepRes, R.string.ai_setup_step_paste)
        .distinct()
        .forEachIndexed { index, step ->
            Text("${index + 1}. " + stringResource(step), style = MaterialTheme.typography.bodyMedium)
        }
    AiKeyButtons(provider, controller)
    Text(
        stringResource(R.string.ai_settings_key_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A saved key that has not answered yet: checking, why it failed, or a button to check it. */
@Composable
private fun AiKeyCheck(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    when (val test = aiState.connectionTest) {
        AiConnectionTest.Testing ->
            Row(Modifier.testTag("ai_key_checking"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ai_checking), style = MaterialTheme.typography.titleMedium)
            }
        is AiConnectionTest.Failed -> {
            Text(stringResource(R.string.ai_check_failed_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(test.failure.kind.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ai_key_check_failed"),
            )
            test.failure.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Button(onClick = controller::testConnection, modifier = Modifier.testTag("ai_check_key")) {
                Text(stringResource(R.string.ai_retry))
            }
            AiKeyButtons(aiState.settings.provider, controller)
        }
        AiConnectionTest.Idle, AiConnectionTest.Passed -> {
            Text(stringResource(R.string.ai_check_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_check_message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = controller::testConnection, modifier = Modifier.testTag("ai_check_key")) {
                Text(stringResource(R.string.ai_check_key))
            }
        }
    }
}

/** "Get a free key" and "Paste key". Pasting recognizes the service from the key. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiKeyButtons(
    provider: AiProvider,
    controller: AiAssistantController,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Int?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        provider.keyPageUrl?.let { url ->
            Button(
                onClick = { notice = if (openExternalPage(context, url)) null else R.string.navigation_no_browser },
                modifier = Modifier.testTag("ai_get_key"),
            ) { Text(stringResource(if (provider.freeKeys) R.string.ai_get_free_key else R.string.ai_get_key)) }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val text =
                        clipboard
                            .getClipEntry()
                            ?.clipData
                            ?.firstText(context)
                            .orEmpty()
                    notice =
                        when {
                            text.isBlank() -> R.string.ai_key_clipboard_empty
                            controller.saveKey(text) == null -> R.string.ai_key_not_a_key
                            else -> null
                        }
                }
            },
            modifier = Modifier.testTag("ai_paste_key"),
        ) { Text(stringResource(R.string.ai_paste_key)) }
    }
    notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

private fun ClipData.firstText(context: android.content.Context): String? =
    if (itemCount >
        0
    ) {
        getItemAt(0).coerceToText(context)?.toString()
    } else {
        null
    }

/** More settings → AI assistant, and the settings page inside the panel. */
@Composable
internal fun AiAssistantSettingsSection(host: AiAssistantHost) {
    val controller = host.controller
    val aiState by controller.state.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(R.string.ai_settings_enabled_title),
        description = stringResource(R.string.ai_settings_enabled_description),
        checked = aiState.settings.enabled,
        onCheckedChange = controller::setEnabled,
        testTag = "ai_enabled_switch",
    )
    if (!aiState.settings.enabled) return
    SettingsSectionDivider()
    SettingsSubheading(stringResource(R.string.ai_settings_service))
    AiServicePicker(aiState.settings.provider, includeCustom = true, onSelect = controller::selectProvider)
    if (aiState.settings.provider == AiProvider.CUSTOM) AiCustomAddressField(aiState, controller)
    SettingsSectionDivider()
    SettingsSubheading(stringResource(R.string.ai_settings_key_heading))
    AiKeySettings(aiState, controller)
    SettingsSectionDivider()
    SettingsSubheading(stringResource(R.string.ai_memory_title))
    AiMemorySettings(aiState, controller)
    SettingsSectionDivider()
    AiHistorySettings(aiState, controller)
    SettingsSectionDivider()
    AiAdvancedSettings(aiState, controller)
}

@Composable
private fun AiCustomAddressField(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val address = aiState.settings.customBaseUrl
    OutlinedTextField(
        value = address,
        onValueChange = controller::setCustomBaseUrl,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_base_url"),
        label = { Text(stringResource(R.string.ai_settings_base_url)) },
        placeholder = { Text("https://example.com/v1") },
        singleLine = true,
        isError = address.isNotBlank() && chatCompletionsUrl(address) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

@Composable
private fun AiKeySettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val provider = aiState.settings.provider
    val hint = aiState.keyHints[provider]
    if (hint != null) {
        Text(
            stringResource(R.string.ai_settings_key_saved, stringResource(provider.labelRes), hint),
            modifier = Modifier.testTag("ai_key_saved"),
        )
        AiConnectionStatus(aiState)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = controller::testConnection,
                enabled = aiState.connectionTest != AiConnectionTest.Testing,
                modifier = Modifier.testTag("ai_test_connection"),
            ) { Text(stringResource(R.string.ai_settings_test)) }
            OutlinedButton(onClick = { controller.removeKey(provider) }, modifier = Modifier.testTag("ai_remove_key")) {
                Text(stringResource(R.string.ai_settings_remove_key))
            }
        }
    } else {
        AiSetupSteps(aiState, controller)
        AiTypedKeyField(controller)
    }
    if (provider == AiProvider.GEMINI) SettingsHint(stringResource(R.string.ai_settings_gemini_note))
}

@Composable
private fun AiTypedKeyField(controller: AiAssistantController) {
    var typed by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            invalid = false
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_key_field"),
        label = { Text(stringResource(R.string.ai_settings_key_label)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.ai_key_not_a_key)) }) else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    TextButton(
        onClick = {
            if (controller.saveKey(typed) == null) invalid = true else typed = ""
        },
        enabled = typed.isNotBlank(),
        modifier = Modifier.testTag("ai_save_key"),
    ) { Text(stringResource(R.string.ai_settings_save_key)) }
}

/** Checking, why the last check failed, ready, or not checked yet (a new key, model or address). */
@Composable
private fun AiConnectionStatus(aiState: AiAssistantUiState) {
    val test = aiState.connectionTest
    val failure = (test as? AiConnectionTest.Failed)?.failure
    val text =
        when {
            test == AiConnectionTest.Testing -> stringResource(R.string.ai_checking)
            failure != null -> stringResource(failure.kind.messageRes())
            aiState.keyChecked -> stringResource(R.string.ai_settings_test_passed)
            else -> stringResource(R.string.ai_settings_not_checked)
        }
    val color =
        when {
            failure != null -> MaterialTheme.colorScheme.error
            test == AiConnectionTest.Testing || aiState.keyChecked -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp).testTag("ai_test_result"))
    failure?.detail?.let { SettingsHint(it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiHistorySettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    var deleted by remember { mutableStateOf(false) }
    SettingsSubheading(stringResource(R.string.ai_settings_history_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatHistoryRetention.entries.forEach { retention ->
            FilterChip(
                selected = aiState.settings.historyRetention == retention,
                onClick = { controller.setHistoryRetention(retention) },
                label = { Text(stringResource(retention.labelRes)) },
                modifier = Modifier.testTag("ai_history_${retention.key}"),
            )
        }
    }
    SettingsHint(stringResource(R.string.ai_settings_history_description))
    OutlinedButton(
        onClick = {
            controller.deleteHistory()
            deleted = true
        },
        modifier = Modifier.padding(top = 8.dp).testTag("ai_delete_history"),
    ) { Text(stringResource(R.string.ai_settings_delete_history)) }
    if (deleted) SettingsHint(stringResource(R.string.ai_settings_history_deleted))
}

@Composable
private fun AiAdvancedSettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("ai_advanced")) {
        Text(stringResource(R.string.ai_settings_advanced))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    if (!expanded) return
    val settings = aiState.settings
    val provider = settings.provider
    OutlinedTextField(
        value = settings.models[provider].orEmpty(),
        onValueChange = { controller.setModel(provider, it) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_model"),
        label = { Text(stringResource(R.string.ai_settings_model)) },
        placeholder = { Text(provider.defaultModel) },
        singleLine = true,
    )
    if (provider == AiProvider.OPENROUTER) SettingsHint(stringResource(R.string.ai_settings_openrouter_note))
}
