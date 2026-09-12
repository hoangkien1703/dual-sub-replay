package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

internal fun installedPronunciationEngines(context: Context): List<String?> {
    val preferred = Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
    val installed =
        context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }
    return (listOf(preferred) + installed).distinct()
}

internal class AndroidPronunciationEngine(
    context: Context,
    engineName: String?,
) : PronunciationEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val initialized = CompletableDeferred<Boolean>()
    private var closed = false
    private var nextUtterance = 0L
    private var utteranceId: String? = null
    private var playback: CompletableDeferred<Boolean>? = null
    private val tts =
        TextToSpeech(context.applicationContext, { status ->
            // onInit may run before the constructor returns. Post before touching tts or UI state.
            handler.post { if (!closed) initialized.complete(status == TextToSpeech.SUCCESS) }
        }, engineName)

    override suspend fun initialize(): Boolean {
        if (!initialized.await()) return false
        tts.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) = complete(id, true)

                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(id: String?) = complete(id, false)

                override fun onError(id: String?, errorCode: Int) = complete(id, false)

                override fun onStop(id: String?, interrupted: Boolean) = complete(id, false)
            },
        )
        return true
    }

    private fun complete(id: String?, success: Boolean) {
        handler.post {
            if (!closed && id == utteranceId) playback?.complete(success)
        }
    }

    override fun voices(): List<PronunciationVoice> =
        tts.voices.orEmpty().map {
            PronunciationVoice(
                it.name,
                it.locale,
                it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty(),
            )
        }

    override fun selectVoice(voice: PronunciationVoice): Boolean {
        val native = tts.voices.orEmpty().firstOrNull { it.name == voice.name } ?: return false
        return tts.setVoice(native) == TextToSpeech.SUCCESS
    }

    override fun selectLanguage(locale: Locale): Boolean = tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE

    override suspend fun speak(word: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        playback = result
        val id = "word-${++nextUtterance}"
        utteranceId = id
        val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        return try {
            if (tts.speak(word, TextToSpeech.QUEUE_FLUSH, parameters, id) == TextToSpeech.ERROR) false else result.await()
        } finally {
            utteranceId = null
            playback = null
            tts.stop()
        }
    }

    override fun close() {
        closed = true
        utteranceId = null
        playback?.cancel()
        initialized.cancel()
        handler.removeCallbacksAndMessages(null)
        try {
            tts.stop()
        } finally {
            tts.shutdown()
        }
    }
}
