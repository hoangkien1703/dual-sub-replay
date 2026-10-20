package com.kienhoang.dualsubreplay.ui

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.ANKIDROID_PERMISSION
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.AnkiProblem
import com.kienhoang.dualsubreplay.data.AnkiSendResult
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VideoComprehension
import com.kienhoang.dualsubreplay.data.knownWordKey
import com.kienhoang.dualsubreplay.data.knownWordLanguage
import com.kienhoang.dualsubreplay.data.lineWordKeys
import com.kienhoang.dualsubreplay.data.savedWordFrom
import com.kienhoang.dualsubreplay.data.videoComprehension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How strongly a new word's background shows the highlight color. */
private const val NEW_WORD_TINT_ALPHA = 0.24f

/**
 * The Study tools of the open video (Settings → Study tools): its known words and how much of it is
 * known, and AnkiDroid. Provided by `LearningPlayerRoot`; null in screens built without it.
 */
@Stable
internal class StudyTools(
    internal val repository: KnownWordsRepository,
    internal val anki: AnkiDroidNotes,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(StudySettings())
        internal set

    /** The known-word language of the open video's subtitles, or null before they load. */
    var language by mutableStateOf<String?>(null)
        internal set

    var known by mutableStateOf<Set<String>>(emptySet())
        internal set

    /** Saved words of [language], which count as being learned rather than new. */
    var learning by mutableStateOf<Set<String>>(emptySet())
        internal set

    var comprehension by mutableStateOf<VideoComprehension?>(null)
        internal set

    var ankiInstalled by mutableStateOf(false)
        internal set

    val ankiAvailable: Boolean get() = settings.ankiDroid && ankiInstalled

    /** True when words that are neither known nor saved get a tint in subtitles in [languageCode]. */
    fun marksNewWords(languageCode: String?): Boolean =
        settings.knownWords && settings.markNewWords && language != null && knownWordLanguage(languageCode) == language

    fun isNew(token: AnalyzedToken): Boolean = knownWordKey(token)?.let { it !in known && it !in learning } ?: false

    fun setKnown(
        language: String,
        key: String,
        known: Boolean,
    ) {
        scope.launch { repository.setKnown(language, listOf(key), known) }
    }

    fun markAllNewKnown() {
        val language = language ?: return
        val words = comprehension?.newWords.orEmpty()
        if (words.isEmpty()) return
        scope.launch { repository.setKnown(language, words, known = true) }
    }
}

internal val LocalStudyTools = staticCompositionLocalOf<StudyTools?> { null }

private val NO_WORDS = MutableStateFlow<Set<String>>(emptySet())

/** Keeps [StudyTools] current with the open video's subtitles, the settings and the saved words. */
@Composable
internal fun rememberStudyTools(
    state: DualSubUiState,
    savedWords: List<SavedWord>,
): StudyTools {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools = remember(context) { StudyTools(KnownWordsRepository.get(context), AnkiDroidNotes(context), scope) }
    val settings by rememberStudySettings()
    val sourceCode = state.resolvedSourceLanguage
    val language = knownWordLanguage(sourceCode).takeIf { settings.knownWords }
    val known by remember(language) { language?.let(tools.repository::words) ?: NO_WORDS }.collectAsState()
    val learning =
        remember(savedWords, language) {
            savedWords
                .filter { language != null && knownWordLanguage(it.wordLanguage) == language }
                .mapNotNull { knownWordKey(it.word) }
                .toSet()
        }
    val lines = remember(state.segments) { state.segments.map { it.originalText } }
    val revision = tokenizerRevision()
    val lineKeys by produceState<List<List<String>>?>(null, lines, sourceCode, revision, language, state.liveFallback) {
        value = null
        if (language == null || state.liveFallback || lines.isEmpty()) return@produceState
        val segments = state.segments
        value = withContext(Dispatchers.Default) { lineWordKeys(segments, sourceCode) }
    }
    val comprehension = remember(lineKeys, known, learning) { lineKeys?.let { videoComprehension(it, known, learning) } }
    LaunchedEffect(settings.ankiDroid) { tools.ankiInstalled = settings.ankiDroid && tools.anki.installed() }
    SideEffect {
        tools.settings = settings
        tools.language = language
        tools.known = known
        tools.learning = learning
        tools.comprehension = comprehension
    }
    return tools
}

/** The background of a word that is neither known nor saved, from the highlight color. */
internal fun newWordTint(highlight: Color) = highlight.copy(alpha = NEW_WORD_TINT_ALPHA)

/** "Known 78%" in the subtitle panel's header; opens how much of the video is known. */
@Composable
internal fun ComprehensionChip() {
    val tools = LocalStudyTools.current ?: return
    val comprehension = tools.comprehension?.takeIf { it.words > 0 } ?: return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("comprehension_chip")) {
        Text(stringResource(R.string.study_comprehension_chip, comprehension.percent), style = MaterialTheme.typography.labelMedium)
    }
    if (open) ComprehensionDialog(tools, comprehension, onDismiss = { open = false })
}

@Composable
private fun ComprehensionDialog(
    tools: StudyTools,
    comprehension: VideoComprehension,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val newCount = comprehension.newWords.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.study_comprehension_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.study_comprehension_known, comprehension.percent))
                Text(pluralStringResource(R.plurals.study_new_words, newCount, newCount))
                if (confirming) Text(pluralStringResource(R.plurals.study_mark_all_known_message, newCount, newCount))
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(
                    onClick = {
                        tools.markAllNewKnown()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("confirm_mark_all_known"),
                ) { Text(stringResource(R.string.study_mark_all_known_confirm)) }
            } else if (newCount > 0) {
                TextButton(onClick = { confirming = true }, modifier = Modifier.testTag("mark_all_known")) {
                    Text(stringResource(R.string.study_mark_all_known))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (confirming) R.string.settings_cancel else R.string.study_close))
            }
        },
    )
}

/** The word card's "I know this word" and "Send to AnkiDroid", when those tools are on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudyWordActions(
    selection: LearningWordSelection,
    meaning: String,
) {
    val tools = LocalStudyTools.current ?: return
    val language = knownWordLanguage(selection.wordLanguage)?.takeIf { it == tools.language }
    val key = selection.token.takeIf { !selection.isPhrase && !selection.translated }?.let(::knownWordKey)
    val showKnown = tools.settings.knownWords && language != null && key != null
    if (!showKnown && !tools.ankiAvailable) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showKnown) {
            val known = key in tools.known
            FilterChip(
                selected = known,
                onClick = { tools.setKnown(language, key, !known) },
                label = { Text(stringResource(R.string.study_known_word_toggle)) },
                leadingIcon =
                    if (known) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                modifier = Modifier.testTag("known_word_toggle"),
            )
        }
        if (tools.ankiAvailable) AnkiSendChip(tools, selection, meaning)
    }
}

@Composable
private fun AnkiSendChip(
    tools: StudyTools,
    selection: LearningWordSelection,
    meaning: String,
) {
    val word = savedWordFrom(selection, meaning, online = false)
    val scope = rememberCoroutineScope()
    var sending by remember(selection) { mutableStateOf(false) }
    var sent by remember(selection) { mutableStateOf(tools.anki.wasSent(word.id)) }
    var problem by remember(selection) { mutableStateOf<AnkiProblem?>(null) }
    val send = {
        sending = true
        scope.launch {
            val result = tools.anki.send(listOf(word))
            sending = false
            problem = result.problem
            sent = result.problem == null
        }
        Unit
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) send() else problem = AnkiProblem.NO_PERMISSION
        }
    Column {
        FilterChip(
            selected = sent,
            enabled = !sending && !sent && meaning.isNotBlank(),
            onClick = { if (tools.anki.permitted()) send() else permission.launch(ANKIDROID_PERMISSION) },
            label = {
                Text(
                    stringResource(
                        when {
                            sent -> R.string.study_sent_to_anki
                            sending -> R.string.study_sending_to_anki
                            else -> R.string.study_send_to_anki
                        },
                    ),
                )
            },
            leadingIcon = { Icon(Icons.Default.Style, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.testTag("send_to_anki"),
        )
        problem?.let {
            Text(stringResource(ankiProblemText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@StringRes
internal fun ankiProblemText(problem: AnkiProblem): Int =
    when (problem) {
        AnkiProblem.NOT_INSTALLED -> R.string.study_anki_not_installed
        AnkiProblem.NO_PERMISSION -> R.string.study_anki_permission_denied
        AnkiProblem.FAILED -> R.string.study_anki_failed
    }

/** The export dialog's "Send all to AnkiDroid", when AnkiDroid is installed and the setting is on. */
@Composable
internal fun AnkiSendAllButton(
    words: List<SavedWord>,
    onResult: (String) -> Unit,
) {
    val tools = LocalStudyTools.current ?: return
    if (!tools.ankiAvailable || words.isEmpty()) return
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val send = {
        scope.launch { onResult(ankiResultText(resources, tools.anki.send(words))) }
        Unit
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) send() else onResult(resources.getString(R.string.study_anki_permission_denied))
        }
    TextButton(
        onClick = { if (tools.anki.permitted()) send() else permission.launch(ANKIDROID_PERMISSION) },
        modifier = Modifier.testTag("send_all_to_anki"),
    ) { Text(stringResource(R.string.study_anki_send_all)) }
}

private fun ankiResultText(
    resources: Resources,
    result: AnkiSendResult,
): String {
    val sent = resources.getQuantityString(R.plurals.study_anki_sent_count, result.sent, result.sent)
    return when {
        result.problem != null ->
            listOfNotNull(sent.takeIf { result.sent > 0 }, resources.getString(ankiProblemText(result.problem))).joinToString(" ")
        result.sent == 0 -> resources.getString(R.string.study_anki_all_sent_before)
        else -> sent
    }
}
