package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.CefrLevel
import com.kienhoang.dualsubreplay.data.EnglishMorphology
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.validClipRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun WordLearningDialog(
    selection: LearningWordSelection,
    autoPronounce: Boolean,
    onTranslateWord: suspend () -> String,
    onSave: suspend (meaning: String, online: Boolean) -> Unit,
    onSpeak: () -> Unit,
    speechMessage: String?,
    onDismiss: () -> Unit,
    existingWord: com.kienhoang.dualsubreplay.data.SavedWord? = null,
    onSpeechSettings: (() -> Unit)? = null,
    onSpeakPart: (AnalyzedToken) -> Unit = {},
) {
    var meaning by remember(selection) { mutableStateOf(existingWord?.meaning.orEmpty()) }
    var loading by remember(selection) { mutableStateOf(true) }
    var error by remember(selection) { mutableStateOf<Int?>(null) }
    var saved by remember(selection) { mutableStateOf(existingWord != null) }
    var saving by remember(selection) { mutableStateOf(false) }
    var online by remember(selection) { mutableStateOf(existingWord?.online ?: true) }
    var characterPage by remember(selection) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canClip = validClipRange(selection.videoId, selection.segment?.startMs ?: -1, selection.segment?.endMs ?: -1)

    LaunchedEffect(selection) {
        if (autoPronounce) onSpeak()
        try {
            if (existingWord == null) meaning = onTranslateWord()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = R.string.practice_word_translation_unavailable
        } finally {
            loading = false
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.testTag("word_learning_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .heightIn(
                        max = 560.dp,
                    ).verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WordCardHeader(selection.token.text, onSpeak, onDismiss)
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val openCharacter = characterPage
                    if (openCharacter != null) {
                        CharacterPage(openCharacter, selection, onBack = { characterPage = null }, onAskedAi = onDismiss)
                        return@Column
                    }
                    MeaningPanel(
                        meaning = meaning,
                        language = languageDisplayName(selection.meaningLanguage, currentInterfaceLocale()),
                        loading = loading,
                        enabled = !loading && !saving,
                        onChange = {
                            meaning = it
                            saved = false
                        },
                    )
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    WordCardDetails(selection, speechMessage, onSpeechSettings)
                    CharacterSection(selection) { characterPage = it }
                    GrammarExplanations(selection, onAskedAi = onDismiss)
                    if (canClip) {
                        ClipChoice(stringResource(R.string.practice_online_example), online, {
                            online = it
                            saved = false
                        }, "online_clip_choice")
                        if (selection.translated) {
                            Text(
                                stringResource(R.string.practice_example_plays_original),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    SaveWordButton(saving = saving, saved = saved, enabled = !saving && !loading && !saved) {
                        saving = true
                        scope.launch {
                            try {
                                onSave(meaning, online && canClip)
                                saved = true
                                error = null
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                error = R.string.practice_save_failed
                            } finally {
                                saving = false
                            }
                        }
                    }
                    if (saved) Text(stringResource(R.string.practice_saved_to_vocabulary), modifier = Modifier.testTag("word_saved"))
                    StudyWordActions(selection, meaning)
                    if (selection.isPhrase) WordByWord(selection.parts, onSpeakPart)
                }
            }
        }
    }
}

/**
 * The meaning, the answer the learner opened the card for: a tinted panel with an accent bar and
 * large bold text right under the word (owner request 2026-10-24). The pencil turns it into a
 * text field to correct the meaning before saving.
 */
@Composable
private fun MeaningPanel(
    meaning: String,
    language: String,
    loading: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("word_meaning_panel"),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(colors.primary))
            Column(Modifier.weight(1f).padding(start = 14.dp, top = 10.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.practice_meaning_label, language),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
                when {
                    loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp, end = 14.dp))
                    editing ->
                        OutlinedTextField(
                            meaning,
                            onChange,
                            enabled = enabled,
                            textStyle = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp).testTag("word_meaning"),
                        )
                    else ->
                        Text(
                            meaning.ifBlank { "…" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp).testTag("word_meaning_text"),
                        )
                }
            }
            IconButton(onClick = { editing = !editing }, enabled = enabled, modifier = Modifier.testTag("edit_meaning")) {
                Icon(
                    if (editing) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = stringResource(if (editing) R.string.practice_meaning_done else R.string.practice_meaning_edit),
                    tint = colors.primary,
                )
            }
        }
    }
}

/**
 * Part of speech (or "Phrase") with an English word's CEFR level, reading, an English word's
 * dictionary form, and any speech problem with its settings shortcut.
 */
@Composable
private fun WordCardDetails(
    selection: LearningWordSelection,
    speechMessage: String?,
    onSpeechSettings: (() -> Unit)?,
) {
    val wordLevel = remember(selection) { selection.takeUnless { it.isPhrase }?.let { englishWordLevel(it) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (selection.isPhrase) R.string.practice_phrase else partOfSpeechLabel(selection.token.partOfSpeech)))
        wordLevel?.let { LevelBadge(it, stringResource(R.string.practice_word_level_description, it.code), tag = "word_level") }
    }
    selection.token.reading
        ?.takeIf { it.isNotBlank() }
        ?.let { Text(it) }
    selection.token.baseForm
        ?.takeIf { !selection.isPhrase && it.isNotBlank() }
        ?.let { Text(stringResource(R.string.practice_dictionary_form, it), modifier = Modifier.testTag("dictionary_form")) }
    speechMessage?.let { Text(it) }
    onSpeechSettings?.let { open ->
        TextButton(onClick = open, modifier = Modifier.testTag("speech_settings")) {
            Text(stringResource(R.string.practice_speech_settings))
        }
    }
}

/** The word or phrase with its speaker button, and the close ✕ kept apart in the corner. */
@Composable
private fun WordCardHeader(
    title: String,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
            IconButton(onClick = onSpeak, modifier = Modifier.testTag("pronounce_word")) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.practice_pronounce))
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Top).testTag("close_word_card")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.practice_close))
        }
    }
}

@Composable
private fun SaveWordButton(
    saving: Boolean,
    saved: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("save_word")) {
        Icon(if (saved) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                if (saving) {
                    R.string.practice_saving
                } else if (saved) {
                    R.string.practice_saved
                } else {
                    R.string.practice_save_to_vocabulary
                },
            ),
        )
    }
}

/** Each word of a phrase with its part of speech, reading, and its own pronunciation. */
@Composable
private fun WordByWord(
    parts: List<AnalyzedToken>,
    onSpeakPart: (AnalyzedToken) -> Unit,
) {
    Text(
        stringResource(R.string.practice_word_by_word),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
    parts.forEachIndexed { index, part ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(part.text, style = MaterialTheme.typography.bodyLarge)
                val partOfSpeech = stringResource(partOfSpeechLabel(part.partOfSpeech))
                val details = listOfNotNull(partOfSpeech, part.reading?.takeIf { it.isNotBlank() }).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = partOfSpeechColor(part.partOfSpeech, appThemeIsLight()))
            }
            IconButton(onClick = { onSpeakPart(part) }, modifier = Modifier.testTag("pronounce_part_$index")) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.practice_pronounce_item, part.text))
            }
        }
    }
}

@Composable
internal fun ClipChoice(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Checkbox(checked, onChange, modifier = Modifier.testTag(tag))
    }
}

/** The CEFR-J level of a tapped English word, or null for other languages and unlisted words. */
private fun englishWordLevel(selection: LearningWordSelection): CefrLevel? =
    selection.wordLanguage
        .lowercase(Locale.ROOT)
        .takeIf { it == "en" || it.startsWith("en-") || it.startsWith("en_") }
        ?.let { EnglishMorphology.wordLevel(selection.token) }

/** The shown name of a part of speech, in the interface language. */
@StringRes
internal fun partOfSpeechLabel(partOfSpeech: PartOfSpeech): Int =
    when (partOfSpeech) {
        PartOfSpeech.NOUN -> R.string.practice_pos_noun
        PartOfSpeech.VERB -> R.string.practice_pos_verb
        PartOfSpeech.ADJECTIVE -> R.string.practice_pos_adjective
        PartOfSpeech.ADVERB -> R.string.practice_pos_adverb
        PartOfSpeech.PRONOUN -> R.string.practice_pos_pronoun
        PartOfSpeech.CONJUNCTION -> R.string.practice_pos_conjunction
        PartOfSpeech.PREPOSITION -> R.string.practice_pos_preposition
        PartOfSpeech.PARTICLE -> R.string.practice_pos_particle
        PartOfSpeech.UNALIGNED -> R.string.practice_pos_unaligned
        PartOfSpeech.OTHER -> R.string.practice_pos_other
    }

/** Japanese words list their kanji, Chinese words their characters (Settings → Study tools → Character details). */
@Composable
private fun CharacterSection(
    selection: LearningWordSelection,
    onOpen: (String) -> Unit,
) {
    if (!rememberStudySettings().value.kanjiDetails) return
    when {
        isJapaneseWord(selection) -> KanjiSection(selection, onOpen)
        isChineseWord(selection) -> HanziSection(selection, onOpen)
    }
}

/** The kanji or Chinese character page opened from [CharacterSection], shown in place of the card. */
@Composable
private fun CharacterPage(
    literal: String,
    selection: LearningWordSelection,
    onBack: () -> Unit,
    onAskedAi: () -> Unit,
) {
    if (isChineseWord(selection)) {
        HanziPage(literal, selection, onBack, onAskedAi)
    } else {
        KanjiPage(literal, selection, onBack, onAskedAi)
    }
}
