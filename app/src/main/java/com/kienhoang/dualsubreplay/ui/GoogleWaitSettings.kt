package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.aiTranslationModelChoices
import com.kienhoang.dualsubreplay.translation.GoogleWait
import com.kienhoang.dualsubreplay.translation.SubtitleTranslator

/** The chip text of each Wait for Google choice. */
@get:StringRes
internal val GoogleWait.labelRes: Int
    get() =
        when (this) {
            GoogleWait.AUTO -> R.string.settings_google_wait_auto
            GoogleWait.TWO_SECONDS -> R.string.settings_google_wait_2
            GoogleWait.FOUR_SECONDS -> R.string.settings_google_wait_4
            GoogleWait.EIGHT_SECONDS -> R.string.settings_google_wait_8
        }

/** Settings → Translation → Wait for Google, shown while Google's lines are shown first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoogleWaitSettings(
    wait: GoogleWait,
    onChange: (GoogleWait) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_google_wait_title))
        Text(
            stringResource(R.string.settings_google_wait_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoogleWait.entries.forEach { option ->
                FilterChip(
                    selected = wait == option,
                    onClick = { onChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.testTag("google_wait_${option.storageValue}"),
                )
            }
        }
    }
}

/** The title of each Translate subtitles with choice. */
@get:StringRes
internal val SubtitleTranslator.titleRes: Int
    get() =
        when (this) {
            SubtitleTranslator.GOOGLE -> R.string.settings_translator_google
            SubtitleTranslator.AI_WITH_GOOGLE -> R.string.settings_translator_ai_with_google
            SubtitleTranslator.AI_ONLY -> R.string.settings_translator_ai_only
            SubtitleTranslator.ON_DEVICE -> R.string.settings_translator_on_device
        }

@get:StringRes
internal val SubtitleTranslator.descriptionRes: Int
    get() =
        when (this) {
            SubtitleTranslator.GOOGLE -> R.string.settings_translator_google_description
            SubtitleTranslator.AI_WITH_GOOGLE -> R.string.settings_translator_ai_with_google_description
            SubtitleTranslator.AI_ONLY -> R.string.settings_translator_ai_only_description
            SubtitleTranslator.ON_DEVICE -> R.string.settings_translator_on_device_description
        }

/**
 * Settings → Translation → AI model for translation, shown with an AI translator: "Same as AI
 * assistant" (saved as empty, the default), then the other models of the assistant's service. A
 * model the service no longer offers falls back to the assistant's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiTranslationModelSettings(
    chosen: String,
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val (assistantModel, choices) = remember(context) { context.aiTranslationModelChoices() }
    val selected = chosen.takeIf { it in choices }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_ai_translation_model_title))
        if (choices.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_ai_translation_model_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onChange("") },
                label = { Text(stringResource(R.string.settings_ai_translation_model_same, assistantModel)) },
                modifier = Modifier.testTag("ai_translation_model_same"),
            )
            choices.forEach { model ->
                FilterChip(
                    selected = selected == model,
                    onClick = { onChange(model) },
                    label = { Text(model) },
                    modifier = Modifier.testTag("ai_translation_model_$model"),
                )
            }
        }
    }
}

/** Where the AI translator's service and key come from, with a way there when the dialog has one. */
@Composable
internal fun AiTranslationServiceNote(onOpenAiSettings: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.settings_ai_translation_service_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onOpenAiSettings != null) {
            TextButton(onClick = onOpenAiSettings, modifier = Modifier.testTag("ai_translation_open_assistant_settings")) {
                Text(stringResource(R.string.settings_ai_translation_service_open))
            }
        }
    }
}
