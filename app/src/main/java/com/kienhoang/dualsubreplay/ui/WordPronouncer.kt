package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class WordPronouncer(context: Context) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speech: Job? = null
    private var disposed = false
    var message by mutableStateOf<String?>(null)
        private set
    var showSpeechSettings by mutableStateOf(false)
        private set

    fun speak(word: String, language: String) {
        if (disposed || word.isBlank()) return
        stop()
        message = "Preparing pronunciation…"
        speech = scope.launch {
            val result =
                try {
                    withTimeoutOrNull(25_000) {
                        pronounceWord(word, language, installedPronunciationEngines(application)) {
                            AndroidPronunciationEngine(application, it)
                        }
                    } ?: PronunciationResult.PLAYBACK_FAILED
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    PronunciationResult.UNAVAILABLE
                }
            showSpeechSettings = result != PronunciationResult.SPOKEN && result != PronunciationResult.INVALID_LANGUAGE
            message =
                when (result) {
                    PronunciationResult.SPOKEN -> null
                    PronunciationResult.NO_VOICE ->
                        "No voice is available for this language. Open Speech settings to add one, then tap Pronounce again."
                    PronunciationResult.UNAVAILABLE ->
                        "Speech is unavailable. Open Speech settings to enable or install a speech engine, then try again."
                    PronunciationResult.PLAYBACK_FAILED ->
                        "Could not play pronunciation. Check media volume and your connection, or choose a voice in Speech settings."
                    PronunciationResult.INVALID_LANGUAGE -> "Choose a subtitle language before pronouncing this word."
                }
        }
    }

    fun openSpeechSettings() {
        stop()
        val opened =
            listOf("com.android.settings.TTS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS).any { action ->
                runCatching { application.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            }
        if (!opened) {
            showSpeechSettings = true
            message = "Open Android Settings and search for Text-to-speech to install or select a voice."
        }
    }

    fun stop() {
        speech?.cancel()
        speech = null
        message = null
        showSpeechSettings = false
    }

    fun close() {
        disposed = true
        stop()
        scope.cancel()
    }
}

@Composable
internal fun rememberWordPronouncer(): WordPronouncer {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val pronouncer = remember(context) { WordPronouncer(context) }
    DisposableEffect(pronouncer, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pronouncer.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(pronouncer) {
        onDispose { pronouncer.close() }
    }
    return pronouncer
}
