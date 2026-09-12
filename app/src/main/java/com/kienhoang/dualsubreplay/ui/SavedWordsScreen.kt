package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun intervalLabel(interval: Long): String = if (interval < DAY_MS) "10 min" else {
    val days = interval / DAY_MS
    "$days ${if (days == 1L) "day" else "days"}"
}

@Composable
internal fun SavedWordsScreen(
    repository: VocabularyRepository,
    onOnline: (SavedWord) -> Unit,
    onPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    val words by repository.words.collectAsStateWithLifecycle()
    val warning by repository.warning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pronouncer = rememberWordPronouncer()
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var practice by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<SavedWord?>(null) }
    var onlinePreview by remember { mutableStateOf(false) }
    val selected = words.firstOrNull { it.id == if (practice) queue.firstOrNull() else selectedId }
    var revealed by remember(selected?.id, practice) { mutableStateOf(false) }
    var editedMeaning by remember(selected?.id, selected?.meaning) { mutableStateOf(selected?.meaning.orEmpty()) }
    val due = words.filter { it.dueAt <= now }.sortedBy { it.dueAt }

    LaunchedEffect(Unit) {
        try { repository.refresh() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "Could not load saved words. Check storage and reopen this screen to retry." }
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    DisposableEffect(Unit) { onDispose { onPause(); pronouncer.stop() } }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block(); error = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Could not update saved words. Check storage and try again." }
            finally { busy = false }
        }
    }
    fun returnFromVideo() { onPause(); onlinePreview = false }

    if (onlinePreview) {
        BackHandler { returnFromVideo() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(tonalElevation = 8.dp, modifier = Modifier.padding(16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Example video · internet required")
                    Text("If the video is unavailable, return to keep practicing.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = ::returnFromVideo, modifier = Modifier.testTag("return_to_words")) { Text("Return to Practice") }
                }
            }
        }
        return
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Practice", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (selected == null) {
                    if (practice) {
                        Text("Session complete", modifier = Modifier.testTag("practice_complete"))
                        Text("Your reviews are saved. Come back when more words are due.")
                        TextButton(onClick = { practice = false }) { Text("Back to saved words") }
                    } else {
                        PracticeTransferControls(repository, words)
                        Text("${words.size} ${if (words.size == 1) "word" else "words"} · ${due.size} due")
                        Button(enabled = due.isNotEmpty(), modifier = Modifier.testTag("practice_words"), onClick = {
                            queue = due.map { it.id }; practice = true
                        }) { Text("Practice due words") }
                        OutlinedTextField(search, { search = it }, label = { Text("Search words or meanings") }, modifier = Modifier.fillMaxWidth())
                        if (words.isEmpty()) Text("Tap a subtitle word and choose Save word to start your collection.")
                        else if (due.isEmpty()) words.minOfOrNull { it.dueAt }?.let {
                            Text("Next review: ${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall)
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
                        TextButton(onClick = { onPause(); pronouncer.speak(selected.word, selected.wordLanguage) }) { Text("Pronounce") }
                        pronouncer.message?.let { Text(it) }
                        if (pronouncer.showSpeechSettings) {
                            TextButton(onClick = pronouncer::openSpeechSettings, modifier = Modifier.testTag("speech_settings")) { Text("Speech settings") }
                        }
                        if (practice && !revealed) {
                            Button(onClick = { revealed = true }, modifier = Modifier.testTag("reveal_meaning")) { Text("Show meaning") }
                        } else {
                            if (practice) Text(selected.meaning, modifier = Modifier.testTag("review_meaning"))
                            else {
                                OutlinedTextField(editedMeaning, { editedMeaning = it }, label = { Text("Meaning (${selected.meaningLanguage})") })
                                TextButton(enabled = !busy && editedMeaning != selected.meaning, onClick = { action {
                                    repository.update(selected.id) { it.copy(meaning = editedMeaning.trim()) }
                                } }) { Text("Save meaning") }
                            }
                            Text(selected.sentence)
                            selected.translatedSentence?.let { Text(it) }
                            if (selected.translated) Text("Video example uses the original sentence.", style = MaterialTheme.typography.bodySmall)
                            if (selected.online) TextButton(onClick = {
                                pronouncer.stop(); onlinePreview = true; onOnline(selected)
                            }, modifier = Modifier.testTag("play_online_clip")) { Text("Play online example") }
                            if (practice) {
                                ReviewRating.entries.forEach { rating ->
                                    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("review_${rating.name.lowercase()}"), onClick = {
                                        action {
                                            repository.update(selected.id) { reviewWord(it, rating, System.currentTimeMillis()) }
                                            queue = queue.drop(1); pronouncer.stop()
                                        }
                                    }) { Text("${rating.name.lowercase().replaceFirstChar { it.uppercase() }} · ${intervalLabel(reviewInterval(selected.intervalMs, rating))}") }
                                }
                            } else if (validClipRange(selected.videoId, selected.startMs, selected.endMs)) {
                                ClipChoice("Online example", selected.online, { enabled -> action { repository.update(selected.id) { it.copy(online = enabled) } } }, "saved_online_choice")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { selectedId = null; practice = false; pronouncer.stop() }) { Text("Back to saved words") }
                        if (!practice) TextButton(enabled = !busy, onClick = { confirmDelete = selected }) { Text("Delete word") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { word ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete ${word.word}?") },
            text = { Text("This removes its review history.") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                action { repository.remove(word.id); selectedId = null }
            }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } })
    }
}
