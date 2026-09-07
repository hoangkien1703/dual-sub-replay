package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyVocabularyUpgradeTest {
    @Test
    fun legacyCardWithOfflineOnlyKeepsOnlineFalse() {
        // A legacy record that was offline-only: online is false, offline is true
        val legacyJson = JSONObject().apply {
            put("id", "legacy-id-1")
            put("word", "apple")
            put("reading", "")
            put("wordLanguage", "en")
            put("meaningLanguage", "vi")
            put("meaning", "quả táo")
            put("sentence", "I eat an apple.")
            put("translatedSentence", "Tôi ăn một quả táo.")
            put("videoId", "dQw4w9WgXcQ")
            put("startMs", 1000L)
            put("endMs", 3000L)
            put("translated", false)
            put("online", false)
            put("offline", true)
            put("clipStatus", "ready")
            put("clipError", "")
            put("clipGeneration", 2L)
            put("dueAt", 123456789L)
            put("intervalMs", 86400000L)
        }

        val decoded = decodeWord(legacyJson)

        assertEquals("legacy-id-1", decoded.id)
        assertEquals("apple", decoded.word)
        assertEquals("quả táo", decoded.meaning)
        assertEquals("I eat an apple.", decoded.sentence)
        assertEquals("dQw4w9WgXcQ", decoded.videoId)
        assertEquals(1000L, decoded.startMs)
        assertEquals(3000L, decoded.endMs)
        assertEquals(123456789L, decoded.dueAt)
        assertEquals(86400000L, decoded.intervalMs)
        // Must NEVER enable online playback automatically on formerly offline-only cards
        assertFalse(decoded.online)

        // Encoding the upgraded word produces modern record without offline metadata
        val encoded = encodeWord(decoded)
        assertFalse(encoded.has("offline"))
        assertFalse(encoded.has("clipStatus"))
        assertFalse(encoded.has("clipGeneration"))
        assertFalse(encoded.getBoolean("online"))
    }

    @Test
    fun legacyCardWithOnlineChoiceRetainsOnlineTrue() {
        val legacyJson = JSONObject().apply {
            put("id", "legacy-id-2")
            put("word", "book")
            put("reading", "")
            put("wordLanguage", "en")
            put("meaningLanguage", "vi")
            put("meaning", "sách")
            put("sentence", "Read a book.")
            put("translatedSentence", "Đọc một cuốn sách.")
            put("videoId", "dQw4w9WgXcQ")
            put("startMs", 2000L)
            put("endMs", 4000L)
            put("translated", false)
            put("online", true)
            put("offline", true)
            put("clipStatus", "ready")
            put("dueAt", 5000L)
            put("intervalMs", 1000L)
        }

        val decoded = decodeWord(legacyJson)
        assertTrue(decoded.online)
        assertEquals("legacy-id-2", decoded.id)
        assertEquals("book", decoded.word)
        assertEquals("sách", decoded.meaning)
    }

    @Test
    fun legacyCardWithoutOnlineFieldDefaultsToOnlineFalse() {
        val legacyJson = JSONObject().apply {
            put("id", "legacy-id-3")
            put("word", "cat")
            put("wordLanguage", "en")
            put("meaningLanguage", "vi")
            put("meaning", "mèo")
            put("sentence", "A cat.")
            put("startMs", 0L)
            put("endMs", 1000L)
            put("translated", false)
        }

        val decoded = decodeWord(legacyJson)
        assertFalse(decoded.online)
        assertEquals("cat", decoded.word)
    }
}
