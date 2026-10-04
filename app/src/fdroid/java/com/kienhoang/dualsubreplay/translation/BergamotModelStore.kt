package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads Mozilla's free translation models on demand and keeps them in app
 * storage. Only model files are fetched; subtitle text never leaves the device.
 */
internal class BergamotModelStore(
    private val directory: File,
    private val client: OkHttpClient = defaultClient(),
) {
    @Volatile
    private var catalog: Map<BergamotPair, BergamotModel>? = null
    private val pairLocks = mutableMapOf<BergamotPair, Mutex>()

    private fun pairLock(pair: BergamotPair): Mutex = synchronized(pairLocks) { pairLocks.getOrPut(pair) { Mutex() } }

    /** Returns the model's local config, downloading any missing or damaged file first. */
    suspend fun prepare(
        pair: BergamotPair,
        onDownloadingChange: ((Boolean) -> Unit)?,
    ): String =
        // One download per pair at a time: a settings download and a caption translation may ask together.
        pairLock(pair).withLock { prepareUnlocked(pair, onDownloadingChange) }

    private suspend fun prepareUnlocked(
        pair: BergamotPair,
        onDownloadingChange: ((Boolean) -> Unit)?,
    ): String =
        withContext(Dispatchers.IO) {
            val model =
                loadCatalog()[pair]
                    ?: throw IllegalArgumentException("No offline model translates ${pair.source} to ${pair.target}.")
            val pairDirectory = File(directory, "${pair.key}/${model.version}").apply { mkdirs() }
            val missing = model.files.filterNot { File(pairDirectory, it.name).isComplete(it) }
            if (missing.isNotEmpty()) {
                onDownloadingChange?.invoke(true)
                try {
                    missing.forEach { download(it, File(pairDirectory, it.name)) }
                } finally {
                    onDownloadingChange?.invoke(false)
                }
                removeOtherVersions(pair, keep = pairDirectory)
            }
            bergamotModelConfig(
                modelPath = File(pairDirectory, model.model.name).absolutePath,
                lexicalShortlistPath = File(pairDirectory, model.lexicalShortlist.name).absolutePath,
                sourceVocabularyPath = File(pairDirectory, model.sourceVocabulary.name).absolutePath,
                targetVocabularyPath = File(pairDirectory, model.targetVocabulary.name).absolutePath,
            )
        }

    /** Every pair Mozilla publishes a release model for. Needs the network unless the catalog is cached. */
    suspend fun pairs(): Set<BergamotPair> = withContext(Dispatchers.IO) { loadCatalog().keys }

    /** Whether [pair]'s current model files are all on this device. */
    suspend fun isDownloaded(pair: BergamotPair): Boolean =
        withContext(Dispatchers.IO) {
            val model = loadCatalog()[pair] ?: return@withContext false
            val pairDirectory = File(directory, "${pair.key}/${model.version}")
            model.files.all { File(pairDirectory, it.name).isComplete(it) }
        }

    /** Deletes every downloaded version of [pair]. */
    suspend fun remove(pair: BergamotPair) {
        pairLock(pair).withLock { withContext(Dispatchers.IO) { File(directory, pair.key).deleteRecursively() } }
    }

    private fun loadCatalog(): Map<BergamotPair, BergamotModel> {
        catalog?.let { return it }
        val cached = File(directory, CATALOG_FILE)
        val fresh = cached.isFile && System.currentTimeMillis() - cached.lastModified() < CATALOG_MAX_AGE_MS
        val json =
            if (fresh) {
                cached.readText()
            } else {
                runCatching { fetchCatalog().also { writeAtomically(cached, it) } }
                    .getOrElse { error -> if (cached.isFile) cached.readText() else throw error }
            }
        return parseBergamotCatalog(json).also { catalog = it }
    }

    private fun fetchCatalog(): String {
        val request = Request.Builder().url(BERGAMOT_CATALOG_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The translation model list could not be loaded (${response.code}).")
            return response.body?.string() ?: throw IOException("The translation model list was empty.")
        }
    }

    private suspend fun download(
        file: BergamotFile,
        destination: File,
    ) {
        val partial = File(destination.parentFile, destination.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(file.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("A translation model download failed (${response.code}).")
            val body = response.body ?: throw IOException("A translation model download was empty.")
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("A downloaded translation model was damaged. Retry to download it again.")
        }
        if (!partial.renameTo(destination)) throw IOException("A translation model could not be saved.")
    }

    private fun removeOtherVersions(
        pair: BergamotPair,
        keep: File,
    ) {
        File(directory, pair.key).listFiles()?.filter { it != keep }?.forEach { it.deleteRecursively() }
    }

    private fun File.isComplete(expected: BergamotFile): Boolean = isFile && length() == expected.size

    private fun writeAtomically(
        file: File,
        text: String,
    ) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        temporary.renameTo(file)
    }

    private companion object {
        const val CATALOG_FILE = "catalog.json"
        const val CATALOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val BUFFER_BYTES = 64 * 1024

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
