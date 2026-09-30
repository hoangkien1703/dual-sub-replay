package com.kienhoang.dualsubreplay.ui

import java.io.File

/**
 * Recorded speech for the one word pronounced last, so hearing it again and again is instant.
 * Recording or choosing another word deletes it: a word's sound is kept only while it is the
 * word the learner is looking at.
 */
internal class PronunciationCache(
    private val directory: File,
) {
    private var key: String? = null
    private var audio: File? = null
    private var ready = false
    private var counter = 0

    /** The recorded speech of [text] in [language], if that is the cached word and it finished recording. */
    fun lookup(
        text: String,
        language: String,
    ): File? = audio?.takeIf { ready && key == keyOf(text, language) && it.isFile && it.length() > 0 }

    /** Whether the cached (or still recording) speech is for [text] in [language]. */
    fun holds(
        text: String,
        language: String,
    ): Boolean = key == keyOf(text, language)

    /** Deletes the cached speech unless it belongs to [text] in [language]. */
    fun keepOnly(
        text: String,
        language: String,
    ) {
        if (!holds(text, language)) clear()
    }

    /**
     * Deletes the previous word's speech, and any file a previous run left behind, and returns
     * the file to record [text] into. Call [markReady] once the recording is complete.
     */
    fun prepare(
        text: String,
        language: String,
    ): File {
        clear()
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
        counter++
        return File(directory, "word-$counter.wav").also {
            key = keyOf(text, language)
            audio = it
        }
    }

    fun markReady() {
        ready = audio != null
    }

    fun clear() {
        audio?.delete()
        audio = null
        key = null
        ready = false
    }

    private fun keyOf(
        text: String,
        language: String,
    ) = "${language.trim()}\u0000${text.trim()}"
}
