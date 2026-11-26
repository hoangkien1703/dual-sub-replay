package com.kienhoang.dualsubreplay.ui

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.ANKIDROID_PERMISSION
import com.kienhoang.dualsubreplay.data.AnkiCardsToCount
import com.kienhoang.dualsubreplay.data.AnkiDeck
import com.kienhoang.dualsubreplay.data.AnkiKnownWords
import com.kienhoang.dualsubreplay.data.AnkiKnownWordsResult
import com.kienhoang.dualsubreplay.data.AnkiReadProblem
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.knownWordLanguage
import kotlinx.coroutines.launch

/**
 * The languages Known words from Anki offers, first the one the learner picked when they started,
 * then the first caption language they prefer, then languages that already have known words.
 */
internal fun ankiKnownWordsLanguages(
    learning: String?,
    preferredCaption: String?,
    withKnownWords: Collection<String>,
): List<String> = (listOf(learning, preferredCaption) + withKnownWords.sorted()).mapNotNull(::knownWordLanguage).distinct()

/** Settings → Word learning → Known words: Add words from Anki, while AnkiDroid is on the phone. */
@Composable
internal fun AnkiKnownWordsButton() {
    val context = LocalContext.current
    val anki = remember(context) { AnkiKnownWords(context) }
    if (!remember(anki) { anki.installed() }) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("known_words_from_anki")) {
        Text(stringResource(R.string.study_known_from_anki))
    }
    if (open) AnkiKnownWordsDialog(anki) { open = false }
}

/** What one Add words tap tells the learner. */
private fun ankiResultNote(
    resources: Resources,
    result: AnkiKnownWordsResult,
): String =
    when (result.problem) {
        AnkiReadProblem.NOT_INSTALLED -> resources.getString(R.string.study_anki_not_installed)
        AnkiReadProblem.NO_PERMISSION -> resources.getString(R.string.study_known_from_anki_permission)
        AnkiReadProblem.FAILED -> resources.getString(R.string.study_known_from_anki_failed)
        null ->
            if (result.keys.isEmpty()) {
                resources.getString(R.string.study_known_from_anki_none, result.notes)
            } else {
                resources.getString(R.string.study_known_from_anki_added, result.keys.size, result.notes)
            }
    }

@Composable
private fun AnkiKnownWordsDialog(
    anki: AnkiKnownWords,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { KnownWordsRepository.get(context) }
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var decks by remember { mutableStateOf<List<AnkiDeck>?>(null) }
    var languages by remember { mutableStateOf(emptyList<String>()) }
    var deck by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf<String?>(null) }
    var cards by remember { mutableStateOf(AnkiCardsToCount.WELL_KNOWN) }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var permitted by remember { mutableStateOf(anki.permitted()) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permitted = granted
            if (!granted) note = resources.getString(R.string.study_known_from_anki_permission)
        }
    LaunchedEffect(Unit) {
        val learning = preferences.getString("learning_language", null)
        val caption = preferences.getString("preferred_caption_language", null)
        languages = ankiKnownWordsLanguages(learning, caption, repository.allKnown().keys)
        language = languages.firstOrNull()
        if (!permitted) permission.launch(ANKIDROID_PERMISSION)
    }
    LaunchedEffect(permitted) {
        if (!permitted) return@LaunchedEffect
        val found = anki.decks()
        decks = found.orEmpty()
        if (found == null) note = resources.getString(R.string.study_known_from_anki_failed)
        deck = deck ?: found?.firstOrNull()?.name
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { DialogTitle(stringResource(R.string.study_known_from_anki_title), onClose) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("known_from_anki_dialog")) {
                Text(stringResource(R.string.study_known_from_anki_intro), style = MaterialTheme.typography.bodyMedium)
                AnkiHeading(R.string.study_known_from_anki_deck)
                AnkiDeckList(decks, deck) { deck = it }
                AnkiHeading(R.string.study_known_from_anki_cards)
                AnkiCardsChoice(cards) { cards = it }
                AnkiLanguageChoice(languages, language) { language = it }
                note?.let { AnkiNote(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && deck != null && language != null && permitted,
                onClick = {
                    val chosenDeck = deck ?: return@TextButton
                    val chosenLanguage = language ?: return@TextButton
                    working = true
                    note = resources.getString(R.string.study_known_from_anki_reading)
                    scope.launch {
                        val result = anki.words(chosenDeck, cards, chosenLanguage)
                        if (result.problem == null && result.keys.isNotEmpty()) {
                            repository.setKnown(chosenLanguage, result.keys, known = true)
                        }
                        note = ankiResultNote(resources, result)
                        working = false
                    }
                },
                modifier = Modifier.testTag("known_from_anki_add"),
            ) { Text(stringResource(R.string.study_known_from_anki_add)) }
        },
    )
}

@Composable
private fun AnkiHeading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun AnkiNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp).testTag("known_from_anki_note"),
    )
}

@Composable
private fun AnkiDeckList(
    decks: List<AnkiDeck>?,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    when {
        decks == null -> Unit
        decks.isEmpty() -> SettingsHint(stringResource(R.string.study_known_from_anki_no_decks))
        else ->
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                decks.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == item.name, role = Role.RadioButton) { onSelect(item.name) }
                            .testTag("known_from_anki_deck_${item.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == item.name, onClick = null)
                        Text(item.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnkiCardsChoice(
    selected: AnkiCardsToCount,
    onSelect: (AnkiCardsToCount) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            AnkiCardsToCount.WELL_KNOWN to R.string.study_known_from_anki_well_known,
            AnkiCardsToCount.STUDIED to R.string.study_known_from_anki_studied,
        ).forEach { (option, label) ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(stringResource(label)) },
                modifier = Modifier.testTag("known_from_anki_${option.name.lowercase()}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnkiLanguageChoice(
    languages: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    if (languages.size <= 1) {
        selected?.let {
            val name = languageDisplayName(it, currentInterfaceLocale())
            SettingsHint(stringResource(R.string.study_known_from_anki_language_one, name))
        }
        return
    }
    AnkiHeading(R.string.study_known_from_anki_language)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        languages.forEach { code ->
            FilterChip(
                selected = selected == code,
                onClick = { onSelect(code) },
                label = { Text(languageDisplayName(code, currentInterfaceLocale())) },
                modifier = Modifier.testTag("known_from_anki_language_$code"),
            )
        }
    }
}
