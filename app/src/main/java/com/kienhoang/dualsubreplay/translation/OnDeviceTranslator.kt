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
        texts: List<String>,
        onTranslation: suspend (index: Int, translatedText: String) -> Unit,
    ) {
        val source = TranslateLanguage.fromLanguageTag(sourceLanguageCode.substringBefore('-'))
            ?: TranslateLanguage.ENGLISH
        if (source == TranslateLanguage.VIETNAMESE) {
            texts.forEachIndexed { index, text -> onTranslation(index, text) }
            return
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build(),
        )
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            texts.forEachIndexed { index, text ->
                onTranslation(index, translator.translate(text).awaitResult())
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
