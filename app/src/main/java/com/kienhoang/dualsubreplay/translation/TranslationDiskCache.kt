package com.kienhoang.dualsubreplay.translation

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Best-effort, bounded, persistent cache. All access is on an IO dispatcher. */
internal class TranslationDiskCache(
    private val directory: File,
    private val maxEntries: Int = 2048,
    private val maxBytes: Long = 4L * 1024 * 1024,
) {
    private val entries = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var initialized = false
    private var bytes = 0L

    @Synchronized fun get(
        source: String,
        target: String,
        text: String,
    ): String? =
        safely {
            initialize()
            val key = key(source, target, text)
            val size = entries[key] ?: return@safely null
            val file = File(directory, key)
            if (!file.isFile || file.length() != size || size > maxBytes) {
                remove(key)
                return@safely null
            }
            file.setLastModified(System.currentTimeMillis())
            file.readText(Charsets.UTF_8)
        }

    @Synchronized fun put(
        source: String,
        target: String,
        text: String,
        translation: String,
    ) {
        safely {
            initialize()
            val data = translation.toByteArray(Charsets.UTF_8)
            if (data.size > maxBytes || maxEntries <= 0) return@safely null
            val key = key(source, target, text)
            val temporary = File(directory, "$key.tmp")
            try {
                temporary.writeBytes(data)
                remove(key)
                if (!temporary.renameTo(File(directory, key))) return@safely null
                entries[key] = data.size.toLong()
                bytes += data.size
                trim()
            } finally {
                temporary.delete()
            }
            null
        }
    }

    private fun initialize() {
        if (initialized) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create translation cache")
        directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach { file ->
            if (file.isFile && file.name.matches(Regex("[a-f0-9]{64}"))) {
                entries[file.name] = file.length()
                bytes += file.length()
            } else {
                file.delete()
            }
        }
        trim()
        initialized = true
    }

    private fun trim() {
        while (entries.size > maxEntries || bytes > maxBytes) remove(entries.keys.first())
    }

    private fun remove(key: String) {
        entries.remove(key)?.let { bytes -= it }
        File(directory, key).delete()
    }

    private fun key(
        source: String,
        target: String,
        text: String,
    ): String {
        val input = "v1:${source.length}:$source:${target.length}:$target:$text"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun safely(block: () -> String?): String? =
        try {
            block()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
