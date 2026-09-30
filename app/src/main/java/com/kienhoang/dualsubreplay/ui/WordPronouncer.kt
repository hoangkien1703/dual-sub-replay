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
import java.io.File

internal class WordPronouncer(context: Context) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = PronunciationCache(File(application.cacheDir, "pronunciation"))
    private var speech: Job? = null
    private var disposed = false
    var message by mutableStateOf<String?>(null)
        private set
    var showSpeechSettings by mutableStateOf(false)
        private set

    fun speak(word: String, language: String) {
        if (disposed || word.isBlank()) return
        stop()
        if (cache.lookup(word, language) == null) message = "Preparing pronunciation…"
        speech = scope.launch {
            val result = pronounce(word, language)
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

    /**
     * Drops the recorded speech of the last word unless the learner is still on [word], and stops
     * that word's speech if it is still being recorded or played, so it cannot start late.
     */
    fun forgetUnless(
        word: String,
        language: String,
    ) {
        if (!cache.holds(word, language)) stop()
        cache.keepOnly(word, language)
    }

    /**
     * Replays the recorded speech when [word] was the last word pronounced. Otherwise records it to
     * a file, keeps that file for the next replay and plays it; engines that cannot record speak it
     * directly instead.
     */
    private suspend fun pronounce(
        word: String,
        language: String,
    ): PronunciationResult {
        cache.lookup(word, language)?.let { audio ->
            if (withTimeoutOrNull(15_000) { playSpeechFile(audio) } == true) return PronunciationResult.SPOKEN
        }
        val file = cache.prepare(word, language)
        val recorded = attempt(word, language) { synthesize(word, file) && file.length() > 0 }
        if (recorded == PronunciationResult.SPOKEN) {
            cache.markReady()
            if (withTimeoutOrNull(15_000) { playSpeechFile(file) } == true) return PronunciationResult.SPOKEN
        }
        cache.clear()
        // Only a recording or playback failure is worth a second try; the rest fail the same way.
        if (recorded != PronunciationResult.SPOKEN && recorded != PronunciationResult.PLAYBACK_FAILED) return recorded
        return attempt(word, language) { speak(word) }
    }

    private suspend fun attempt(
        word: String,
        language: String,
        render: suspend PronunciationEngine.() -> Boolean,
    ): PronunciationResult =
        try {
            withTimeoutOrNull(25_000) {
                pronounceWord(word, language, installedPronunciationEngines(application), render) {
                    AndroidPronunciationEngine(application, it)
                }
            } ?: PronunciationResult.PLAYBACK_FAILED
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            PronunciationResult.UNAVAILABLE
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
        cache.clear()
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
