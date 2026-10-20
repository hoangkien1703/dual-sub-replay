package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.PageTokenMode
import kotlinx.coroutines.launch

/** More settings → Study tools: play modes, skip silence, known words, AnkiDroid, blur box and page tokens. */
@Composable
internal fun StudyToolsSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val settings by rememberStudySettings()
    StudyPlayModeSettings(settings) { mode -> preferences.edit().putString(STUDY_PLAY_MODE_PREFERENCE, mode.storageValue).apply() }
    if (settings.playMode == StudyPlayMode.REPEAT_LINE) {
        StudyRepeatCountSettings(settings.repeatCount) { count -> preferences.edit().putInt(STUDY_REPEAT_COUNT_PREFERENCE, count).apply() }
    }
    if (settings.playMode == StudyPlayMode.NEW_WORD_LINES && !settings.knownWords) {
        SettingsHint(stringResource(R.string.study_play_mode_needs_known_words))
    }
    SettingsHint(stringResource(R.string.study_play_mode_note))
    SettingsSectionDivider()
    SkipSilenceSettings(settings.skipSilence) { skip -> preferences.edit().putString(SKIP_SILENCE_PREFERENCE, skip.storageValue).apply() }
    SettingsSectionDivider()
    KnownWordsSettings(
        settings = settings,
        onModeChange = { mode -> preferences.edit().putString(KNOWN_WORDS_PREFERENCE, mode.storageValue).apply() },
        onAfterChange = { count -> preferences.edit().putInt(KNOWN_WORDS_AFTER_PREFERENCE, count).apply() },
    )
    if (settings.knownWords) {
        SettingsSwitchRow(
            title = stringResource(R.string.study_mark_new_words_title),
            description = stringResource(R.string.study_mark_new_words_description),
            checked = settings.markNewWords,
            onCheckedChange = { preferences.edit().putBoolean(MARK_NEW_WORDS_PREFERENCE, it).apply() },
            testTag = "mark_new_words_switch",
        )
    }
    KnownWordsCountRow()
    SettingsSectionDivider()
    SettingsSwitchRow(
        title = stringResource(R.string.study_anki_title),
        description = stringResource(R.string.study_anki_description),
        checked = settings.ankiDroid,
        onCheckedChange = { preferences.edit().putBoolean(ANKIDROID_PREFERENCE, it).apply() },
        testTag = "ankidroid_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.study_blur_box_title),
        description = stringResource(R.string.study_blur_box_description),
        checked = settings.blurBox,
        onCheckedChange = { preferences.edit().putBoolean(BLUR_BOX_PREFERENCE, it).apply() },
        testTag = "blur_box_switch",
    )
    PageTokenSettings(settings.pageTokenCaptions) { mode ->
        // A new choice starts over: Automatic learns again whether YouTube wants a token.
        preferences
            .edit()
            .putString(PAGE_TOKEN_CAPTIONS_PREFERENCE, mode.storageValue)
            .remove(PAGE_TOKEN_NEEDED_PREFERENCE)
            .apply()
    }
}

@Composable
private fun StudyPlayModeSettings(
    settings: StudySettings,
    onChange: (StudyPlayMode) -> Unit,
) {
    SettingsSubheading(stringResource(R.string.study_play_mode_title))
    StudyPlayMode.entries.forEach { mode ->
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onChange(mode) }
                    .padding(vertical = 4.dp)
                    .testTag("study_play_mode_${mode.storageValue}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = settings.playMode == mode, onClick = { onChange(mode) })
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(mode.titleRes))
                Text(
                    stringResource(mode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyRepeatCountSettings(
    count: Int,
    onChange: (Int) -> Unit,
) {
    FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STUDY_REPEAT_COUNTS.forEach { option ->
            FilterChip(
                selected = count == option,
                onClick = { onChange(option) },
                label = { Text(pluralStringResource(R.plurals.study_repeat_count, option, option)) },
                modifier = Modifier.testTag("study_repeat_$option"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipSilenceSettings(
    skip: SkipSilence,
    onChange: (SkipSilence) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.study_skip_silence_title))
        Text(
            stringResource(R.string.study_skip_silence_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SkipSilence.entries.forEach { option ->
                FilterChip(
                    selected = skip == option,
                    onClick = { onChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.testTag("skip_silence_${option.storageValue}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnownWordsSettings(
    settings: StudySettings,
    onModeChange: (KnownWordsMode) -> Unit,
    onAfterChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.study_known_words_title))
        Text(
            stringResource(R.string.study_known_words_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KnownWordsMode.entries.forEach { option ->
                FilterChip(
                    selected = settings.knownWordsMode == option,
                    onClick = { onModeChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.testTag("known_words_${option.storageValue}"),
                )
            }
        }
        if (settings.knownWordsMode == KnownWordsMode.AUTO) {
            Text(
                stringResource(R.string.study_known_words_after_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KNOWN_WORDS_AFTER_CHOICES.forEach { option ->
                    FilterChip(
                        selected = settings.knownWordsAfter == option,
                        onClick = { onAfterChange(option) },
                        label = { Text(pluralStringResource(R.plurals.study_repeat_count, option, option)) },
                        modifier = Modifier.testTag("known_words_after_$option"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageTokenSettings(
    mode: PageTokenMode,
    onChange: (PageTokenMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.study_page_token_title))
        Text(
            stringResource(R.string.study_page_token_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageTokenMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { onChange(option) },
                    label = { Text(stringResource(pageTokenModeLabel(option))) },
                    modifier = Modifier.testTag("page_token_${option.storageValue}"),
                )
            }
        }
    }
}

@StringRes
internal fun pageTokenModeLabel(mode: PageTokenMode): Int =
    when (mode) {
        PageTokenMode.AUTO -> R.string.study_page_token_auto
        PageTokenMode.ON -> R.string.study_page_token_on
        PageTokenMode.OFF -> R.string.study_page_token_off
    }

/** How many words are marked as known, with Clear known words behind a confirmation. */
@Composable
private fun KnownWordsCountRow() {
    val context = LocalContext.current
    val repository = remember(context) { KnownWordsRepository.get(context) }
    val total by repository.total.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(repository) { repository.refreshTotal() }
    val count = total ?: return
    SettingsHint(pluralStringResource(R.plurals.study_known_words_count, count, count))
    if (count > 0) {
        TextButton(onClick = { confirming = true }, modifier = Modifier.testTag("clear_known_words")) {
            Text(stringResource(R.string.study_known_words_clear), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.study_known_words_clear_title)) },
            text = { Text(stringResource(R.string.study_known_words_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        scope.launch { repository.clearAll() }
                    },
                    modifier = Modifier.testTag("confirm_clear_known_words"),
                ) { Text(stringResource(R.string.study_known_words_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}
