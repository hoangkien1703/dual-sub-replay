package com.kienhoang.dualsubreplay.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnDeviceTranslator {
    suspend fun translateAll(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
        onTranslation: suspend (index: Int, translatedText: String) -> Unit,
    ) {
        val normalizedSource = TranslationLanguages.normalize(sourceLanguageCode)
        val normalizedTarget = TranslationLanguages.normalize(targetLanguageCode)
        val source = TranslateLanguage.fromLanguageTag(normalizedSource)
            ?: throw IllegalArgumentException("${TranslationLanguages.displayName(sourceLanguageCode)} translation is not supported.")
        val target = TranslateLanguage.fromLanguageTag(normalizedTarget)
            ?: throw IllegalArgumentException("${TranslationLanguages.displayName(targetLanguageCode)} translation is not supported.")
        if (source == target) {
            texts.forEachIndexed { index, text -> onTranslation(index, text) }
            return
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            texts.indices.forEach { index ->
                onTranslation(index, translator.translate(texts[index]).awaitResult())
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
}
