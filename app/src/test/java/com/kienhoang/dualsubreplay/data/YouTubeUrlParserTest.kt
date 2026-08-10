package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {
    @Test fun parsesStandardWatchUrl() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12"))
    }

    @Test fun parsesSharedTextAndShortUrl() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.extractVideoId("Watch this: https://youtu.be/dQw4w9WgXcQ?si=abc"))
    }

    @Test fun parsesShortsAndRawIds() {
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.extractVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeUrlParser.extractVideoId("dQw4w9WgXcQ"))
    }

    @Test fun rejectsNonYouTubeUrls() {
        assertNull(YouTubeUrlParser.extractVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
    }
}
