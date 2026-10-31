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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
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
import com.kienhoang.dualsubreplay.data.AnkiConnectNotes
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.PageTokenMode
import kotlinx.coroutines.launch

/** More settings → Study tools: play modes, skip silence, known words, Send to, blur box and page tokens. */
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
    if (settings.ankiDroid) {
        SendToSettings(
            settings = settings,
            onAppChange = { app -> preferences.edit().putString(SEND_TO_APP_PREFERENCE, app.storageValue).apply() },
            onDeckChange = { deck ->
                val editor = preferences.edit()
                if (deck == null) editor.remove(ANKICONNECT_DECK_PREFERENCE) else editor.putString(ANKICONNECT_DECK_PREFERENCE, deck)
                editor.apply()
            },
        )
    }
    SettingsSwitchRow(
        title = stringResource(R.string.study_blur_box_title),
        description = stringResource(R.string.study_blur_box_description),
        checked = settings.blurBox,
        onCheckedChange = { preferences.edit().putBoolean(BLUR_BOX_PREFERENCE, it).apply() },
        testTag = "blur_box_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.study_kanji_details_title),
        description = stringResource(R.string.study_kanji_details_description),
        checked = settings.kanjiDetails,
        onCheckedChange = { preferences.edit().putBoolean(KANJI_DETAILS_PREFERENCE, it).apply() },
        testTag = "kanji_details_switch",
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

/**
 * Where the Send to button sends words: the same choice as the button's ▾ list. AnkiConnect also
 * picks its deck, from the decks AnkiConnect lists (Automatic: DualSub Replay, else Default).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendToSettings(
    settings: StudySettings,
    onAppChange: (FlashcardApp) -> Unit,
    onDeckChange: (String?) -> Unit,
) {
    var choosingDeck by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 6.dp)) {
        Text(stringResource(R.string.study_send_to_app_title), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlashcardApp.entries.forEach { option ->
                FilterChip(
                    selected = settings.sendTo == option,
                    onClick = { onAppChange(option) },
                    label = { Text(stringResource(option.nameRes)) },
                    modifier = Modifier.testTag("send_to_app_${option.storageValue}"),
                )
            }
        }
        Text(
            stringResource(
                if (settings.sendTo ==
                    FlashcardApp.ANKICONNECT
                ) {
                    R.string.study_ankiconnect_description
                } else {
                    R.string.study_ankidroid_description
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.sendTo == FlashcardApp.ANKICONNECT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.study_ankiconnect_deck_value,
                        settings.ankiConnectDeck ?: stringResource(R.string.study_ankiconnect_deck_automatic),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { choosingDeck = true }, modifier = Modifier.testTag("ankiconnect_choose_deck")) {
                    Text(stringResource(R.string.study_ankiconnect_deck_choose))
                }
            }
        }
    }
    if (choosingDeck) {
        AnkiConnectDeckDialog(
            chosen = settings.ankiConnectDeck,
            onPick = { deck ->
                choosingDeck = false
                onDeckChange(deck)
            },
            onDismiss = { choosingDeck = false },
        )
    }
}

/** The decks AnkiConnect lists, asked when the dialog opens, with Automatic first. */
@Composable
private fun AnkiConnectDeckDialog(
    chosen: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val ankiConnect = remember(context) { AnkiConnectNotes.create(context) }
    // null while loading; a failure shows that nothing answered.
    val decks by produceState<Result<List<String>>?>(null) { value = runCatching { ankiConnect.deckNames() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.study_ankiconnect_deck_title), onClose = onDismiss) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val loaded = decks
                when {
                    loaded == null -> Text(stringResource(R.string.study_ankiconnect_deck_loading))
                    loaded.isFailure ->
                        Text(
                            stringResource(R.string.study_ankiconnect_not_running),
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {
                        DeckOption(
                            stringResource(R.string.study_ankiconnect_deck_automatic),
                            chosen == null,
                            "ankiconnect_deck_automatic",
                        ) { onPick(null) }
                        loaded.getOrDefault(emptyList()).sorted().forEach { deck ->
                            DeckOption(deck, chosen == deck, "ankiconnect_deck_option") { onPick(deck) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun DeckOption(
    name: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
                .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(8.dp))
        Text(name)
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
