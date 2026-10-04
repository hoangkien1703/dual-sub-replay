package com.kienhoang.dualsubreplay.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnDeviceTranslator(
    cacheDirectory: File? = null,
    // Used by the F-Droid build's downloadable models; ML Kit manages its own.
    @Suppress("UNUSED_PARAMETER") modelDirectory: File? = null,
) {
    private val diskCache = cacheDirectory?.let { TranslationDiskCache(it) }
    private val cache = TranslationCache()
    private val modelDownloadMutex = Mutex()
    private val preparedPairs = mutableSetOf<String>()
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    /**
     * Downloads the translation model ahead of time. First-launch onboarding can
     * call this while the user is still reading the guide so the first video does
     * not appear to sit on "Translating…" while ML Kit fetches its model.
     */
    suspend fun prepare(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
    ) {
        val languages = resolveLanguages(sourceLanguageCode, targetLanguageCode)
        if (languages.source == languages.target) return
        val translator = newTranslator(languages)
        try {
            ensureModelReady(languages, translator, onDownloadingChange)
        } finally {
            translator.close()
        }
    }

    suspend fun translateSingle(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String = withSession(sourceLanguageCode, targetLanguageCode) { translate -> translate(text) }

    /** One client for the playback session, opened lazily only on a cache miss. */
    internal suspend fun <T> withSession(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        block: suspend (suspend (String) -> String) -> T,
    ): T {
        val languages = resolveLanguages(sourceLanguageCode, targetLanguageCode)
        var client: Translator? = null
        try {
            return block { text ->
                if (text.isBlank() || languages.source == languages.target) {
                    text
                } else {
                    val cached =
                        cache.get(languages.source, languages.target, text)
                            ?: withContext(Dispatchers.IO) { diskCache?.get(languages.source, languages.target, text) }
                    if (cached != null) {
                        cache.put(languages.source, languages.target, text, cached)
                        cached
                    } else {
                        // Keep the client only once its model is ready, so a retry downloads it again.
                        val active =
                            client ?: newTranslator(languages).also { created ->
                                try {
                                    ensureModelReady(languages, created, onDownloadingChange)
                                } catch (error: Throwable) {
                                    created.close()
                                    throw error
                                }
                                client = created
                            }
                        val translated = active.translate(text).awaitResult()
                        cache.put(languages.source, languages.target, text, translated)
                        withContext(Dispatchers.IO) { diskCache?.put(languages.source, languages.target, text, translated) }
                        translated
                    }
                }
            }
        } finally {
            client?.close()
        }
    }

    suspend fun translateAll(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        texts: List<String>,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        onTranslation: suspend (index: Int, translatedText: String) -> Unit,
    ) = withSession(sourceLanguageCode, targetLanguageCode, onDownloadingChange) { translate ->
        texts.forEachIndexed { index, text ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            onTranslation(index, translate(text))
        }
    }

    /** The app languages whose model this build can download. English is built in, so it is not listed. */
    suspend fun downloadableLanguages(): List<String> =
        TranslationLanguages.all
            .map(TranslationLanguageOption::code)
            .filter { it != TranslateLanguage.ENGLISH && TranslateLanguage.fromLanguageTag(it) != null }

    /** The app languages whose model is on this device, English excluded. */
    suspend fun downloadedLanguages(): Set<String> =
        modelManager
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .awaitResult()
            .map { TranslationLanguages.normalize(it.language) }
            .filter { it != TranslateLanguage.ENGLISH }
            .toSet()

    /** Downloads [languageCode]'s model now, so later translations need no network. */
    suspend fun downloadLanguage(languageCode: String) {
        val model = remoteModel(languageCode)
        try {
            withTimeout(LANGUAGE_DOWNLOAD_TIMEOUT_MS) {
                modelManager.download(model, DownloadConditions.Builder().build()).awaitResult()
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("The language download took too long. Check your internet connection and retry.", error)
        }
    }

    /** Deletes [languageCode]'s model; it downloads again the next time it is needed. */
    suspend fun removeLanguage(languageCode: String) {
        val model = remoteModel(languageCode)
        modelDownloadMutex.withLock {
            modelManager.deleteDownloadedModel(model).awaitResult()
            preparedPairs.removeAll { pair -> model.language in pair.split('>') }
        }
    }

    private fun remoteModel(languageCode: String): TranslateRemoteModel {
        val language =
            TranslateLanguage.fromLanguageTag(TranslationLanguages.normalize(languageCode))
                ?: throw IllegalArgumentException("${TranslationLanguages.displayName(languageCode)} translation is not supported.")
        return TranslateRemoteModel.Builder(language).build()
    }

    private fun resolveLanguages(
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ): TranslationPair {
        val normalizedSource = TranslationLanguages.normalize(sourceLanguageCode)
        val normalizedTarget = TranslationLanguages.normalize(targetLanguageCode)
        val source =
            TranslateLanguage.fromLanguageTag(normalizedSource)
                ?: throw IllegalArgumentException(
                    "${TranslationLanguages.displayName(sourceLanguageCode)} translation is not supported.",
                )
        val target =
            TranslateLanguage.fromLanguageTag(normalizedTarget)
                ?: throw IllegalArgumentException(
                    "${TranslationLanguages.displayName(targetLanguageCode)} translation is not supported.",
                )
        return TranslationPair(source, target)
    }

    private fun newTranslator(languages: TranslationPair): Translator =
        Translation.getClient(
            TranslatorOptions
                .Builder()
                .setSourceLanguage(languages.source)
                .setTargetLanguage(languages.target)
                .build(),
        )

    private suspend fun ensureModelReady(
        languages: TranslationPair,
        translator: Translator,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
    ) {
        val pairKey = "${languages.source}>${languages.target}"
        modelDownloadMutex.withLock {
            if (pairKey in preparedPairs) return
            try {
                onDownloadingChange?.invoke(true)
                withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
                }
                preparedPairs += pairKey
            } catch (error: TimeoutCancellationException) {
                throw IllegalStateException(
                    "The translation model download took too long. Check your internet connection and retry.",
                    error,
                )
            } finally {
                onDownloadingChange?.invoke(false)
            }
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
            addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            addOnCanceledListener { continuation.cancel() }
        }

    private data class TranslationPair(
        val source: String,
        val target: String,
    )

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 45_000L

        // A settings download has no caption waiting on it, so it may take longer on slow networks.
        const val LANGUAGE_DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
    }
}
