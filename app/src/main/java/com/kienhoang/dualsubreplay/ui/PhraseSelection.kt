package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.roundToInt

/** The selected words of one subtitle line: [owner] identifies the line, [words] the inclusive word range. */
internal data class PhraseSelection(
    val owner: Any,
    val words: IntRange,
)

/**
 * The selection after tapping word [tapped], as Android selects text: a tap selects that word, a
 * tap on another word moves the selection there, and a tap inside the selection clears it.
 */
internal fun nextPhraseRange(
    current: IntRange?,
    tapped: Int,
): IntRange? = if (current != null && tapped in current) null else tapped..tapped

/** Every word from [fixed] to [moving], whichever comes first: a drag or a handle moves [moving]. */
internal fun spanBetween(
    fixed: Int,
    moving: Int,
): IntRange = minOf(fixed, moving)..maxOf(fixed, moving)

/** The word holding character [index], or -1 when it falls between words. */
internal fun wordAt(
    tokens: List<AnalyzedToken>,
    index: Int,
): Int = tokens.indexOfFirst { index >= it.startIndex && index < it.endIndex }

/** The word holding character [index], or else the closest one (the earlier on a tie); -1 without words. */
internal fun nearestWord(
    tokens: List<AnalyzedToken>,
    index: Int,
): Int =
    tokens.indices.minByOrNull { i ->
        val token = tokens[i]
        when {
            index < token.startIndex -> token.startIndex - index
            index >= token.endIndex -> index - token.endIndex + 1
            else -> 0
        }
    } ?: -1

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
 * horizontally, above the selection, or [belowGap] below it (clear of the handles) when there is
 * no room above [minTop].
 */
internal fun phraseBarPosition(
    selection: IntRect,
    popupWidth: Int,
    popupHeight: Int,
    windowWidth: Int,
    minTop: Int,
    gap: Int,
    margin: Int,
    belowGap: Int = gap,
): IntOffset {
    val centered = (selection.left + selection.right) / 2 - popupWidth / 2
    val maxX = windowWidth - popupWidth - margin
    val x = if (maxX < margin) (windowWidth - popupWidth) / 2 else centered.coerceIn(margin, maxX)
    val above = selection.top - gap - popupHeight
    val y = if (above >= minTop) above else selection.bottom + belowGap
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
    /** Speaks the selection; [text] is what the speech engine reads, kana in place of kanji where known. */
    val pronounce: (text: String, translated: Boolean) -> Unit = { _, _ -> },
    /** Runs whenever the selected text changes, before any button is pressed, with the text to speak. */
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

    /** True from a press on a subtitle line until the next press anywhere, so that press keeps the selection. */
    internal var pressedSubtitle = false

    fun tap(
        owner: Any,
        word: Int,
    ) {
        val current = selection?.takeIf { it.owner === owner }?.words
        val next = nextPhraseRange(current, word)
        if (next == null) clear(owner) else select(owner, next)
    }

    /** Selects [words] of [owner]'s line, pausing the video for a new selection; true when it changed. */
    fun select(
        owner: Any,
        words: IntRange,
    ): Boolean {
        val current = selection
        if (current?.owner === owner && current.words == words) return false
        if (current?.owner !== owner) actions.pause()
        selection = PhraseSelection(owner, words)
        return true
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

/**
 * Clears the selection after a tap anywhere inside this element except on a subtitle line, as
 * tapping elsewhere does on Android. A scroll or drag keeps it. The handles and the action bar are
 * popups of their own, so taps on them never reach here.
 */
internal fun Modifier.clearPhraseSelectionOnTapElsewhere(controller: PhraseSelectionController): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // A subtitle line sets this again while it handles the same press, after this pass.
            controller.pressedSubtitle = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                if (change.changedToUpIgnoreConsumed()) {
                    if (!controller.pressedSubtitle) controller.clearAll()
                    break
                }
            }
        }
    }

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
 * A subtitle line whose words can be selected the way Android selects text: tapping a word selects
 * it, holding a word and dragging selects every word passed, and the handles at both ends move
 * either end word by word. The Copy / Translate / Pronounce bar sits above the selection, and
 * Translate hands the phrase to [onWordClick]. A tap outside every word runs [onBlankTap], or
 * clears this line's selection.
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
    centered: Boolean = false,
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
    val drag = remember { SelectionDrag() }
    drag.haptics = LocalHapticFeedback.current
    val dictionaryStatus by JapaneseMorphology.status.collectAsState()
    LaunchedEffect(text, revision) { controller.clear(owner) }
    DisposableEffect(owner) { onDispose { controller.clear(owner) } }

    val start = words?.let { tokens[it.first].startIndex }
    val end = words?.let { tokens[it.last].endIndex }
    val selectedText = if (start != null && end != null) text.substring(start, end) else null
    val spoken = if (selectedText != null && start != null && words != null) spokenText(selectedText, start, tokens.slice(words)) else null
    val singleWord = words != null && words.first == words.last
    // While a finger drags, the selection changes word by word: speak and translate where it stops.
    LaunchedEffect(spoken, singleWord, drag.active) {
        if (!drag.active) spoken?.let { controller.actions.select(it, translated, singleWord) }
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

    // Moves the dragged end of the selection to the word under [point], in this line's pixels.
    fun dragTo(point: Offset) = drag.moveTo(point, layout, tokens) { range -> controller.select(owner, range) }

    val currentOnBlankTap by rememberUpdatedState(onBlankTap)
    val side = lineSideUnlessCentered(text, centered)
    Box(modifier.then(side)) {
        BasicText(
            text = shown,
            modifier = side.selectableWords(controller, owner, tokens, { layout }, drag, ::dragTo) { currentOnBlankTap() },
            style = style.inLineDirection().centeredIf(centered),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
        )
        val textLayout = layout
        if (words != null && start != null && end != null && textLayout != null && end <= textLayout.layoutInput.text.length) {
            BackHandler { controller.clear(owner) }
            SelectionHandles(textLayout, start, end, words, drag, ::dragTo)
            // Hidden while a finger drags, as Android hides its text toolbar, so it never covers the words.
            if (!drag.active) {
                PhraseActionBar(
                    selection = textLayout.getPathForRange(start, end).getBounds(),
                    phrase = text.substring(start, end),
                    singleWord = words.first == words.last,
                    speechMessage = controller.actions.speechMessage(),
                    dictionaryNote =
                        japaneseDictionaryNote(dictionaryStatus).takeIf { LanguageAwareTokenizer.isJapanese(text, languageCode) },
                    quickTranslate =
                        controller.actions.translate
                            ?.let { translate -> { translate(text.substring(start, end), translated) } },
                    onTranslate = {
                        val tap = phraseTap(text, tokens, words, segment, translated)
                        controller.clear(owner)
                        onWordClick(tap)
                    },
                    onPronounce = { spoken?.let { controller.actions.pronounce(it, translated) } },
                    onClose = { controller.clear(owner) },
                )
            }
        }
    }
}

/**
 * The line's touch handling: a tap selects, moves or clears the selection (or runs [onBlankTap]
 * between words), and holding a word starts a selection that grows with the finger.
 */
private fun Modifier.selectableWords(
    controller: PhraseSelectionController,
    owner: Any,
    tokens: List<AnalyzedToken>,
    layout: () -> TextLayoutResult?,
    drag: SelectionDrag,
    dragTo: (Offset) -> Unit,
    onBlankTap: () -> Unit,
): Modifier =
    pointerInput(owner, tokens) {
        detectTapGestures(
            onPress = { controller.pressedSubtitle = true },
            onTap = { position ->
                val index = layout()?.let { wordAt(tokens, it.characterAt(position)) } ?: -1
                when {
                    index >= 0 -> controller.tap(owner, index)
                    controller.selection?.owner === owner -> controller.clear(owner)
                    else -> onBlankTap()
                }
            },
        )
    }
        // Inside the tap detector, so a long press's lift reaches it first and is never a tap.
        .pointerInput(owner, tokens) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val word = layout()?.wordNear(tokens, position) ?: -1
                    if (word >= 0) {
                        drag.start(fixedWord = word, from = null)
                        dragTo(position)
                    }
                },
                onDrag = { change, _ -> if (drag.active) dragTo(change.position) },
                onDragEnd = drag::stop,
                onDragCancel = drag::stop,
            )
        }.magnifier(sourceCenter = { drag.magnifier }, zoom = 1.25f)

/** The two handles at the ends of the selection [words], from character [start] to [end]. */
@Composable
private fun SelectionHandles(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    words: IntRange,
    drag: SelectionDrag,
    dragTo: (Offset) -> Unit,
) {
    val startHandle = layout.startHandleAnchor(start)
    val endHandle = layout.endHandleAnchor(end)
    SelectionHandle(
        anchor = startHandle,
        tag = "phrase_handle_start",
        onDragStart = { drag.start(fixedWord = words.last, from = startHandle) },
        onDrag = { moved -> dragTo(drag.target(moved)) },
        onDragEnd = drag::stop,
    )
    SelectionHandle(
        anchor = endHandle,
        tag = "phrase_handle_end",
        onDragStart = { drag.start(fixedWord = words.first, from = endHandle) },
        onDrag = { moved -> dragTo(drag.target(moved)) },
        onDragEnd = drag::stop,
    )
}

/** What a finger dragging on one subtitle line holds: the word that stays put and where the drag began. */
@Stable
private class SelectionDrag {
    var active by mutableStateOf(false)
    var magnifier by mutableStateOf(Offset.Unspecified)
    var fixedWord = 0
    var haptics: HapticFeedback? = null
    private var origin = Offset.Zero

    /** Starts a drag that keeps [fixedWord] selected: from a handle, or from a held word when [from] is null. */
    fun start(
        fixedWord: Int,
        from: HandleAnchor?,
    ) {
        this.fixedWord = fixedWord
        // A handle hangs under its line, so aim at the middle of that line.
        if (from != null) origin = from.position - Offset(0f, from.lineHeight / 2)
        active = true
    }

    /**
     * Selects from the fixed word to the word nearest [point] on [layout] through [select], with a
     * tick each time the selection changes, and shows the magnifier there.
     */
    fun moveTo(
        point: Offset,
        layout: TextLayoutResult?,
        tokens: List<AnalyzedToken>,
        select: (IntRange) -> Boolean,
    ) {
        layout ?: return
        val word = layout.wordNear(tokens, point)
        if (word < 0) return
        if (select(spanBetween(fixedWord, word))) haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        magnifier = layout.magnifierSource(point)
    }

    /** The point a handle points at after the finger [moved] from where it pressed. */
    fun target(moved: Offset): Offset = origin + moved

    fun stop() {
        active = false
        magnifier = Offset.Unspecified
    }
}

/** Where a selection handle points, in its line's pixels, and which way its drop hangs. */
private data class HandleAnchor(
    val position: Offset,
    val lineHeight: Float,
    val hangsLeft: Boolean,
)

/** The start handle hangs outward from the selection's first character, left of it in left-to-right text. */
private fun TextLayoutResult.startHandleAnchor(start: Int): HandleAnchor {
    val box = getBoundingBox(start)
    val leftToRight = getBidiRunDirection(start) == ResolvedTextDirection.Ltr
    val line = getLineForOffset(start)
    return HandleAnchor(
        position = Offset(if (leftToRight) box.left else box.right, getLineBottom(line)),
        lineHeight = getLineBottom(line) - getLineTop(line),
        hangsLeft = leftToRight,
    )
}

/** The end handle hangs outward from the selection's last character, right of it in left-to-right text. */
private fun TextLayoutResult.endHandleAnchor(end: Int): HandleAnchor {
    val last = end - 1
    val box = getBoundingBox(last)
    val leftToRight = getBidiRunDirection(last) == ResolvedTextDirection.Ltr
    val line = getLineForOffset(last)
    return HandleAnchor(
        position = Offset(if (leftToRight) box.right else box.left, getLineBottom(line)),
        lineHeight = getLineBottom(line) - getLineTop(line),
        hangsLeft = !leftToRight,
    )
}

/** The character drawn at [position], or -1 between and around characters. */
private fun TextLayoutResult.characterAt(position: Offset): Int {
    val offset = getOffsetForPosition(position)
    // The nearest caret position can sit after the character the finger is on.
    return listOf(offset - 1, offset).firstOrNull { index ->
        index in 0 until layoutInput.text.length &&
            getBoundingBox(index).let { box -> position.x in box.left..box.right && position.y in box.top..box.bottom }
    } ?: -1
}

/** The word at [position], or the closest one, so a drag between or past words still picks a word. */
private fun TextLayoutResult.wordNear(
    tokens: List<AnalyzedToken>,
    position: Offset,
): Int = nearestWord(tokens, characterAt(position).takeIf { it >= 0 } ?: getOffsetForPosition(position))

/** What the magnifier shows for a finger at [position]: the middle of that line, held within the line's ends. */
private fun TextLayoutResult.magnifierSource(position: Offset): Offset {
    val line = getLineForVerticalPosition(position.y)
    return Offset(
        position.x.coerceIn(getLineLeft(line), getLineRight(line)),
        (getLineTop(line) + getLineBottom(line)) / 2,
    )
}

private val HandleDropSize = 22.dp
private val HandleTouchSize = 40.dp

/**
 * One teardrop handle in the accent color, hanging under [anchor] with its square corner on it.
 * It is a popup, so the card around the line never clips it. [onDrag] gets the finger's movement
 * since it pressed, measured on the screen, so the handle jumping to the next word under the
 * finger never shifts the drag.
 */
@Composable
private fun SelectionHandle(
    anchor: HandleAnchor,
    tag: String,
    onDragStart: () -> Unit,
    onDrag: (moved: Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val provider = remember(anchor) { HandlePositionProvider(anchor) }
    val color = MaterialTheme.colorScheme.primary
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
        Canvas(
            Modifier
                .size(HandleTouchSize)
                .testTag(tag)
                .onGloballyPositioned { coordinates[0] = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()

                        fun onScreen(position: Offset) = coordinates[0]?.localToScreen(position) ?: position
                        val from = onScreen(down.position)
                        currentOnDragStart()
                        drag(down.id) { change ->
                            change.consume()
                            currentOnDrag(onScreen(change.position) - from)
                        }
                        currentOnDragEnd()
                    }
                },
        ) {
            val drop = HandleDropSize.toPx()
            val radius = drop / 2
            val left = if (anchor.hangsLeft) size.width - drop else 0f
            drawCircle(color, radius, center = Offset(left + radius, radius))
            // The square corner touches the selection's end.
            drawRect(color, topLeft = Offset(if (anchor.hangsLeft) left + radius else left, 0f), size = Size(radius, radius))
        }
    }
}

/** Puts a handle's square corner on its anchor: the touch area spreads down and outward from it. */
private class HandlePositionProvider(
    private val anchor: HandleAnchor,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + anchor.position.x.roundToInt()
        return IntOffset(
            if (anchor.hangsLeft) x - popupContentSize.width else x,
            anchorBounds.top + anchor.position.y.roundToInt(),
        )
    }
}

/** Centered lines hug their text, so they never stretch to one side. */
@Composable
private fun lineSideUnlessCentered(
    text: String,
    centered: Boolean,
): Modifier = if (centered) Modifier else Modifier.lineSide(text, LocalLayoutDirection.current)

private fun TextStyle.centeredIf(centered: Boolean): TextStyle = if (centered) copy(textAlign = TextAlign.Center) else this

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
                belowGap = with(density) { (6.dp + HandleTouchSize).roundToPx() },
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
            // As wide as its widest line, so the close button sits at the bar's right edge.
            Column(Modifier.width(IntrinsicSize.Max).padding(horizontal = 6.dp, vertical = 2.dp)) {
                val actions =
                    listOf(
                        PhraseAction(
                            icon = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            label = if (copied) R.string.practice_copied else R.string.practice_copy,
                            tag = "copy",
                        ) {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, phrase))) }
                            copied = true
                        },
                        PhraseAction(Icons.Default.Translate, R.string.practice_translate, "translate", onTranslate),
                        PhraseAction(Icons.AutoMirrored.Filled.VolumeUp, R.string.practice_pronounce, "pronounce", onPronounce),
                    )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    actions.forEach { action ->
                        IconButton(onClick = action.onClick, modifier = Modifier.testTag("phrase_${action.tag}")) {
                            Icon(action.icon, contentDescription = stringResource(action.label), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    PhraseActionMenu(actions)
                    Spacer(Modifier.weight(1f))
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
                        ?: if (singleWord) stringResource(R.string.practice_drag_handles) else null
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

/** One action of the phrase bar: an icon in the bar, and the icon with its name in the ▾ list. */
private class PhraseAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val tag: String,
    val onClick: () -> Unit,
)

/** The ▾ button listing every action with its icon and name, for anyone unsure what an icon does. */
@Composable
private fun PhraseActionMenu(actions: List<PhraseAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("phrase_all_actions")) {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.practice_all_actions),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    modifier = Modifier.testTag("phrase_menu_${action.tag}"),
                )
            }
        }
    }
}

/** The selection's meaning, shown right in the bar so no extra tap is needed to understand it. */
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
    private val belowGap: Int,
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
            belowGap = belowGap,
        )
}
