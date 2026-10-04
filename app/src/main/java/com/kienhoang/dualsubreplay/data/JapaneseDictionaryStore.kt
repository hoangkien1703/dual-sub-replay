package com.kienhoang.dualsubreplay.data

import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.ResourceResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** The published Kuromoji IPADIC artifact the app downloads, pinned by size and SHA-256. */
internal object JapaneseDictionaryRelease {
    const val FILE_NAME = "kuromoji-ipadic-0.9.0.jar"
    const val SIZE_BYTES = 13_343_016L
    const val SHA256 = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
    private const val PATH = "com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"

    /** Maven Central, then Google's mirror of it. The checksum makes the host irrelevant to integrity. */
    val URLS =
        listOf(
            "https://repo1.maven.org/maven2/$PATH",
            "https://maven-central.storage-download.googleapis.com/maven2/$PATH",
        )
}

/**
 * Keeps the Japanese dictionary in app storage. The APK carries Kuromoji's code but not its
 * 13 MB dictionary, so only people who watch Japanese download it, once. The file is the
 * unchanged Maven artifact; Kuromoji reads its dictionary entries straight from it.
 */
internal class JapaneseDictionaryStore(
    private val directory: File,
    private val expectedSize: Long = JapaneseDictionaryRelease.SIZE_BYTES,
    private val expectedSha256: String = JapaneseDictionaryRelease.SHA256,
    private val open: (url: String) -> InputStream = ::openWithOkHttp,
) {
    private val file: File get() = File(directory, JapaneseDictionaryRelease.FILE_NAME)

    fun isInstalled(): Boolean = file.isFile && file.length() == expectedSize

    /**
     * Downloads the dictionary unless it is already installed. Blocking; returns whether it is installed.
     * Synchronized because the settings screen and on-demand loading may both ask at once.
     */
    @Synchronized
    fun install(): Boolean {
        if (isInstalled()) return true
        return JapaneseDictionaryRelease.URLS.any { url ->
            try {
                open(url).use(::installFrom)
            } catch (_: Exception) {
                // Offline, blocked or a broken response: try the next host.
                false
            }
        }
    }

    /** Deletes the downloaded dictionary. An analyzer already loaded from it keeps working. */
    @Synchronized
    fun remove() {
        file.delete()
    }

    /** Saves [input] as the dictionary only if it is exactly the expected file. */
    internal fun installFrom(input: InputStream): Boolean {
        directory.mkdirs()
        val partial = File(directory, JapaneseDictionaryRelease.FILE_NAME + ".part")
        var installed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            partial.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (total <= expectedSize) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            installed = total == expectedSize && sha256.equals(expectedSha256, ignoreCase = true) && partial.renameTo(file)
            return installed
        } finally {
            // Also after a dropped connection or a failed write: no partial file stays behind.
            if (!installed) partial.delete()
        }
    }

    /**
     * Builds Kuromoji's analyzer from the installed dictionary. Takes about a second and ~50 MB.
     * A file that cannot be read as the dictionary is deleted, so the next attempt downloads it
     * again instead of failing the same way forever. Running out of memory keeps the file.
     */
    fun loadTokenizer(): Tokenizer =
        try {
            ZipFile(file).use { zip -> ZipDictionaryBuilder(zip).build() }
        } catch (error: IOException) {
            file.delete()
            throw error
        }

    /**
     * Kuromoji 0.9.0's IPADIC builder always reads the dictionary from its own classpath package.
     * This does the same loading steps, but reads each file from the downloaded jar instead.
     */
    private class ZipDictionaryBuilder(
        private val zip: ZipFile,
    ) : Tokenizer.Builder() {
        override fun loadDictionaries() {
            // IPADIC's default search-mode penalties; normal mode, used here, does not apply them.
            penalties = arrayListOf(2, 3000, 7, 1700)
            val files =
                ResourceResolver { name ->
                    val entry = zip.getEntry(DICTIONARY_PACKAGE + name) ?: throw FileNotFoundException(name)
                    zip.getInputStream(entry)
                }
            resolver = files
            doubleArrayTrie = DoubleArrayTrie.newInstance(files)
            connectionCosts = ConnectionCosts.newInstance(files)
            tokenInfoDictionary = TokenInfoDictionary.newInstance(files)
            characterDefinitions = CharacterDefinitions.newInstance(files)
            unknownDictionary = UnknownDictionary.newInstance(files, characterDefinitions, totalFeatures)
            insertedDictionary = InsertedDictionary(totalFeatures)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val DICTIONARY_PACKAGE = "com/atilika/kuromoji/ipadic/"

        val client: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                // Bounds the whole 13 MB transfer, so a connection that trickles cannot hold the one download forever.
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
        }

        fun openWithOkHttp(url: String): InputStream {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("The Japanese dictionary download failed (${response.code}).")
            }
            return response.body?.byteStream() ?: throw IOException("The Japanese dictionary download was empty.")
        }
    }
}
