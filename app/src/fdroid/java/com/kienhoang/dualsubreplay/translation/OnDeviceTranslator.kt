package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * F-Droid build of the translator: Mozilla's Bergamot engine with Firefox
 * Translations models, all on-device. It mirrors the full build's API. Models are
 * downloaded on first use; pairs without English pivot through English.
 */
class OnDeviceTranslator(
    cacheDirectory: File? = null,
    modelDirectory: File? = null,
) {
    private val diskCache = cacheDirectory?.let { TranslationDiskCache(it) }
    private val cache = TranslationCache()
    private val store = modelDirectory?.let { BergamotModelStore(it) }
    private val engineMutex = Mutex()
    private val loadedModels = LinkedHashMap<BergamotPair, Long>()

    suspend fun prepare(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
    ) {
        val route = resolveRoute(sourceLanguageCode, targetLanguageCode)
        if (route.isEmpty()) return
        engineMutex.withLock { route.forEach { loadModel(it, route, onDownloadingChange) } }
    }

    suspend fun translateSingle(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        text: String,
    ): String = withSession(sourceLanguageCode, targetLanguageCode) { translate -> translate(text) }

    internal suspend fun <T> withSession(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        onDownloadingChange: ((Boolean) -> Unit)? = null,
        block: suspend (suspend (String) -> String) -> T,
    ): T {
        val route = resolveRoute(sourceLanguageCode, targetLanguageCode)
        return block { text ->
            if (text.isBlank() || route.isEmpty()) {
                text
            } else {
                val source = route.first().source
                val target = route.last().target
                val cached =
                    cache.get(source, target, text)
                        ?: withContext(Dispatchers.IO) { diskCache?.get(source, target, text) }
                if (cached != null) {
                    cache.put(source, target, text, cached)
                    cached
                } else {
                    val translated = translateUncached(route, text, onDownloadingChange)
                    cache.put(source, target, text, translated)
                    withContext(Dispatchers.IO) { diskCache?.put(source, target, text, translated) }
                    translated
                }
            }
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
            currentCoroutineContext().ensureActive()
            onTranslation(index, translate(text))
        }
    }

    /** The app languages Mozilla has models for. English is the pivot, so it is not listed. */
    suspend fun downloadableLanguages(): List<String> {
        val pairs = requireStore().pairs()
        return TranslationLanguages.all
            .map(TranslationLanguageOption::code)
            .filter { code -> code != BERGAMOT_PIVOT_LANGUAGE && languagePairs(code, pairs).isNotEmpty() }
    }

    /** The app languages whose models to and from English are all on this device. */
    suspend fun downloadedLanguages(): Set<String> {
        val modelStore = requireStore()
        val pairs = modelStore.pairs()
        return downloadableLanguages()
            .filter { code -> languagePairs(code, pairs).all { modelStore.isDownloaded(it) } }
            .toSet()
    }

    /** Downloads [languageCode]'s models to and from English now. */
    suspend fun downloadLanguage(languageCode: String) {
        val modelStore = requireStore()
        val pairs = languagePairs(languageCode, modelStore.pairs())
        if (pairs.isEmpty()) {
            throw IllegalArgumentException("${TranslationLanguages.displayName(languageCode)} is not available for offline translation.")
        }
        pairs.forEach { modelStore.prepare(it, null) }
    }

    /** Deletes [languageCode]'s models; they download again the next time they are needed. */
    suspend fun removeLanguage(languageCode: String) {
        val modelStore = requireStore()
        val pairs = languagePairs(languageCode, modelStore.pairs())
        engineMutex.withLock {
            pairs.forEach { pair ->
                loadedModels.remove(pair)?.let(BergamotNative::releaseModel)
                modelStore.remove(pair)
            }
        }
    }

    private fun languagePairs(
        languageCode: String,
        published: Set<BergamotPair>,
    ): List<BergamotPair> {
        val code = bergamotLanguageCode(languageCode) ?: return emptyList()
        return listOf(BergamotPair(code, BERGAMOT_PIVOT_LANGUAGE), BergamotPair(BERGAMOT_PIVOT_LANGUAGE, code))
            .filter { it in published }
    }

    private fun requireStore(): BergamotModelStore =
        store ?: throw IllegalStateException("Offline translation has no storage for its models.")

    private suspend fun translateUncached(
        route: List<BergamotPair>,
        text: String,
        onDownloadingChange: ((Boolean) -> Unit)?,
    ): String =
        engineMutex.withLock {
            val handles = route.map { loadModel(it, route, onDownloadingChange) }
            withContext(Dispatchers.Default) {
                val input = arrayOf(text)
                val output =
                    if (handles.size == 1) {
                        BergamotNative.translate(handles[0], input)
                    } else {
                        BergamotNative.pivot(handles[0], handles[1], input)
                    }
                output.firstOrNull().orEmpty()
            }
        }

    /** Caller holds [engineMutex]. Keeps at most one route's models in memory. */
    private suspend fun loadModel(
        pair: BergamotPair,
        route: List<BergamotPair>,
        onDownloadingChange: ((Boolean) -> Unit)?,
    ): Long {
        loadedModels.remove(pair)?.let { handle ->
            loadedModels[pair] = handle
            return handle
        }
        val modelStore = requireStore()
        val config =
            try {
                modelStore.prepare(pair, onDownloadingChange)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "${TranslationLanguages.displayName(pair.source)} to " +
                        "${TranslationLanguages.displayName(pair.target)} is not available for offline translation.",
                    error,
                )
            }
        val handle = withContext(Dispatchers.Default) { nativeEngine().loadModel(config) }
        loadedModels[pair] = handle
        loadedModels.keys.filter { it !in route }.forEach { stale ->
            loadedModels.remove(stale)?.let(BergamotNative::releaseModel)
        }
        return handle
    }

    private fun resolveRoute(
        sourceLanguageCode: String,
        targetLanguageCode: String,
    ): List<BergamotPair> {
        val source =
            bergamotLanguageCode(sourceLanguageCode)
                ?: throw IllegalArgumentException("${TranslationLanguages.displayName(sourceLanguageCode)} translation is not supported.")
        val target =
            bergamotLanguageCode(targetLanguageCode)
                ?: throw IllegalArgumentException("${TranslationLanguages.displayName(targetLanguageCode)} translation is not supported.")
        return bergamotRoute(source, target)
    }

    private fun nativeEngine(): BergamotNative =
        try {
            BergamotNative
        } catch (error: LinkageError) {
            throw IllegalStateException("Offline translation is not supported on this device's processor.", error)
        }
}
