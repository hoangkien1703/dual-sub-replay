package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.ANKIDROID_PERMISSION
import com.kienhoang.dualsubreplay.data.AnkiProblem
import com.kienhoang.dualsubreplay.data.AnkiSendResult
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.knownWordKey
import com.kienhoang.dualsubreplay.data.knownWordLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Save to Practice takes this many slots of the bar; each other button takes one. */
private const val SAVE_WEIGHT = 2.4f

/** Ask AI and Anki sit between Save to Practice and More; a hidden one leaves its space empty. */
private const val MIDDLE_SLOTS = 2

/** Save to Practice while it is ready, saving, or after the word was saved. */
internal enum class SaveToPracticeState { READY, SAVING, SAVED }

/**
 * The word card's bar (owner design 2026-11-17, [spec](docs/specs/2026-11-17-word-card-action-bar.md)):
 * a wide Save to Practice, then Ask AI while the assistant is on and Anki while Send to shows, and
 * More always at the far right, with "I know this word" (while Known words is on) and Word card settings.
 */
@Composable
internal fun WordCardActionBar(
    save: SaveToPracticeState,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    askAi: (() -> Unit)?,
    anki: (@Composable (Modifier) -> Unit)?,
    known: KnownWordToggle?,
    onOpenSettings: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().testTag("word_card_actions"), verticalAlignment = Alignment.CenterVertically) {
        SaveToPracticeButton(save, saveEnabled, onSave, Modifier.weight(SAVE_WEIGHT))
        var slots = 0
        if (askAi != null) {
            slots++
            WordCardSlot(
                icon = Icons.Default.AutoAwesome,
                label = stringResource(R.string.word_card_ask_ai),
                description = stringResource(R.string.ai_explain_grammar_button),
                onClick = askAi,
                tag = "grammar_ask_ai",
                modifier = Modifier.weight(1f),
            )
        }
        if (anki != null) {
            slots++
            anki(Modifier.weight(1f))
        }
        if (slots < MIDDLE_SLOTS) Spacer(Modifier.weight((MIDDLE_SLOTS - slots).toFloat()))
        WordCardMore(known, onOpenSettings, Modifier.weight(1f))
    }
}

@Composable
private fun SaveToPracticeButton(
    state: SaveToPracticeState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val saved = state == SaveToPracticeState.SAVED
    // A saved word's button stays in the accent's quieter shade instead of the grey of a disabled button.
    val colors =
        if (saved) {
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    Button(
        onClick = onClick,
        enabled = enabled && state == SaveToPracticeState.READY,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 52.dp).testTag("save_word"),
    ) {
        Icon(
            if (saved) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(
                when (state) {
                    SaveToPracticeState.READY -> R.string.word_card_save_to_practice
                    SaveToPracticeState.SAVING -> R.string.practice_saving
                    SaveToPracticeState.SAVED -> R.string.word_card_in_practice
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One icon with its short name under it; [description] is what a screen reader says. */
@Composable
private fun WordCardSlot(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val color =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 6.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** More: "I know this word" while Known words is on, and Word card settings. */
@Composable
private fun WordCardMore(
    known: KnownWordToggle?,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        WordCardSlot(
            icon = Icons.Default.MoreHoriz,
            label = stringResource(R.string.word_card_more),
            description = stringResource(R.string.word_card_more_description),
            onClick = { open = true },
            tag = "word_card_more",
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            known?.let { word ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.study_known_word_toggle)) },
                    onClick = {
                        open = false
                        word.toggle()
                    },
                    leadingIcon = {
                        Icon(if (word.known) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, contentDescription = null)
                    },
                    modifier = Modifier.testTag("known_word_toggle"),
                )
            }
            onOpenSettings?.let { openSettings ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.word_card_settings)) },
                    onClick = {
                        open = false
                        openSettings()
                    },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.testTag("word_card_settings"),
                )
            }
        }
    }
}

/** The word card's "I know this word": only while Known words is on, for one word in the video's language. */
@Immutable
internal class KnownWordToggle(
    val known: Boolean,
    /** Automatic known words: the lines it was seen in so far, out of [after]. */
    val seen: Int,
    val after: Int,
    val toggle: () -> Unit,
)

/** "I know this word" for [selection], or null while Known words is off (the default) or it does not apply. */
@Composable
internal fun knownWordToggle(selection: LearningWordSelection): KnownWordToggle? {
    val tools = LocalStudyTools.current ?: return null
    if (!tools.settings.knownWords) return null
    val language = knownWordLanguage(selection.wordLanguage)?.takeIf { it == tools.wordsLanguage } ?: return null
    val key = selection.token.takeIf { !selection.isPhrase && !selection.translated }?.let(::knownWordKey) ?: return null
    val known = key in tools.known
    val seen = if (!known && tools.settings.knownWordsMode == KnownWordsMode.AUTO) tools.seen[key] ?: 0 else 0
    return KnownWordToggle(known, seen, tools.settings.knownWordsAfter) { tools.setKnown(language, key, !known) }
}

/**
 * Sending the word card's word to the Send to app (AnkiDroid by default; Settings → Flashcards & Anki
 * picks it): from the Anki button, or after Save to Practice when Also send saved words is on.
 */
@Stable
internal class WordCardSend(
    private val tools: StudyTools,
    private val scope: CoroutineScope,
) {
    var sending by mutableStateOf(false)
        private set
    private var sentNow by mutableStateOf(emptySet<FlashcardApp>())
    var problem by mutableStateOf<Pair<FlashcardApp, AnkiSendResult>?>(null)
        private set
    var soundWaits by mutableStateOf(false)
        private set
    var soundReplaying by mutableStateOf(false)
        private set
    var askAgain by mutableStateOf(false)
    internal var askPermission: (SavedWord) -> Unit = {}

    val app: FlashcardApp get() = tools.settings.sendTo

    /** Save to Practice sends the card too (Also send saved words, off by default). */
    val sendsOnSave: Boolean get() = tools.sendToAvailable && tools.settings.sendOnSave

    /** The Anki button shows unless Save to Practice sends; then only to try again after a problem. */
    val showsButton: Boolean get() = tools.sendToAvailable && (!tools.settings.sendOnSave || problem != null)

    fun isSent(word: SavedWord): Boolean = app in sentNow || tools.wasSent(app, word.id)

    fun tap(word: SavedWord) {
        if (isSent(word)) askAgain = true else start(word)
    }

    /** AnkiDroid asks for its permission before the first card. */
    fun start(word: SavedWord) {
        if (tools.needsPermission(app)) askPermission(word) else send(word, again = false)
    }

    fun permissionAnswered(
        word: SavedWord,
        granted: Boolean,
    ) {
        if (granted) {
            send(word, again = false)
        } else {
            problem = FlashcardApp.ANKIDROID to AnkiSendResult(problem = AnkiProblem.NO_PERMISSION)
        }
    }

    fun send(
        word: SavedWord,
        again: Boolean,
    ) {
        val to = app
        sending = true
        problem = null
        scope.launch {
            val result = tools.send(to, listOf(word), again)
            sending = false
            soundWaits = result.waiting > 0
            // The line was paused part-way: play it once now, so the card gets its sound without waiting.
            soundReplaying = soundWaits && LineAudioBridge.replayForSound(word)
            if (result.problem == null) sentNow = sentNow + to else problem = to to result
        }
    }
}

/** The word card's sending to the Send to app, or null while Send to does not show. */
@Composable
internal fun rememberWordCardSend(selection: LearningWordSelection): WordCardSend? {
    val tools = LocalStudyTools.current ?: return null
    val scope = rememberCoroutineScope()
    val send = remember(selection, tools) { WordCardSend(tools, scope) }
    var waiting by remember(selection) { mutableStateOf<SavedWord?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            waiting?.let { send.permissionAnswered(it, granted) }
        }
    SideEffect {
        send.askPermission = { word ->
            waiting = word
            permission.launch(ANKIDROID_PERMISSION)
        }
    }
    return send.takeIf { tools.sendToAvailable }
}

/** The Anki button: sends [word], or asks before sending it again. */
@Composable
internal fun WordCardAnkiButton(
    send: WordCardSend,
    word: SavedWord,
    enabled: Boolean,
    modifier: Modifier,
) {
    val name = stringResource(send.app.nameRes)
    val sent = send.isSent(word)
    WordCardSlot(
        icon = if (sent) Icons.Default.Check else Icons.Default.Style,
        label = stringResource(if (sent) R.string.word_card_sent else R.string.word_card_anki),
        description = stringResource(if (sent) R.string.study_sent_to_anki else R.string.study_send_to_anki, name),
        onClick = { send.tap(word) },
        tag = "send_to_anki",
        modifier = modifier,
        enabled = enabled && !send.sending,
        selected = sent,
    )
}

/**
 * Lines under the bar: where a saved word went, what stopped a send, when the card's sound comes,
 * and how often Automatic known words saw the word.
 */
@Composable
internal fun WordCardStatus(
    saved: Boolean,
    send: WordCardSend?,
    word: SavedWord,
    known: KnownWordToggle?,
) {
    val note = MaterialTheme.typography.bodySmall
    val noteColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (saved) {
        val sentToo = send != null && send.sendsOnSave && send.isSent(word)
        Text(
            if (sentToo) {
                stringResource(R.string.word_card_saved_and_sent, stringResource(send.app.nameRes))
            } else {
                stringResource(R.string.word_card_saved_note)
            },
            style = note,
            color = noteColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("word_saved"),
        )
    }
    if (send != null) {
        val name = stringResource(send.app.nameRes)
        if (send.askAgain) SendAgainDialog(name, { send.askAgain = false }) { send.send(word, again = true) }
        if (send.soundWaits && send.problem == null) CardSoundWaitsNote(send.soundReplaying)
        send.problem?.let { (to, result) ->
            Text(
                sendProblemText(LocalResources.current, to, result),
                color = MaterialTheme.colorScheme.error,
                style = note,
            )
            SendProblemAction(LocalStudyTools.current ?: return@let, to, result.problem)
        }
    }
    val seen = known?.seen ?: 0
    if (seen > 0) {
        Text(
            stringResource(R.string.study_word_seen, seen, known?.after ?: 0),
            style = note,
            color = noteColor,
            modifier = Modifier.padding(top = 2.dp).testTag("known_word_seen"),
        )
    }
}
