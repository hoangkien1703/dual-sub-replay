package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

internal class WordPronouncer(context: Context) {
    var message by mutableStateOf<String?>(null)
        private set
    private var ready = false
    private var initializationFailed = false
    private var disposed = false
    private var pending: Pair<String, String>? = null
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (!disposed) {
                ready = status == TextToSpeech.SUCCESS
                if (ready) pending?.let { speak(it.first, it.second) }
                else {
                    initializationFailed = true
                    if (pending != null) message = "Speech is unavailable on this device."
                    pending = null
                }
            }
        }
    }
    fun speak(word: String, language: String) {
        if (disposed || word.isBlank()) return
        if (initializationFailed) { message = "Speech is unavailable on this device."; return }
        if (!ready) { pending = word to language; return }
        pending = null
        val tts = engine ?: return
        val result = tts.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            message = "Install a speech voice for this language in Android settings."
            return
        }
        message = null
        if (tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word") == TextToSpeech.ERROR) {
            message = "Could not pronounce this word. Try again."
        }
    }
    fun stop() { pending = null; engine?.stop() }
    fun close() { disposed = true; stop(); engine?.shutdown(); engine = null }
}

@Composable
internal fun rememberWordPronouncer(): WordPronouncer {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val pronouncer = remember(context) { WordPronouncer(context) }
    DisposableEffect(pronouncer, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pronouncer.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); pronouncer.close() }
    }
    return pronouncer
}
