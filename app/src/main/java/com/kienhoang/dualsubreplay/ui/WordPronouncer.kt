package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.GoogleWebSpeech
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import com.kienhoang.dualsubreplay.translation.speaksWithGoogle
import com.kienhoang.dualsubreplay.translation.storedTranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal class WordPronouncer(context: Context) {
    private val application = context.applicationContext

    // The activity's resources show the chosen interface language; the application's may not before Android 13.
    private val resources = context.resources
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = PronunciationCache(File(application.cacheDir, "pronunciation"))
    private val googleSpeech = GoogleWebSpeech()
    private var speech: Job? = null
    private var disposed = false
    private var lastResult: PronunciationResult? = null
    var message by mutableStateOf<String?>(null)
        private set
    var showSpeechSettings by mutableStateOf(false)
        private set

    fun speak(word: String, language: String) {
        if (disposed || word.isBlank()) return
        stop()
        if (cache.lookup(word, language) == null) message = resources.getString(R.string.practice_preparing_pronunciation)
        speech = scope.launch {
            val result = pronounce(word, language)
            lastResult = result
            showSpeechSettings = result != PronunciationResult.SPOKEN && result != PronunciationResult.INVALID_LANGUAGE
            message =
                when (result) {
                    PronunciationResult.SPOKEN -> null
                    PronunciationResult.NO_VOICE -> resources.getString(R.string.practice_speech_no_voice)
                    PronunciationResult.UNAVAILABLE -> resources.getString(R.string.practice_speech_unavailable)
                    PronunciationResult.PLAYBACK_FAILED -> resources.getString(R.string.practice_speech_playback_failed)
                    PronunciationResult.INVALID_LANGUAGE -> resources.getString(R.string.practice_speech_invalid_language)
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
     * directly instead. While Google translates subtitles, every language is first fetched from
     * Google's online voice; the phone's voices are the fallback.
     */
    private suspend fun pronounce(
        word: String,
        language: String,
    ): PronunciationResult {
        val volume = readPronunciationVolume(application)
        cache.lookup(word, language)?.let { audio ->
            if (withTimeoutOrNull(15_000) { playSpeechFile(audio, volume) } == true) return PronunciationResult.SPOKEN
        }
        if (speaksWithGoogle(word, language, translationEngine())) {
            val online = cache.prepare(word, language, extension = "mp3")
            if (googleSpeech.record(word, language, online)) {
                cache.markReady()
                if (withTimeoutOrNull(15_000) { playSpeechFile(online, volume) } == true) return PronunciationResult.SPOKEN
            }
            cache.clear()
        }
        val file = cache.prepare(word, language)
        val recorded = attempt(word, language, volume) { synthesize(word, file) && file.length() > 0 }
        if (recorded == PronunciationResult.SPOKEN) {
            // Engines record at very different levels; bring quiet recordings up to full loudness.
            withContext(Dispatchers.IO) { normalizeSpeechFile(file) }
            cache.markReady()
            if (withTimeoutOrNull(15_000) { playSpeechFile(file, volume) } == true) return PronunciationResult.SPOKEN
        }
        cache.clear()
        // Only a recording or playback failure is worth a second try; the rest fail the same way.
        if (recorded != PronunciationResult.SPOKEN && recorded != PronunciationResult.PLAYBACK_FAILED) return recorded
        return attempt(word, language, volume) { speak(word) }
    }

    private fun translationEngine() =
        storedTranslationEngine(
            application.getSharedPreferences("dual_sub_preferences", 0).getString(TRANSLATION_ENGINE_PREFERENCE, null),
        )

    private suspend fun attempt(
        word: String,
        language: String,
        volume: Float,
        render: suspend PronunciationEngine.() -> Boolean,
    ): PronunciationResult =
        try {
            withTimeoutOrNull(25_000) {
                pronounceWord(word, language, installedPronunciationEngines(application), render) {
                    AndroidPronunciationEngine(application, it, volume)
                }
            } ?: PronunciationResult.PLAYBACK_FAILED
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            PronunciationResult.UNAVAILABLE
        }

    fun openSpeechSettings() {
        val noVoice = lastResult == PronunciationResult.NO_VOICE
        stop()
        // No voice for the language: the speech engine's own voice download page comes first.
        val actions =
            listOfNotNull(
                TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA.takeIf { noVoice },
                "com.android.settings.TTS_SETTINGS",
                Settings.ACTION_ACCESSIBILITY_SETTINGS,
            )
        val opened =
            actions.any { action ->
                runCatching { application.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            }
        if (!opened) {
            showSpeechSettings = true
            message = resources.getString(R.string.practice_speech_settings_manual)
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
