package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.validClipRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WordLearningDialog(
    selection: LearningWordSelection,
    autoPronounce: Boolean,
    onTranslateWord: suspend () -> String,
    onSave: suspend (meaning: String, online: Boolean, offline: Boolean) -> Unit,
    onSpeak: () -> Unit,
    speechMessage: String?,
    onDismiss: () -> Unit,
    existingWord: com.kienhoang.dualsubreplay.data.SavedWord? = null,
) {
    var meaning by remember(selection) { mutableStateOf(existingWord?.meaning.orEmpty()) }
    var loading by remember(selection) { mutableStateOf(true) }
    var error by remember(selection) { mutableStateOf<String?>(null) }
    var saved by remember(selection) { mutableStateOf(existingWord != null) }
    var saving by remember(selection) { mutableStateOf(false) }
    var online by remember(selection) { mutableStateOf(existingWord?.online ?: true) }
    var offline by remember(selection) { mutableStateOf(existingWord?.offline ?: false) }
    val scope = rememberCoroutineScope()
    val canClip = validClipRange(selection.videoId, selection.segment?.startMs ?: -1, selection.segment?.endMs ?: -1)

    LaunchedEffect(selection) {
        if (autoPronounce) onSpeak()
        try { if (existingWord == null) meaning = onTranslateWord() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "Translation unavailable. You can enter a meaning and save the word." }
        finally { loading = false }
    }
    AlertDialog(
        modifier = Modifier.testTag("word_learning_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(selection.token.text) },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selection.token.partOfSpeech.label)
                selection.token.reading?.takeIf { it.isNotBlank() }?.let { Text(it) }
                TextButton(onClick = onSpeak, modifier = Modifier.testTag("pronounce_word")) { Text("Pronounce") }
                speechMessage?.let { Text(it) }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(meaning, { meaning = it; saved = false }, label = { Text("Meaning (${selection.meaningLanguage})") },
                    enabled = !loading && !saving, modifier = Modifier.testTag("word_meaning"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (canClip) {
                    ClipChoice("Online example", online, { online = it; saved = false }, "online_clip_choice")
                    ClipChoice("Download offline clip", offline, { offline = it; saved = false }, "offline_clip_choice")
                    Text("Both options are optional. Downloads use your internet connection.", style = MaterialTheme.typography.bodySmall)
                    if (selection.translated) Text("The clip plays the original sentence, not the translated word.", style = MaterialTheme.typography.bodySmall)
                }
                if (saved) Text("Word saved", modifier = Modifier.testTag("word_saved"))
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !loading && !saved, modifier = Modifier.testTag("save_word"), onClick = {
                saving = true
                scope.launch {
                    try { onSave(meaning, online && canClip, offline && canClip); saved = true; error = null }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { error = "Could not save. Check your available storage and try again." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Saving…" else if (saved) "Saved" else "Save word") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ClipChoice(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Checkbox(checked, onChange, modifier = Modifier.testTag(tag))
    }
}
