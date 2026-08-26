package com.kienhoang.dualsubreplay.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class OnDeviceTranslator {
    private val modelDownloadMutex = Mutex()
    private val preparedPairs = mutableSetOf<String>()

    /**
     * Downloads the translation model ahead of time. First-launch onboarding can
     * call this while the user is still reading the guide so the first video does
     * not appear to sit on "Translating…" while ML Kit fetches its model.
     */
    suspend fun prepare(
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ) {
        val languages = resolveLanguages(sourceLanguageCode, targetLanguageCode)
        if (languages.source == languages.target) return
        val translator = newTranslator(languages)
        try {
            ensureModelReady(languages, translator)
        } finally {
            translator.close()
        }
    }

    suspend fun translateAll(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
        onTranslation: suspend (index: Int, translatedText: String) -> Unit,
    ) {
        val languages = resolveLanguages(sourceLanguageCode, targetLanguageCode)
        if (languages.source == languages.target) {
            texts.forEachIndexed { index, text -> onTranslation(index, text) }
            return
        }

        val translator = newTranslator(languages)
        try {
            ensureModelReady(languages, translator)
            texts.indices.forEach { index ->
                onTranslation(index, translator.translate(texts[index]).awaitResult())
            }
        } finally {
            translator.close()
        }
    }

    private fun resolveLanguages(
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ): TranslationPair {
        val normalizedSource = TranslationLanguages.normalize(sourceLanguageCode)
        val normalizedTarget = TranslationLanguages.normalize(targetLanguageCode)
        val source = TranslateLanguage.fromLanguageTag(normalizedSource)
            ?: throw IllegalArgumentException(
                "${TranslationLanguages.displayName(sourceLanguageCode)} translation is not supported.",
            )
        val target = TranslateLanguage.fromLanguageTag(normalizedTarget)
            ?: throw IllegalArgumentException(
                "${TranslationLanguages.displayName(targetLanguageCode)} translation is not supported.",
            )
        return TranslationPair(source, target)
    }

    private fun newTranslator(languages: TranslationPair): Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(languages.source)
            .setTargetLanguage(languages.target)
            .build(),
    )

    private suspend fun ensureModelReady(languages: TranslationPair, translator: Translator) {
        val pairKey = "${languages.source}>${languages.target}"
        modelDownloadMutex.withLock {
            if (pairKey in preparedPairs) return
            try {
                withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
                }
                preparedPairs += pairKey
            } catch (error: TimeoutCancellationException) {
                throw IllegalStateException(
                    "The translation model download took too long. Check your internet connection and retry.",
                    error,
                )
            }
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    private data class TranslationPair(val source: String, val target: String)

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 45_000L
    }
}
