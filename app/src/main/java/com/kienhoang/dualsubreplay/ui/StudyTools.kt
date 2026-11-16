package com.kienhoang.dualsubreplay.ui

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.Alignment
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
import com.kienhoang.dualsubreplay.data.AnkiCardMedia
import com.kienhoang.dualsubreplay.data.AnkiConnectNotes
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.AnkiProblem
import com.kienhoang.dualsubreplay.data.AnkiSendResult
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.LineEncounters
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.VideoComprehension
import com.kienhoang.dualsubreplay.data.knownWordKey
import com.kienhoang.dualsubreplay.data.knownWordLanguage
import com.kienhoang.dualsubreplay.data.lineWordKeys
import com.kienhoang.dualsubreplay.data.videoComprehension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long the Known % score takes to reach a new number. */
private const val SCORE_ANIMATION_MS = 600

/** How strongly a new word's background shows the highlight color. */
private const val NEW_WORD_TINT_ALPHA = 0.24f

/**
 * The Study tools of the open video (Settings → Study tools): its known words and how much of it is
 * known, and the flashcard apps Send to sends to. Provided by `LearningPlayerRoot`; null in screens
 * built without it.
 */
@Stable
internal class StudyTools(
    internal val repository: KnownWordsRepository,
    internal val anki: AnkiDroidNotes,
    internal val ankiConnect: AnkiConnectNotes,
    private val scope: CoroutineScope,
    /** The cards' sounds (Settings → Study tools → Add audio to cards); null where nothing records them. */
    private val cardMedia: AnkiCardMedia? = null,
) {
    var settings by mutableStateOf(StudySettings())
        internal set

    /** The known-word language of the open video's subtitles while Known words is on, or null. */
    var language by mutableStateOf<String?>(null)
        internal set

    /** The known-word language of the open video's subtitles, also while Known words is off. */
    var wordsLanguage by mutableStateOf<String?>(null)
        internal set

    var known by mutableStateOf<Set<String>>(emptySet())
        internal set

    /** Automatic known words: how often each word of [language] not known yet was seen. */
    var seen by mutableStateOf<Map<String, Int>>(emptyMap())
        internal set

    /** Saved words of [language], which count as being learned rather than new. */
    var learning by mutableStateOf<Set<String>>(emptySet())
        internal set

    var comprehension by mutableStateOf<VideoComprehension?>(null)
        internal set

    var ankiInstalled by mutableStateOf(false)
        internal set

    /**
     * Send to shows while its setting is on and AnkiDroid is installed, or AnkiConnect was picked
     * (in Settings, for phones without AnkiDroid).
     */
    val sendToAvailable: Boolean get() = settings.ankiDroid && (ankiInstalled || settings.sendTo == FlashcardApp.ANKICONNECT)

    fun wasSent(
        app: FlashcardApp,
        id: String,
    ): Boolean =
        when (app) {
            FlashcardApp.ANKIDROID -> anki.wasSent(id)
            FlashcardApp.ANKICONNECT -> ankiConnect.wasSent(id)
        }

    suspend fun send(
        app: FlashcardApp,
        words: List<SavedWord>,
        again: Boolean = false,
    ): AnkiSendResult =
        when (app) {
            FlashcardApp.ANKIDROID -> anki.send(words, cardMedia, settings.cardOptions, again)
            FlashcardApp.ANKICONNECT -> ankiConnect.send(words, settings.ankiConnectDeck, cardMedia, settings.cardOptions, again)
        }

    /** AnkiDroid asks for its permission before the first card; AnkiConnect asks for none. */
    fun needsPermission(app: FlashcardApp): Boolean = app == FlashcardApp.ANKIDROID && anki.installed() && !anki.permitted()

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

private val NO_COUNTS = MutableStateFlow<Map<String, Int>>(emptyMap())

/** Keeps [StudyTools] current with the open video's subtitles, the settings and the saved words. */
@Composable
internal fun rememberStudyTools(
    state: DualSubUiState,
    savedWords: List<SavedWord>,
): StudyTools {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools =
        remember(context) {
            StudyTools(
                KnownWordsRepository.get(context),
                AnkiDroidNotes(context),
                AnkiConnectNotes.create(context),
                scope,
                CardMediaSource(context),
            )
        }
    val settings by rememberStudySettings()
    val sourceCode = state.resolvedSourceLanguage
    val wordsLanguage = knownWordLanguage(sourceCode)
    val language = wordsLanguage.takeIf { settings.knownWords }
    // Kept even while Known words is off, for the word card's "I know this word".
    val known by remember(wordsLanguage) { wordsLanguage?.let(tools.repository::words) ?: NO_WORDS }.collectAsState()
    val seen by remember(language) { language?.let(tools.repository::seen) ?: NO_COUNTS }.collectAsState()
    val learning =
        remember(savedWords, language) {
            savedWords
                .filter { language != null && knownWordLanguage(it.wordLanguage) == language }
                .mapNotNull { knownWordKey(it.word) }
                .toSet()
        }
    val lines = remember(state.segments) { state.segments.map { it.originalText } }
    val revision = tokenizerRevision()
    val videoId = state.activeVideoId
    // The last result stays while a new one is worked out, so the score does not blink when lines change.
    val counted by produceState<Pair<String?, List<List<String>>>?>(null, lines, sourceCode, revision, language, state.liveFallback) {
        if (language == null || state.liveFallback || lines.isEmpty()) {
            value = null
            return@produceState
        }
        val segments = state.segments
        value = videoId to withContext(Dispatchers.Default) { lineWordKeys(segments, sourceCode) }
    }
    val lineKeys = counted?.takeIf { it.first == videoId }?.second
    val comprehension = remember(lineKeys, known, learning) { lineKeys?.let { videoComprehension(it, known, learning) } }
    LaunchedEffect(settings.ankiDroid) { tools.ankiInstalled = settings.ankiDroid && tools.anki.installed() }
    CountPlayedLines(tools, state, language, lineKeys, learning, settings)
    SideEffect {
        tools.settings = settings
        tools.language = language
        tools.wordsLanguage = wordsLanguage
        tools.known = known
        tools.seen = seen
        tools.learning = learning
        tools.comprehension = comprehension
    }
    return tools
}

/**
 * Automatic known words: each line that plays counts its words once per video (Kien's choice,
 * 2026-10-20). A paused video counts nothing; the counts are written when it pauses or the screen goes.
 */
@Composable
private fun CountPlayedLines(
    tools: StudyTools,
    state: DualSubUiState,
    language: String?,
    lineKeys: List<List<String>>?,
    learning: Set<String>,
    settings: StudySettings,
) {
    val lines = remember { LineEncounters() }
    val counting = settings.knownWordsMode == KnownWordsMode.AUTO && language != null && lineKeys != null
    val videoId = state.activeVideoId
    val index = state.currentIndex
    val playing = !state.playbackPaused
    LaunchedEffect(counting, videoId, index, playing) {
        if (!counting || !playing || videoId == null || language == null) return@LaunchedEffect
        val keys = lineKeys?.getOrNull(index) ?: return@LaunchedEffect
        if (lines.take(videoId, index)) tools.repository.encounter(language, keys, learning, settings.knownWordsAfter)
    }
    LaunchedEffect(playing) { if (!playing) tools.repository.flush() }
    DisposableEffect(tools) { onDispose { tools.repository.flushSoon() } }
}

/** The background of a word that is neither known nor saved, from the highlight color. */
internal fun newWordTint(highlight: Color) = highlight.copy(alpha = NEW_WORD_TINT_ALPHA)

/** "Known 78%" in the subtitle panel's header; opens how much of the video is known. */
@Composable
internal fun ComprehensionChip() {
    val tools = LocalStudyTools.current ?: return
    val comprehension = tools.comprehension?.takeIf { it.words > 0 } ?: return
    var open by remember { mutableStateOf(false) }
    // A new score counts up or down to its number instead of jumping.
    val percent by animateIntAsState(comprehension.percent, tween(SCORE_ANIMATION_MS), label = "known percent")
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("comprehension_chip")) {
        Text(stringResource(R.string.study_comprehension_chip, percent), style = MaterialTheme.typography.labelMedium)
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
        title = { DialogTitle(stringResource(R.string.study_comprehension_title), onClose = onDismiss) },
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
        // While asking to mark every new word known, Cancel steps back; otherwise the X closes.
        dismissButton = {
            if (confirming) TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** The Send all to button's list: every flashcard app, with a check by the one in use. */
@Composable
private fun SendToMenu(
    expanded: Boolean,
    current: FlashcardApp,
    onDismiss: () -> Unit,
    onPick: (FlashcardApp) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FlashcardApp.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(option.nameRes)) },
                onClick = { onPick(option) },
                leadingIcon = {
                    if (option == current) Icon(Icons.Default.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                },
                modifier = Modifier.testTag("send_to_option_${option.storageValue}"),
            )
        }
    }
}

/**
 * On many phones AnkiDroid accepts cards only while it runs, and AnkiConnect Android's server runs
 * only after it was opened: opening the app once lets the next try work.
 */
@Composable
internal fun SendProblemAction(
    tools: StudyTools,
    app: FlashcardApp,
    problem: AnkiProblem?,
) {
    when {
        app == FlashcardApp.ANKIDROID && problem == AnkiProblem.FAILED ->
            TextButton(onClick = { tools.anki.open() }, modifier = Modifier.testTag("open_ankidroid")) {
                Text(stringResource(R.string.study_anki_open))
            }
        app == FlashcardApp.ANKICONNECT && problem == AnkiProblem.NOT_RUNNING && tools.ankiConnect.canOpen() ->
            TextButton(onClick = { tools.ankiConnect.open() }, modifier = Modifier.testTag("open_ankiconnect")) {
                Text(stringResource(R.string.study_ankiconnect_open))
            }
    }
}

/** What stopped a send to [app], with AnkiConnect's own words when it gave some. */
internal fun sendProblemText(
    resources: Resources,
    app: FlashcardApp,
    result: AnkiSendResult,
): String =
    when (result.problem) {
        null -> ""
        AnkiProblem.NOT_INSTALLED -> resources.getString(R.string.study_anki_not_installed)
        AnkiProblem.NO_PERMISSION -> resources.getString(R.string.study_anki_permission_denied)
        AnkiProblem.NOT_RUNNING -> resources.getString(R.string.study_ankiconnect_not_running)
        AnkiProblem.FAILED ->
            when {
                app == FlashcardApp.ANKIDROID -> resources.getString(R.string.study_anki_failed)
                result.detail.isNullOrBlank() -> resources.getString(R.string.study_ankiconnect_failed)
                else -> resources.getString(R.string.study_ankiconnect_failed_detail, result.detail)
            }
    }

/**
 * The export dialog's "Send all to …" with a ▾ list of the apps, when Send to shows.
 * Picking another app remembers it and sends every word there.
 */
@Composable
internal fun AnkiSendAllButton(
    words: List<SavedWord>,
    onResult: (String) -> Unit,
) {
    val tools = LocalStudyTools.current ?: return
    if (!tools.sendToAvailable || words.isEmpty()) return
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val app = tools.settings.sendTo
    val send: (FlashcardApp) -> Unit = { to ->
        scope.launch { onResult(sendResultText(resources, to, tools.send(to, words))) }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) send(FlashcardApp.ANKIDROID) else onResult(resources.getString(R.string.study_anki_permission_denied))
        }
    val start: (FlashcardApp) -> Unit = { to -> if (tools.needsPermission(to)) permission.launch(ANKIDROID_PERMISSION) else send(to) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { start(app) }, modifier = Modifier.testTag("send_all_to_anki")) {
            Text(stringResource(R.string.study_anki_send_all, stringResource(app.nameRes)))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("send_all_menu")) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.study_send_to_choose))
            }
            SendToMenu(
                expanded = menu,
                current = app,
                onDismiss = { menu = false },
                onPick = { picked ->
                    menu = false
                    preferences.edit().putString(SEND_TO_APP_PREFERENCE, picked.storageValue).apply()
                    start(picked)
                },
            )
        }
    }
}

internal fun sendResultText(
    resources: Resources,
    app: FlashcardApp,
    result: AnkiSendResult,
): String {
    val name = resources.getString(app.nameRes)
    val sent = resources.getQuantityString(R.plurals.study_anki_sent_count, result.sent, result.sent, name)
    val waits =
        resources.getQuantityString(R.plurals.study_anki_sound_waits_count, result.waiting, result.waiting).takeIf { result.waiting > 0 }
    return when {
        result.problem != null ->
            listOfNotNull(sent.takeIf { result.sent > 0 }, waits, sendProblemText(resources, app, result)).joinToString(" ")
        result.sent == 0 -> resources.getString(R.string.study_anki_all_sent_before, name)
        else -> listOfNotNull(sent, waits).joinToString(" ")
    }
}

/**
 * The line was paused part-way (tapping a word pauses it): its sound is recorded once it has played,
 * which [replaying] does right away.
 */
@Composable
internal fun CardSoundWaitsNote(replaying: Boolean) {
    Text(
        stringResource(if (replaying) R.string.study_card_sound_replaying else R.string.study_card_sound_waits),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("card_sound_waits"),
    )
}

/** Asks before a second card of the same word goes to [appName]; Anki keeps both. */
@Composable
internal fun SendAgainDialog(
    appName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.study_send_again_title)) },
        text = { Text(stringResource(R.string.study_send_again_text, appName)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                modifier = Modifier.testTag("send_again_confirm"),
            ) { Text(stringResource(R.string.study_send_again)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
