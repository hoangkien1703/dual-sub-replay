package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArchitectureTest {
    @Test
    fun browseVideoSelectionNormalizesEverySupportedWatchRoute() {
        val expected = BrowseVideoSelection(
            videoId = "dQw4w9WgXcQ",
            canonicalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        )

        assertEquals(expected, browseVideoSelection("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(expected, browseVideoSelection("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(browseVideoSelection("https://m.youtube.com/results?search_query=english"))
    }

    @Test
    fun singleWebSurfaceAcceptsOnlyYouTubeMainFrameUrls() {
        assertTrue(isYouTubeWebUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(isYouTubeWebUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertFalse(isYouTubeWebUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(isYouTubeWebUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(isYouTubeWebUrl("javascript:alert(1)"))
    }

    @Test
    fun mainFrameNavigationSeparatesGoogleSignInFromYouTubeAndExternalLinks() {
        assertEquals(
            EmbeddedNavigationDecision.YOUTUBE_WEB,
            classifyMainFrameUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        assertEquals(
            EmbeddedNavigationDecision.GOOGLE_SIGN_IN,
            classifyMainFrameUrl("https://accounts.google.com/ServiceLogin?service=youtube"),
        )
        assertEquals(
            EmbeddedNavigationDecision.GOOGLE_SIGN_IN,
            classifyMainFrameUrl("https://accounts.youtube.com/accounts/SetSID"),
        )
        assertEquals(
            EmbeddedNavigationDecision.GOOGLE_SIGN_IN,
            classifyMainFrameUrl("https://consent.google.com/m?continue=youtube"),
        )
        assertEquals(
            EmbeddedNavigationDecision.GOOGLE_SIGN_IN,
            classifyMainFrameUrl("https://accounts.googleusercontent.com/checkcookie"),
        )
        assertEquals(
            EmbeddedNavigationDecision.GOOGLE_SIGN_IN,
            classifyMainFrameUrl("https://myaccount.google.com/signinoptions/security-checkup"),
        )
        assertEquals(
            EmbeddedNavigationDecision.OPEN_EXTERNAL,
            classifyMainFrameUrl("https://example.com/help"),
        )
        assertEquals(
            EmbeddedNavigationDecision.BLOCK,
            classifyMainFrameUrl("javascript:alert(1)"),
        )
        assertEquals(
            EmbeddedNavigationDecision.BLOCK,
            classifyMainFrameUrl("intent://accounts.google.com/#Intent;end"),
        )
        assertTrue(shouldOpenInsideApp(EmbeddedNavigationDecision.YOUTUBE_WEB))
        assertTrue(shouldOpenInsideApp(EmbeddedNavigationDecision.GOOGLE_SIGN_IN))
        assertFalse(shouldOpenInsideApp(EmbeddedNavigationDecision.OPEN_EXTERNAL))
        assertFalse(shouldOpenInsideApp(EmbeddedNavigationDecision.BLOCK))
    }

    @Test
    fun authenticatedYouTubeCookieDetectionIgnoresGuestCookies() {
        assertFalse(hasAuthenticatedYouTubeCookie(null))
        assertFalse(hasAuthenticatedYouTubeCookie("PREF=abc; YSC=def; VISITOR_INFO1_LIVE=ghi"))
        assertTrue(hasAuthenticatedYouTubeCookie("PREF=abc; LOGIN_INFO=account-session"))
        assertTrue(hasAuthenticatedYouTubeCookie("__Secure-3PSID=secure-session; YSC=def"))
    }

    @Test
    fun signOutNavigationsAreDistinguishedFromSignInPages() {
        assertTrue(
            isSignOutNavigation(
                "https://accounts.google.com/Logout?continue=https%3A%2F%2Fwww.youtube.com%2F",
            ),
        )
        assertTrue(
            isSignOutNavigation(
                "https://accounts.google.com/SignOutOptions?hl=en&continue=https://www.youtube.com/",
            ),
        )
        assertTrue(isSignOutNavigation("https://accounts.youtube.com/Logout"))
        assertFalse(isSignOutNavigation("https://accounts.google.com/ServiceLogin?service=youtube"))
        assertFalse(isSignOutNavigation("https://www.youtube.com/logout"))
        assertFalse(isSignOutNavigation("http://accounts.google.com/Logout"))
        assertFalse(isSignOutNavigation("not a url"))
    }

    @Test
    fun autoReturnOnlyFiresWhenSessionCookiesAppearFreshly() {
        assertFalse(
            shouldAutoReturnAfterSignIn(startedWithSessionCookies = false, sessionCookiesPresent = false),
        )
        assertTrue(
            shouldAutoReturnAfterSignIn(startedWithSessionCookies = false, sessionCookiesPresent = true),
        )
        assertFalse(
            shouldAutoReturnAfterSignIn(startedWithSessionCookies = true, sessionCookiesPresent = false),
        )
        assertFalse(
            shouldAutoReturnAfterSignIn(startedWithSessionCookies = true, sessionCookiesPresent = true),
        )
    }

    @Test
    fun activeSignInKeepsUnknownHostsEmbeddedInsteadOfChrome() {
        assertTrue(
            shouldOpenInsideApp(EmbeddedNavigationDecision.OPEN_EXTERNAL, signInEmbeddingActive = true),
        )
        assertFalse(
            shouldOpenInsideApp(EmbeddedNavigationDecision.OPEN_EXTERNAL, signInEmbeddingActive = false),
        )
        assertTrue(
            shouldOpenInsideApp(EmbeddedNavigationDecision.YOUTUBE_WEB, signInEmbeddingActive = false),
        )
        assertTrue(
            shouldOpenInsideApp(EmbeddedNavigationDecision.GOOGLE_SIGN_IN, signInEmbeddingActive = false),
        )
        assertFalse(
            shouldOpenInsideApp(EmbeddedNavigationDecision.BLOCK, signInEmbeddingActive = true),
        )
    }

    @Test
    fun playbackSnapshotParsesWebViewJavascriptResult() {
        val raw = "\"{\\\"url\\\":\\\"https://m.youtube.com/watch?v=dQw4w9WgXcQ\\\",\\\"currentSecond\\\":12.5}\""

        assertEquals(
            WebPlaybackSnapshot(
                url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                currentSecond = 12.5f,
            ),
            parseWebPlaybackSnapshot(raw),
        )
        assertNull(parseWebPlaybackSnapshot("null"))
        assertNull(parseWebPlaybackSnapshot("not-json"))
    }

    @Test
    fun playbackScriptsRecheckTheExecutingYouTubeOrigin() {
        assertTrue(WEB_PLAYBACK_SNAPSHOT_SCRIPT.contains("window.location.protocol !== 'https:'"))
        assertTrue(WEB_PLAYBACK_SNAPSHOT_SCRIPT.contains("host.endsWith('.youtube.com')"))
        assertTrue(webReplayScript(1f).contains("window.location.protocol !== 'https:'"))
        assertTrue(webReplayScript(1f).contains("host.endsWith('.youtube.com')"))
    }

    @Test
    fun replayScriptSeeksAndResumesTheNativePageVideo() {
        val script = webReplayScript(42.25f)

        assertTrue(script.contains("document.querySelectorAll('video')"))
        assertTrue(script.contains("video.currentTime = 42.25"))
        assertTrue(script.contains("video.play()"))
        assertTrue(webReplayScript(-2f).contains("video.currentTime = 0.0"))
        assertTrue(webReplayScript(Float.NaN).contains("video.currentTime = 0.0"))
    }

    @Test
    fun controllerSanitizesReplaySecondsAndIgnoresStaleUnbinds() {
        val controller = YouTubeWebController()
        val firstValues = mutableListOf<Float>()
        val firstToken = controller.bind(firstValues::add)
        val currentValues = mutableListOf<Float>()
        val currentToken = controller.bind(currentValues::add)

        controller.unbind(firstToken)
        controller.replayFrom(12.25f)
        controller.replayFrom(-4f)
        controller.replayFrom(Float.NaN)

        assertTrue(firstValues.isEmpty())
        assertEquals(listOf(12.25f, 0f, 0f), currentValues)

        controller.unbind(currentToken)
        controller.replayFrom(9f)
        assertEquals(listOf(12.25f, 0f, 0f), currentValues)
    }

    @Test
    fun activeSubtitleUsesStartInclusiveEndExclusiveBoundariesAndGaps() {
        val segments = listOf(
            SubtitleSegment(1, 1_000, 2_000, "First", null),
            SubtitleSegment(2, 2_500, 3_000, "Second", null),
        )

        assertEquals(-1, activeSubtitleIndex(segments, 999))
        assertEquals(0, activeSubtitleIndex(segments, 1_000))
        assertEquals(0, activeSubtitleIndex(segments, 1_999))
        assertEquals(-1, activeSubtitleIndex(segments, 2_000))
        assertEquals(-1, activeSubtitleIndex(segments, 2_499))
        assertEquals(1, activeSubtitleIndex(segments, 2_500))
        assertEquals(-1, activeSubtitleIndex(segments, 3_000))
    }

    @Test
    fun activeSubtitleStaysInPlaceUntilItReachesTheBottomOfThePanel() {
        val visibleItems = listOf(4, 5, 6, 7)

        assertFalse(shouldPromoteActiveSubtitle(5, visibleItems))
        assertFalse(shouldPromoteActiveSubtitle(6, visibleItems))
        assertTrue(shouldPromoteActiveSubtitle(7, visibleItems))
    }

    @Test
    fun activeSubtitlePromotesAfterASeekBeyondTheVisiblePanel() {
        assertTrue(shouldPromoteActiveSubtitle(12, listOf(4, 5, 6, 7)))
        assertFalse(shouldPromoteActiveSubtitle(-1, listOf(4, 5, 6, 7)))
        assertFalse(shouldPromoteActiveSubtitle(7, emptyList()))
    }

    @Test
    fun activeSubtitleFollowsRewindsAndSkippedSeeksImmediately() {
        assertTrue(shouldFollowPlaybackSeek(previousIndex = 12, currentIndex = 5))
        assertTrue(shouldFollowPlaybackSeek(previousIndex = 4, currentIndex = 40))
        assertFalse(shouldFollowPlaybackSeek(previousIndex = 4, currentIndex = 5))
        assertFalse(shouldFollowPlaybackSeek(previousIndex = -1, currentIndex = 3))
        assertFalse(shouldFollowPlaybackSeek(previousIndex = 3, currentIndex = -1))
    }

    @Test
    fun subtitlePanelHidesAfterEnoughDistanceOrAFastDownwardFling() {
        assertTrue(shouldHideSubtitlePanel(200f, 1_000f, 0f, 72f))
        assertTrue(shouldHideSubtitlePanel(20f, 1_000f, 1_600f, 72f))
        assertFalse(shouldHideSubtitlePanel(100f, 1_000f, 400f, 72f))
        assertFalse(shouldHideSubtitlePanel(200f, 1_000f, -2_000f, 220f))
    }

    @Test
    fun sourcePreferenceUsesTheChosenCaptionAndFallsBackToAuto() {
        assertEquals(emptyList<String>(), preferredCaptionLanguages("auto"))
        assertEquals(listOf("ja"), preferredCaptionLanguages("ja"))
        assertEquals("ja", resolvedSourcePreference("ja", "ja-JP"))
        assertEquals("auto", resolvedSourcePreference("ja", "en-US"))
    }
}
