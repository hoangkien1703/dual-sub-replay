package com.kienhoang.dualsubreplay.ui

import java.io.File

/** How many words' recorded speech is kept: a line's words and the words of the last few lines. */
internal const val PRONUNCIATION_CACHE_SIZE = 60

/**
 * Recorded speech of the last words pronounced or fetched ahead (lion737, Discussion #81,
 * 2026-11-15: the word card's other words of a line play at once). The oldest recording is deleted
 * once more than [maxEntries] are kept; files a previous run left behind are deleted on the first
 * recording. Main thread only.
 */
internal class PronunciationCache(
    private val directory: File,
    private val maxEntries: Int = PRONUNCIATION_CACHE_SIZE,
) {
    private class Entry(
        val audio: File,
    ) {
        var ready = false
    }

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)
    private var cleaned = false
    private var counter = 0

    /** The recorded speech of [text] in [language], if it finished recording. */
    fun lookup(
        text: String,
        language: String,
    ): File? = entries[pronunciationKey(text, language)]?.takeIf { it.ready }?.audio?.takeIf { it.isFile && it.length() > 0 }

    /** Whether speech of [text] in [language] is kept or still being recorded. */
    fun holds(
        text: String,
        language: String,
    ): Boolean = entries.containsKey(pronunciationKey(text, language))

    /**
     * Returns the file to record [text] into, ending in [extension], replacing any earlier
     * recording of it. Call [markReady] once the recording is complete, or [remove] when it failed.
     */
    fun prepare(
        text: String,
        language: String,
        extension: String = "wav",
    ): File {
        if (!cleaned) {
            cleaned = true
            directory.listFiles()?.forEach { it.delete() }
        }
        remove(text, language)
        directory.mkdirs()
        counter++
        val file = File(directory, "word-$counter.$extension")
        entries[pronunciationKey(text, language)] = Entry(file)
        while (entries.size > maxEntries) {
            val eldest = entries.entries.first()
            eldest.value.audio.delete()
            entries.remove(eldest.key)
        }
        return file
    }

    fun markReady(
        text: String,
        language: String,
    ) {
        entries[pronunciationKey(text, language)]?.ready = true
    }

    fun remove(
        text: String,
        language: String,
    ) {
        entries.remove(pronunciationKey(text, language))?.audio?.delete()
    }

    fun clear() {
        entries.values.forEach { it.audio.delete() }
        entries.clear()
    }
}

/** The key one word's speech is kept and looked up under. */
internal fun pronunciationKey(
    text: String,
    language: String,
) = "${language.trim()}\u0000${text.trim()}"
