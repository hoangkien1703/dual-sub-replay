package com.kienhoang.dualsubreplay.ui

import android.content.Context
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.translation.GOOGLE_SPEECH_ADDRESS_KEY
import com.kienhoang.dualsubreplay.translation.GoogleWebSpeech
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import com.kienhoang.dualsubreplay.translation.googleAddressMemory
import com.kienhoang.dualsubreplay.translation.speaksWithGoogle
import com.kienhoang.dualsubreplay.translation.storedTranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** Word sounds are kept only while a card is sent; older ones are deleted. */
private const val CARD_WORD_AUDIO_MAX_AGE_MS = 10 * 60 * 1000L

internal const val CARD_AUDIO_CACHE_DIRECTORY = "card-audio"

/**
 * Records a saved word in the voice the word card uses (owner pick 2026-11-09): Google's online
 * voice while Google translates subtitles, else the phone's own voices. The file lives in
 * `cacheDir/card-audio/` only while the card is sent.
 */
internal class WordAudioRecorder(
    context: Context,
) {
    private val application = context.applicationContext
    private val googleSpeech = GoogleWebSpeech(application.googleAddressMemory(GOOGLE_SPEECH_ADDRESS_KEY))
    private val directory = File(application.cacheDir, CARD_AUDIO_CACHE_DIRECTORY)

    suspend fun record(word: SavedWord): File? {
        val text = spokenWord(word.word, word.reading).trim()
        if (text.isEmpty()) return null
        val language = word.wordLanguage
        val name = wordAudioName(text, language)
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val now = System.currentTimeMillis()
            directory.listFiles().orEmpty().forEach { if (now - it.lastModified() > CARD_WORD_AUDIO_MAX_AGE_MS) it.delete() }
        }
        return try {
            if (!readPhoneVoice(application) && speaksWithGoogle(text, language, translationEngine())) {
                val online = File(directory, "$name.mp3")
                if (googleSpeech.record(text, language, online) && online.length() > 0) return online
                online.delete()
            }
            val file = File(directory, "$name.wav")
            val result =
                withContext(Dispatchers.Main) {
                    withTimeoutOrNull(25_000) {
                        pronounceWord(text, language, installedPronunciationEngines(application), {
                            synthesize(text, file) &&
                                file.length() > 0
                        }) {
                            AndroidPronunciationEngine(application, it)
                        }
                    }
                }
            if (result == PronunciationResult.SPOKEN) {
                // Engines record at very different levels; bring quiet recordings up to full loudness.
                withContext(Dispatchers.IO) { normalizeSpeechFile(file) }
                file
            } else {
                file.delete()
                null
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private fun translationEngine() =
        storedTranslationEngine(
            application.getSharedPreferences("dual_sub_preferences", 0).getString(TRANSLATION_ENGINE_PREFERENCE, null),
        )
}

/** A file name for [text]'s sound in [language] that needs no escaping and differs per word. */
internal fun wordAudioName(
    text: String,
    language: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$language\u0000$text".toByteArray(Charsets.UTF_8))
    return "word-" + digest.take(8).joinToString("") { "%02x".format(it) }
}
