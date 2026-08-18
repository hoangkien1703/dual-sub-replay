package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WebNavigationPolicyTest {
    @Test
    fun embedsTrustedYouTubeAndGoogleAccountUrls() {
        listOf(
            "https://youtube.com/",
            "https://m.youtube.com/results?search_query=english",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://accounts.google.com/signin",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://consent.google.com/m?continue=youtube",
            "https://accounts.googleusercontent.com/checkcookie",
        ).forEach { url ->
            val expected = if (
                url.startsWith("https://accounts.google.com") ||
                url.startsWith("https://accounts.youtube.com") ||
                url.startsWith("https://consent.google.com") ||
                url.startsWith("https://accounts.googleusercontent.com")
            ) {
                EmbeddedNavigationDecision.GOOGLE_SIGN_IN
            } else {
                EmbeddedNavigationDecision.YOUTUBE_WEB
            }
            assertEquals(url, expected, classifyMainFrameUrl(url))
        }
    }

    @Test
    fun routesOtherHttpsOriginsOutsideTheApp() {
        listOf(
            "https://example.com/",
            "https://support.google.com/youtube/",
            "https://youtube.com.evil.test/watch",
        ).forEach { url ->
            assertEquals(url, EmbeddedNavigationDecision.OPEN_EXTERNAL, classifyMainFrameUrl(url))
        }
    }

    @Test
    fun blocksDeceptiveUserInfoNonHttpsAndActiveContentSchemes() {
        listOf(
            "https://youtube.com@evil.test/watch",
            "https://accounts.google.com@evil.test/",
            "http://youtube.com/",
            "file:///data/local/tmp/page.html",
            "javascript:alert(1)",
            "not a url",
        ).forEach { url ->
            assertEquals(url, EmbeddedNavigationDecision.BLOCK, classifyMainFrameUrl(url))
        }
    }

    @Test
    fun replacesRestoredUntrustedUrlsWithYouTubeHome() {
        assertEquals(
            "https://m.youtube.com/results?search_query=english",
            trustedEmbeddedUrlOrHome("https://m.youtube.com/results?search_query=english"),
        )
        assertEquals(YOUTUBE_HOME_URL, trustedEmbeddedUrlOrHome("https://example.com/phishing"))
        assertEquals(YOUTUBE_HOME_URL, trustedEmbeddedUrlOrHome("file:///data/local/tmp/page.html"))
    }
}
