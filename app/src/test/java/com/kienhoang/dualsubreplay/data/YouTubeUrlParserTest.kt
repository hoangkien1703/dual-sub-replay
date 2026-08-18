package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlParserTest {
    private val videoId = "dQw4w9WgXcQ"

    @Test
    fun parsesStandardWatchUrlsAcrossYouTubeHosts() {
        listOf(
            "https://www.youtube.com/watch?v=$videoId&t=12",
            "https://m.youtube.com/watch?v=$videoId",
            "https://music.youtube.com/watch?v=$videoId&list=example",
            "youtube.com/watch?v=$videoId",
        ).forEach { input ->
            assertEquals(input, videoId, YouTubeUrlParser.extractVideoId(input))
        }
    }

    @Test
    fun parsesEveryLearningNavigationVariant() {
        listOf(
            "https://youtu.be/$videoId?si=abc",
            "https://youtube.com/shorts/$videoId",
            "https://youtube.com/live/$videoId?feature=share",
            "https://youtube.com/embed/$videoId?playsinline=1",
        ).forEach { input ->
            assertEquals(input, videoId, YouTubeUrlParser.extractVideoId(input))
        }
    }

    @Test
    fun parsesSharedTextAndRawId() {
        assertEquals(videoId, YouTubeUrlParser.extractVideoId("Watch this: https://youtu.be/$videoId?si=abc"))
        assertEquals(videoId, YouTubeUrlParser.extractVideoId(videoId))
    }

    @Test
    fun trimsCommonPunctuationFromSharedUrls() {
        assertEquals(videoId, YouTubeUrlParser.extractVideoId("Try this (https://youtu.be/$videoId)."))
    }

    @Test
    fun rejectsNonVideoAndUntrustedUrls() {
        listOf(
            "https://example.com/watch?v=$videoId",
            "https://youtube.example/watch?v=$videoId",
            "https://m.youtube.com/",
            "https://m.youtube.com/results?search_query=english",
            "not-a-video-id",
            "",
        ).forEach { input ->
            assertNull(input, YouTubeUrlParser.extractVideoId(input))
        }
    }

    @Test
    fun rejectsMalformedPercentEncodingWithoutThrowing() {
        assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com/watch?v=%ZZ"))
    }

    @Test
    fun rejectsOversizedSharedText() {
        val oversized = "x".repeat(YouTubeUrlParser.MAX_SHARED_TEXT_LENGTH + 1) +
            " https://youtu.be/$videoId"

        assertNull(YouTubeUrlParser.extractVideoId(oversized))
    }
}
