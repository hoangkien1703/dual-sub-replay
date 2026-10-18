package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiWebSearchMode
import com.kienhoang.dualsubreplay.assistant.TAVILY_KEY_PAGE_URL
import com.kienhoang.dualsubreplay.assistant.searchesWeb
import kotlinx.coroutines.launch

/** One line under the section's title: YouTube search, web search and the screen as they are now. */
@Composable
internal fun aiSearchSummary(aiState: AiAssistantUiState): String {
    val settings = aiState.settings
    val on = stringResource(R.string.ai_action_on)
    val off = stringResource(R.string.ai_action_off)
    return stringResource(
        R.string.ai_search_summary,
        if (settings.youtubeSearch) on else off,
        stringResource(settings.webSearch.labelRes),
        if (settings.screenLook) on else off,
    )
}

/**
 * AI settings → Search and screen: Find YouTube videos, where web searches go (off, the service's
 * own paid search, or Tavily with the user's free key), and Look at the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiSearchSettings(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val settings = aiState.settings
    SettingsSwitchRow(
        title = stringResource(R.string.ai_search_youtube_title),
        description = stringResource(R.string.ai_search_youtube_description),
        checked = settings.youtubeSearch,
        onCheckedChange = controller::setYouTubeSearch,
        testTag = "ai_youtube_search_switch",
    )
    SettingsSectionDivider()
    SettingsSubheading(stringResource(R.string.ai_search_web_title))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiWebSearchMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.webSearch == mode,
                onClick = { controller.setWebSearch(mode) },
                label = { Text(stringResource(mode.labelRes)) },
                modifier = Modifier.testTag("ai_web_search_${mode.key}"),
            )
        }
    }
    val service = stringResource(settings.provider.labelRes)
    when (settings.webSearch) {
        AiWebSearchMode.OFF -> SettingsHint(stringResource(R.string.ai_search_web_off_note))
        AiWebSearchMode.SERVICE ->
            if (settings.provider.searchesWeb) {
                SettingsHint(stringResource(R.string.ai_search_web_service_note, service))
            } else {
                Text(
                    stringResource(R.string.ai_search_web_service_unavailable, service),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp).testTag("ai_web_search_unavailable"),
                )
            }
        AiWebSearchMode.TAVILY -> {
            SettingsHint(stringResource(R.string.ai_search_web_tavily_note))
            AiTavilyKey(aiState, controller)
        }
    }
    SettingsSectionDivider()
    SettingsSwitchRow(
        title = stringResource(R.string.ai_search_screen_title),
        description = stringResource(R.string.ai_search_screen_description),
        checked = settings.screenLook,
        onCheckedChange = controller::setScreenLook,
        testTag = "ai_screen_switch",
    )
}

/** The saved Tavily key with Remove, or how to get one: Get a free key, Paste key, or type it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiTavilyKey(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val hint = aiState.tavilyKeyHint
    if (hint != null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_tavily_key_row"), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ai_settings_key_ending, hint), modifier = Modifier.weight(1f))
            IconButton(onClick = controller::removeTavilyKey, modifier = Modifier.testTag("ai_remove_tavily_key")) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ai_settings_remove_key))
            }
        }
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Int?>(null) }
    var typed by remember { mutableStateOf("") }
    Text(
        stringResource(R.string.ai_search_tavily_steps),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { notice = if (openExternalPage(context, TAVILY_KEY_PAGE_URL)) null else R.string.navigation_no_browser },
            modifier = Modifier.testTag("ai_get_tavily_key"),
        ) { Text(stringResource(R.string.ai_get_free_key)) }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val text =
                        clipboard
                            .getClipEntry()
                            ?.clipData
                            ?.firstClipText(context)
                            .orEmpty()
                    notice =
                        when {
                            text.isBlank() -> R.string.ai_key_clipboard_empty
                            !controller.saveTavilyKey(text) -> R.string.ai_search_tavily_not_a_key
                            else -> null
                        }
                }
            },
            modifier = Modifier.testTag("ai_paste_tavily_key"),
        ) { Text(stringResource(R.string.ai_paste_key)) }
    }
    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            notice = null
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ai_tavily_key_field"),
        label = { Text(stringResource(R.string.ai_search_tavily_key_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    TextButton(
        onClick = { if (controller.saveTavilyKey(typed)) typed = "" else notice = R.string.ai_search_tavily_not_a_key },
        enabled = typed.isNotBlank(),
        modifier = Modifier.testTag("ai_save_tavily_key"),
    ) { Text(stringResource(R.string.ai_settings_save_key)) }
    notice?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

private fun ClipData.firstClipText(context: android.content.Context): String? =
    if (itemCount > 0) getItemAt(0).coerceToText(context)?.toString() else null
