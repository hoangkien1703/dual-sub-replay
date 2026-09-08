package com.kienhoang.dualsubreplay.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabCaptionEngineTest {
    private val url = "https://m.youtube.com/watch?v=obQgWiSX8tY"

    private fun event(): JSONObject =
        JSONObject()
            .put("type", "caption")
            .put("url", url)
            .put("videoId", "obQgWiSX8tY")
            .put("languageCode", "en")
            .put("text", "Podcast episode one.")
            .put("activeWordIndex", 1)
            .put("currentSecond", 21.4)
            .put("revision", 2)
            .put("sampledAtEpochMs", 1000)

    @Test
    fun acceptsPageSelectedWordWithoutReestimatingItsPosition() {
        val sample = parseLabCaptionEvent(event().toString(), url, 1030)!!
        assertEquals(1, sample.activeWordIndex)
        assertEquals(21400L, sample.mediaTimeMs)
        assertEquals(listOf("Podcast", "episode", "one"), labCaptionWords(sample.text).map { it.text })
    }

    @Test
    fun rejectsStaleForeignMalformedAndOutOfRangeMessages() {
        assertNull(parseLabCaptionEvent(event().toString(), url, 1300))
        assertNull(parseLabCaptionEvent(event().toString(), "https://example.com", 1000))
        assertNull(parseLabCaptionEvent(event().put("videoId", "abcdefghijk").toString(), url, 1000))
        assertNull(parseLabCaptionEvent(event().put("activeWordIndex", 3).toString(), url, 1000))
        assertNull(parseLabCaptionEvent(event().put("currentSecond", -1).toString(), url, 1000))
        assertNull(parseLabCaptionEvent("{}", url, 1000))
    }

    @Test
    fun clearEventRemovesHighlightWithoutInventingAWord() {
        val sample = parseLabCaptionEvent(event().put("text", "").put("activeWordIndex", -1).toString(), url, 1000)!!
        assertEquals(false, sample.present)
        assertEquals(-1, sample.activeWordIndex)
    }
}
