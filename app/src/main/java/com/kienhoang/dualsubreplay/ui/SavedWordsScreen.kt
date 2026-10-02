package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun intervalLabel(interval: Long): String {
    if (interval < DAY_MS) return stringResource(R.string.practice_interval_ten_minutes)
    val days = (interval / DAY_MS).toInt()
    return pluralStringResource(R.plurals.practice_interval_days, days, days)
}

@StringRes
private fun ratingLabel(rating: ReviewRating): Int =
    when (rating) {
        ReviewRating.AGAIN -> R.string.practice_rating_again
        ReviewRating.HARD -> R.string.practice_rating_hard
        ReviewRating.GOOD -> R.string.practice_rating_good
        ReviewRating.EASY -> R.string.practice_rating_easy
    }

/** A review button's label: the rating and the wait until the word comes back. */
@Composable
private fun ratingText(
    rating: ReviewRating,
    interval: Long,
): String = stringResource(R.string.practice_rating_with_interval, stringResource(ratingLabel(rating)), intervalLabel(interval))

@Composable
internal fun SavedWordsScreen(
    repository: VocabularyRepository,
    onOnline: (SavedWord) -> Unit,
    onPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    val words by repository.words.collectAsStateWithLifecycle()
    val malformed by repository.malformedRecords.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pronouncer = rememberWordPronouncer()
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var practice by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<SavedWord?>(null) }
    var onlinePreview by remember { mutableStateOf(false) }
    val selected = words.firstOrNull { it.id == if (practice) queue.firstOrNull() else selectedId }
    var revealed by remember(selected?.id, practice) { mutableStateOf(false) }
    var editedMeaning by remember(selected?.id, selected?.meaning) { mutableStateOf(selected?.meaning.orEmpty()) }
    val due = words.filter { it.dueAt <= now }.sortedBy { it.dueAt }
    val locale = LocalContext.current.interfaceLocale()
    val searchLabel = stringResource(R.string.practice_search_label)
    val pronounceLabel = stringResource(R.string.practice_pronounce)
    val showMeaningLabel = stringResource(R.string.practice_show_meaning)
    val originalSentenceNote = stringResource(R.string.practice_video_uses_original_sentence)
    val playOnlineLabel = stringResource(R.string.practice_play_online_example)
    val onlineExampleLabel = stringResource(R.string.practice_online_example)
    val backLabel = stringResource(R.string.practice_back_to_saved_words)
    val deleteWordLabel = stringResource(R.string.practice_delete_word)

    LaunchedEffect(Unit) {
        try { repository.refresh() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = R.string.practice_load_failed }
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    DisposableEffect(Unit) { onDispose { onPause(); pronouncer.stop() } }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); error = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = R.string.practice_update_failed }
            finally { busy = false }
        }
    }
    fun returnFromVideo() { onPause(); onlinePreview = false }

    if (onlinePreview) {
        BackHandler { returnFromVideo() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(tonalElevation = 8.dp, modifier = Modifier.padding(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.practice_example_video_title))
                    Text(stringResource(R.string.practice_example_video_unavailable_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = ::returnFromVideo, modifier = Modifier.testTag("return_to_words")) {
                        Text(stringResource(R.string.practice_return_to_practice))
                    }
                }
            }
        }
        return
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.practice_title), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.practice_close)) }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (malformed > 0) {
                    Text(
                        pluralStringResource(R.plurals.practice_malformed_records, malformed, malformed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selected == null) {
                    if (practice) {
                        Text(stringResource(R.string.practice_session_complete), modifier = Modifier.testTag("practice_complete"))
                        Text(stringResource(R.string.practice_session_complete_body))
                        TextButton(onClick = { practice = false }) { Text(backLabel) }
                    } else {
                        PracticeTransferControls(repository, words)
                        Text(
                            stringResource(
                                R.string.practice_word_summary,
                                pluralStringResource(R.plurals.practice_word_count, words.size, words.size),
                                pluralStringResource(R.plurals.practice_due_count, due.size, due.size),
                            ),
                        )
                        Button(enabled = due.isNotEmpty(), modifier = Modifier.testTag("practice_words"), onClick = {
                            queue = due.map { it.id }; practice = true
                        }) { Text(stringResource(R.string.practice_due_words_button)) }
                        OutlinedTextField(search, { search = it }, label = { Text(searchLabel) }, modifier = Modifier.fillMaxWidth())
                        if (words.isEmpty()) Text(stringResource(R.string.practice_empty_hint))
                        else if (due.isEmpty()) words.minOfOrNull { it.dueAt }?.let {
                            val next = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale).format(Date(it))
                            Text(stringResource(R.string.practice_next_review, next), style = MaterialTheme.typography.bodySmall)
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            items(words.filter { it.word.contains(search, true) || it.meaning.contains(search, true) }, key = { it.id }) { word ->
                                ListItem(headlineContent = { Text(word.word) }, supportingContent = { Text(word.meaning) },
                                    modifier = Modifier.clickable { selectedId = word.id }.testTag("saved_word_${word.id}"))
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected.word, style = MaterialTheme.typography.headlineMedium)
                        selected.reading?.let { Text(it) }
                        TextButton(onClick = { onPause(); pronouncer.speak(selected.word, selected.wordLanguage) }) { Text(pronounceLabel) }
                        pronouncer.message?.let { Text(it) }
                        if (pronouncer.showSpeechSettings) {
                            TextButton(
                                onClick = pronouncer::openSpeechSettings,
                                modifier = Modifier.testTag("speech_settings"),
                            ) {
                                Text(stringResource(R.string.practice_speech_settings))
                            }
                        }
                        if (practice && !revealed) {
                            Button(onClick = { revealed = true }, modifier = Modifier.testTag("reveal_meaning")) { Text(showMeaningLabel) }
                        } else {
                            if (practice) Text(selected.meaning, modifier = Modifier.testTag("review_meaning"))
                            else {
                                val meaningLabel = stringResource(R.string.practice_meaning_label, selected.meaningLanguage)
                                OutlinedTextField(editedMeaning, { editedMeaning = it }, label = { Text(meaningLabel) })
                                TextButton(enabled = !busy && editedMeaning != selected.meaning, onClick = { action {
                                    repository.update(selected.id) { it.copy(meaning = editedMeaning.trim()) }
                                } }) { Text(stringResource(R.string.practice_save_meaning)) }
                            }
                            Text(selected.sentence)
                            selected.translatedSentence?.let { Text(it) }
                            if (selected.translated) Text(originalSentenceNote, style = MaterialTheme.typography.bodySmall)
                            if (selected.online) TextButton(onClick = {
                                pronouncer.stop(); onlinePreview = true; onOnline(selected)
                            }, modifier = Modifier.testTag("play_online_clip")) { Text(playOnlineLabel) }
                            if (practice) {
                                ReviewRating.entries.forEach { rating ->
                                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("review_${rating.name.lowercase()}"), onClick = {
                                        action {
                                            repository.update(selected.id) { reviewWord(it, rating, System.currentTimeMillis()) }
                                            queue = queue.drop(1); pronouncer.stop()
                                        }
                                    }) { Text(ratingText(rating, reviewInterval(selected.intervalMs, rating))) }
                                }
                            } else if (validClipRange(selected.videoId, selected.startMs, selected.endMs)) {
                                ClipChoice(onlineExampleLabel, selected.online, { enabled -> action { repository.update(selected.id) { it.copy(online = enabled) } } }, "saved_online_choice")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selectedId = null; practice = false; pronouncer.stop() }) { Text(backLabel) }
                        if (!practice) TextButton(enabled = !busy, onClick = { confirmDelete = selected }) { Text(deleteWordLabel) }
                    }
                }
            }
        }
    }
    confirmDelete?.let { word ->
        val deleteTitle = stringResource(R.string.practice_delete_word_title, word.word)
        val deleteLabel = stringResource(R.string.practice_delete)
        val keepLabel = stringResource(R.string.practice_keep)
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(deleteTitle) },
            text = { Text(stringResource(R.string.practice_delete_word_body)) },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                action { repository.remove(word.id); selectedId = null }
            }) { Text(deleteLabel) } }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(keepLabel) } })
    }
}
