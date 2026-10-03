package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.JapaneseDictionaryStatus
import com.kienhoang.dualsubreplay.data.JapaneseMorphology
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.learningSourceLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The selected words of one subtitle line: [owner] identifies the line, [words] the inclusive word range. */
internal data class PhraseSelection(
    val owner: Any,
    val words: IntRange,
)

/**
 * The selection after tapping word [tapped]: the first tap selects that word, a tap outside the
 * selection extends it to every word in between, and a tap inside the selection clears it.
 */
internal fun nextPhraseRange(
    current: IntRange?,
    tapped: Int,
): IntRange? =
    when {
        current == null -> tapped..tapped
        tapped in current -> null
        tapped < current.first -> tapped..current.last
        else -> current.first..tapped
    }

/** The words tap-to-learn recognizes in a subtitle line, in text order. */
internal fun subtitleWordTokens(
    text: String,
    languageCode: String?,
    alignedOriginalTokens: List<AnalyzedToken>? = null,
): List<AnalyzedToken> =
    when {
        text.isBlank() -> emptyList()
        alignedOriginalTokens != null ->
            LanguageAwareTokenizer.alignAndTokenizeTranslation(text, alignedOriginalTokens, languageCode)
        else -> LanguageAwareTokenizer.tokenize(text, languageCode)
    }

/**
 * Builds the tap for [words] of [tokens]. A phrase keeps the exact text between its first and last
 * word, so languages written without spaces stay intact, and lists each word as a part.
 */
internal fun phraseTap(
    text: String,
    tokens: List<AnalyzedToken>,
    words: IntRange,
    segment: SubtitleSegment?,
    translated: Boolean,
): WordTap {
    val parts = tokens.subList(words.first, words.last + 1)
    if (parts.size == 1) return WordTap(parts.single(), segment, translated)
    val start = parts.first().startIndex
    val end = parts.last().endIndex
    val readings = parts.mapNotNull { part -> part.reading?.takeIf { it.isNotBlank() } }
    val phrase =
        AnalyzedToken(
            text = text.substring(start, end),
            startIndex = start,
            endIndex = end,
            partOfSpeech = PartOfSpeech.OTHER,
            reading = readings.takeIf { it.size == parts.size }?.joinToString(" "),
        )
    return WordTap(phrase, segment, translated, parts)
}

/**
 * Where the action bar goes, in window pixels: centered over the selection and inside the window
 * horizontally, above the selection, or below it when there is no room above [minTop].
 */
internal fun phraseBarPosition(
    selection: IntRect,
    popupWidth: Int,
    popupHeight: Int,
    windowWidth: Int,
    minTop: Int,
    gap: Int,
    margin: Int,
): IntOffset {
    val centered = (selection.left + selection.right) / 2 - popupWidth / 2
    val maxX = windowWidth - popupWidth - margin
    val x = if (maxX < margin) (windowWidth - popupWidth) / 2 else centered.coerceIn(margin, maxX)
    val above = selection.top - gap - popupHeight
    val y = if (above >= minTop) above else selection.bottom + gap
    return IntOffset(x, y)
}

/**
 * Whether selecting [text] should speak it right away: a single word (not a phrase, not
 * punctuation) while the learner has "Pronounce tapped words" on.
 */
internal fun speaksOnSelect(
    text: String,
    singleWord: Boolean,
    autoPronounce: Boolean,
): Boolean = autoPronounce && singleWord && text.any(Char::isLetterOrDigit)

/** What the selection bar says while the Japanese dictionary is not ready, or null once it is. */
@StringRes
internal fun japaneseDictionaryNote(status: JapaneseDictionaryStatus): Int? =
    when (status) {
        JapaneseDictionaryStatus.DOWNLOADING -> R.string.practice_japanese_dictionary_downloading
        JapaneseDictionaryStatus.UNAVAILABLE -> R.string.practice_japanese_dictionary_unavailable
        else -> null
    }

/** Actions the app root supplies to every selectable subtitle line. */
internal class PhraseActions(
    val pause: () -> Unit = {},
    val pronounce: (text: String, translated: Boolean) -> Unit = { _, _ -> },
    /** Runs whenever the selected text changes, before any button is pressed. */
    val select: (text: String, translated: Boolean, singleWord: Boolean) -> Unit = { _, _, _ -> },
    val stopSpeech: () -> Unit = {},
    val speechMessage: () -> String? = { null },
    val translate: (suspend (text: String, translated: Boolean) -> String)? = null,
)

/** Holds the one selection shared by every subtitle line on screen. */
@Stable
internal class PhraseSelectionController {
    var selection by mutableStateOf<PhraseSelection?>(null)
        private set
    var actions: PhraseActions = PhraseActions()

    fun tap(
        owner: Any,
        word: Int,
    ) {
        val current = selection?.takeIf { it.owner === owner }?.words
        val next = nextPhraseRange(current, word)
        if (next == null) {
            clear(owner)
            return
        }
        if (current == null) actions.pause()
        selection = PhraseSelection(owner, next)
    }

    fun clear(owner: Any) {
        if (selection?.owner !== owner) return
        selection = null
        actions.stopSpeech()
    }

    /** Drops any selection, for example when the video starts playing again. */
    fun clearAll() {
        selection?.let { clear(it.owner) }
    }
}

internal val LocalPhraseSelection = staticCompositionLocalOf<PhraseSelectionController?> { null }

/** The app's shared controller, or a local one where no host provides it (isolated UI tests). */
@Composable
internal fun rememberPhraseSelection(): PhraseSelectionController {
    val fallback = remember { PhraseSelectionController() }
    return LocalPhraseSelection.current ?: fallback
}

/** Connects the shared selection to the page video and the app's speech engine. */
@Composable
internal fun BindPhraseActions(
    state: DualSubUiState,
    webController: YouTubeWebController,
    pronouncer: WordPronouncer,
    translate: suspend (text: String, translated: Boolean) -> String,
) {
    val controller = LocalPhraseSelection.current ?: return
    val source = learningSourceLanguage(state.resolvedSourceLanguage, state.sourcePreference)
    val target = state.targetLanguage
    // Playing the video again closes the bar; selecting pauses it, so this only fires on resume.
    LaunchedEffect(controller, state.playbackPaused) {
        if (!state.playbackPaused) controller.clearAll()
    }
    val autoPronounce = state.autoPronounce
    SideEffect {
        controller.actions =
            PhraseActions(
                pause = webController::pause,
                pronounce = { text, translated ->
                    webController.pause()
                    pronouncer.speak(text, if (translated) target else source)
                },
                select = { text, translated, singleWord ->
                    val language = if (translated) target else source
                    if (speaksOnSelect(text, singleWord, autoPronounce)) {
                        webController.pause()
                        pronouncer.speak(text, language)
                    } else {
                        pronouncer.forgetUnless(text, language)
                    }
                },
                stopSpeech = pronouncer::stop,
                speechMessage = { pronouncer.message },
                translate = translate,
            )
    }
}

/**
 * A subtitle line whose words can be selected. Tapping words selects a phrase and shows the
 * Copy / Translate / Pronounce bar above it; Translate hands the phrase to [onWordClick].
 * A tap outside every word runs [onBlankTap], or clears this line's selection.
 */
@Composable
internal fun SelectableSubtitleText(
    text: String,
    annotated: AnnotatedString,
    style: TextStyle,
    languageCode: String?,
    segment: SubtitleSegment?,
    translated: Boolean,
    highlightColor: Color,
    onWordClick: (WordTap) -> Unit,
    onBlankTap: () -> Unit,
    modifier: Modifier = Modifier,
    alignedOriginalTokens: List<AnalyzedToken>? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val controller = rememberPhraseSelection()
    val owner = remember { Any() }
    val revision = tokenizerRevision()
    val tokens =
        remember(text, languageCode, alignedOriginalTokens, revision) {
            subtitleWordTokens(text, languageCode, alignedOriginalTokens)
        }
    val words =
        controller.selection
            ?.takeIf { it.owner === owner }
            ?.words
            ?.takeIf { it.last < tokens.size }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val dictionaryStatus by JapaneseMorphology.status.collectAsState()
    LaunchedEffect(text, revision) { controller.clear(owner) }
    DisposableEffect(owner) { onDispose { controller.clear(owner) } }

    val start = words?.let { tokens[it.first].startIndex }
    val end = words?.let { tokens[it.last].endIndex }
    val selectedText = if (start != null && end != null) text.substring(start, end) else null
    val singleWord = words != null && words.first == words.last
    LaunchedEffect(selectedText, singleWord) {
        selectedText?.let { controller.actions.select(it, translated, singleWord) }
    }
    val shown =
        if (start != null && end != null) {
            AnnotatedString
                .Builder(annotated)
                .apply {
                    addStyle(SpanStyle(background = highlightColor.copy(alpha = 0.38f)), start, end)
                }.toAnnotatedString()
        } else {
            annotated
        }
    Box(modifier) {
        ClickableText(
            text = shown,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            onClick = { offset ->
                val index = tokens.indexOfFirst { offset >= it.startIndex && offset < it.endIndex }
                when {
                    index >= 0 -> controller.tap(owner, index)
                    words != null -> controller.clear(owner)
                    else -> onBlankTap()
                }
            },
        )
        val textLayout = layout
        if (words != null && start != null && end != null && textLayout != null && end <= textLayout.layoutInput.text.length) {
            BackHandler { controller.clear(owner) }
            PhraseActionBar(
                selection = textLayout.getPathForRange(start, end).getBounds(),
                phrase = text.substring(start, end),
                singleWord = words.first == words.last,
                speechMessage = controller.actions.speechMessage(),
                dictionaryNote =
                    japaneseDictionaryNote(dictionaryStatus).takeIf { LanguageAwareTokenizer.isJapanese(text, languageCode) },
                quickTranslate =
                    controller.actions.translate
                        ?.takeIf { words.first == words.last }
                        ?.let { translate -> { translate(text.substring(start, end), translated) } },
                onTranslate = {
                    val tap = phraseTap(text, tokens, words, segment, translated)
                    controller.clear(owner)
                    onWordClick(tap)
                },
                onPronounce = { controller.actions.pronounce(text.substring(start, end), translated) },
                onClose = { controller.clear(owner) },
            )
        }
    }
}

@Composable
private fun PhraseActionBar(
    selection: Rect,
    phrase: String,
    singleWord: Boolean,
    speechMessage: String?,
    @StringRes dictionaryNote: Int?,
    quickTranslate: (suspend () -> String)?,
    onTranslate: () -> Unit,
    onPronounce: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val minTop = WindowInsets.statusBars.getTop(density)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(phrase) { mutableStateOf(false) }
    val clipLabel = stringResource(R.string.practice_clipboard_label)
    val provider =
        remember(selection, minTop, density) {
            PhraseBarPositionProvider(
                selection = selection,
                minTop = minTop,
                gap = with(density) { 6.dp.roundToPx() },
                margin = with(density) { 8.dp.roundToPx() },
            )
        }
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp).testTag("phrase_action_bar"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, phrase))) }
                            copied = true
                        },
                        modifier = Modifier.testTag("phrase_copy"),
                    ) { Text(stringResource(if (copied) R.string.practice_copied else R.string.practice_copy)) }
                    TextButton(onClick = onTranslate, modifier = Modifier.testTag("phrase_translate")) {
                        Text(stringResource(R.string.practice_translate))
                    }
                    TextButton(onClick = onPronounce, modifier = Modifier.testTag("phrase_pronounce")) {
                        Text(stringResource(R.string.practice_pronounce))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("phrase_close")) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.practice_clear_selection),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                quickTranslate?.let { QuickTranslation(phrase, it) }
                val note =
                    speechMessage
                        ?: dictionaryNote?.let { stringResource(it) }
                        ?: if (singleWord) stringResource(R.string.practice_tap_another_word) else null
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    )
                }
            }
        }
    }
}

/** A single word's meaning, shown right in the bar so one tap is enough to understand it. */
@Composable
private fun QuickTranslation(
    word: String,
    translate: suspend () -> String,
) {
    var meaning by remember(word) { mutableStateOf<String?>(null) }
    val unavailable = stringResource(R.string.practice_translation_unavailable)
    LaunchedEffect(word) {
        meaning =
            try {
                translate().ifBlank { null } ?: unavailable
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                unavailable
            }
    }
    Text(
        meaning ?: stringResource(R.string.practice_translating),
        style = MaterialTheme.typography.titleMedium,
        color = if (meaning == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).testTag("phrase_quick_translation"),
    )
}

private class PhraseBarPositionProvider(
    private val selection: Rect,
    private val minTop: Int,
    private val gap: Int,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset =
        phraseBarPosition(
            selection =
                IntRect(
                    left = anchorBounds.left + selection.left.toInt(),
                    top = anchorBounds.top + selection.top.toInt(),
                    right = anchorBounds.left + selection.right.toInt(),
                    bottom = anchorBounds.top + selection.bottom.toInt(),
                ),
            popupWidth = popupContentSize.width,
            popupHeight = popupContentSize.height,
            windowWidth = windowSize.width,
            minTop = minTop,
            gap = gap,
            margin = margin,
        )
}
