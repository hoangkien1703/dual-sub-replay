package com.kienhoang.dualsubreplay.translation

import java.nio.file.Files
import org.junit.Test

class TranslationDiskCacheTest {
    @Test
    fun revisitingAfterRestartReusesExactLanguageAndTextPairs() {
        withDirectory { directory ->
            TranslationDiskCache(directory).put("en", "vi", "Hello", "Xin chào")
            val reopened = TranslationDiskCache(directory)
            check(reopened.get("en", "vi", "Hello") == "Xin chào")
            check(reopened.get("en", "ja", "Hello") == null)
            check(reopened.get("vi", "en", "Hello") == null)
            check(reopened.get("en", "vi", "hello") == null)
        }
    }

    @Test
    fun entryLimitEvictsLeastRecentlyUsedTranslation() {
        withDirectory { directory ->
            val cache = TranslationDiskCache(directory, maxEntries = 2)
            cache.put("en", "vi", "a", "A")
            cache.put("en", "vi", "b", "B")
            check(cache.get("en", "vi", "a") == "A")
            cache.put("en", "vi", "c", "C")
            check(cache.get("en", "vi", "b") == null)
            check(cache.get("en", "vi", "a") == "A")
            check(directory.listFiles()!!.size == 2)
        }
    }

    @Test
    fun byteLimitIncludesUtf8SizeAndRejectsOversizedEntries() {
        withDirectory { directory ->
            val cache = TranslationDiskCache(directory, maxBytes = 6)
            cache.put("en", "ja", "a", "皆皆")
            cache.put("en", "ja", "b", "守")
            check(cache.get("en", "ja", "a") == null)
            cache.put("en", "ja", "huge", "皆皆皆")
            check(cache.get("en", "ja", "huge") == null)
            check(cache.get("en", "ja", "b") == "守")
            check(directory.listFiles()!!.sumOf { it.length() } <= 6)
        }
    }

    @Test
    fun missingFilesAndInterruptedWritesAreCacheMisses() {
        withDirectory { directory ->
            val cache = TranslationDiskCache(directory)
            cache.put("en", "vi", "a", "A")
            directory.listFiles()!!.forEach { it.delete() }
            check(cache.get("en", "vi", "a") == null)
            java.io.File(directory, "interrupted.tmp").writeText("partial")
            val reopened = TranslationDiskCache(directory)
            check(reopened.get("en", "vi", "a") == null)
            check(directory.listFiles()!!.isEmpty())
        }
    }

    private fun withDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("translation-cache-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
