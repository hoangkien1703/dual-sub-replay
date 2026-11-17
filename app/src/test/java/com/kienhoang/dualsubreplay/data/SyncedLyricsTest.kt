package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Free synced lyrics from LRCLIB before Gemini (owner pick 2026-11-17). */
class SyncedLyricsTest {
    private fun song(
        track: String,
        seconds: Double,
        synced: String?,
        artist: String = "Rick Astley",
    ) = JSONObject()
        .put("trackName", track)
        .put("artistName", artist)
        .put("duration", seconds)
        .put("instrumental", false)
        .put("syncedLyrics", synced ?: JSONObject.NULL)

    private val lyrics = "[00:18.90] We're no strangers to love\n[00:22.61] You know the rules and so do I\n[00:30.00] \n"

    @Test
    fun searchesForTheSongNotTheVideoWords() {
        assertEquals(
            "Rick Astley - Never Gonna Give You Up",
            lyricsSearchQuery("Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley"),
        )
        assertEquals("Fun and Games", lyricsSearchQuery("Fun and Games [Lyrics]", "7clouds"))
        assertEquals("Taylor Swift Anti-Hero", lyricsSearchQuery("Anti-Hero", "Taylor Swift - Topic"))
        assertEquals("Shakira Te Felicito", lyricsSearchQuery("Te Felicito (Official Video)", "ShakiraVEVO"))
        assertEquals("YOASOBI アイドル", lyricsSearchQuery("YOASOBI「アイドル」 Official Music Video", "Ayase / YOASOBI"))
        assertEquals("Đen - Đi Về Nhà", lyricsSearchQuery("Đen - Đi Về Nhà ft. JustaTee (M/V)", "Đen Vâu Official"))
        assertNull(lyricsSearchQuery("(Official Video) Lyrics", ""))
        assertEquals("https://lrclib.net/api/search?q=Rick%20Astley", lyricsSearchUrl("Rick Astley").toString())
    }

    @Test
    fun readsTimedLinesAndEndsThemAtTheNextLine() {
        val cues = parseLrc("[ar:Rick]\n[00:18.90] We're no strangers to love\n[00:22.61][01:10.5] Chorus line\n[00:30.00] \n")
        assertEquals(
            listOf(
                RawCaptionCue(18_900, 22_610, "We're no strangers to love"),
                RawCaptionCue(22_610, 30_000, "Chorus line"),
                RawCaptionCue(70_500, 78_500, "Chorus line"),
            ),
            cues,
        )
    }

    @Test
    fun takesTheSongWhoseLengthMatchesTheVideo() {
        val reply =
            JSONArray()
                .put(song("Never Gonna Give You Up (Live)", 260.0, lyrics))
                .put(song("Never Gonna Give You Up", 214.0, lyrics))
                .put(song("Never Gonna Give You Up (1987)", 215.09, lyrics))
                .toString()
        val picked = pickSyncedLyrics(reply, "Rick Astley - Never Gonna Give You Up (Official Music Video)", 213_000)!!
        assertEquals("Never Gonna Give You Up", picked.track)
        assertEquals(2, picked.cues.size)
    }

    @Test
    fun noLyricsRatherThanTheWrongOnes() {
        val title = "Rick Astley - Never Gonna Give You Up (Official Music Video)"
        // Too long, no timed lyrics, or another song of the same length.
        assertNull(pickSyncedLyrics(JSONArray().put(song("Never Gonna Give You Up", 230.0, lyrics)).toString(), title, 213_000))
        assertNull(pickSyncedLyrics(JSONArray().put(song("Never Gonna Give You Up", 213.0, null)).toString(), title, 213_000))
        assertNull(pickSyncedLyrics(JSONArray().put(song("Together Forever", 213.0, lyrics)).toString(), title, 213_000))
        assertNull(pickSyncedLyrics("not json", title, 213_000))
    }
}
