package com.kienhoang.dualsubreplay.ui

import android.view.KeyEvent
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.YouTubeVideoResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLayoutTest {
    @Test
    fun anyWayOfSayingTvCounts() {
        assertTrue(isTelevision(televisionUiMode = true, leanback = false, fireTv = false))
        assertTrue(isTelevision(televisionUiMode = false, leanback = true, fireTv = false))
        assertTrue(isTelevision(televisionUiMode = false, leanback = false, fireTv = true))
        assertFalse(isTelevision(televisionUiMode = false, leanback = false, fireTv = false))
    }

    @Test
    fun theTvLayoutNeverTurnsOnOffATv() {
        assertTrue(tvLayoutOn(television = true, switchedOn = true))
        assertFalse(tvLayoutOn(television = true, switchedOn = false))
        assertFalse(tvLayoutOn(television = false, switchedOn = true))
        assertFalse(tvLayoutOn(television = false, switchedOn = false))
    }

    @Test
    fun theTvLayoutKeepsThePanelBesideTheVideo() {
        assertTrue(landscapeSplitWanted(setting = false, tvLayout = true))
        assertTrue(landscapeSplitWanted(setting = true, tvLayout = false))
        assertFalse(landscapeSplitWanted(setting = false, tvLayout = false))
    }

    @Test
    fun resetAllSettingsBringsTheTvLayoutBack() {
        assertEquals(listOf("tv_layout"), TV_LAYOUT_SETTING_KEYS)
        assertTrue(RESETTABLE_SETTING_KEYS.containsAll(TV_LAYOUT_SETTING_KEYS))
    }

    @Test
    fun mediaKeysWorkWhereverTheFocusIs() {
        for (place in listOf(TvKeyPlace(), TvKeyPlace(homeShown = true), TvKeyPlace(onPanel = true, watchPage = true))) {
            assertEquals(TvRemoteAction.TOGGLE_PLAY, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, place))
            assertEquals(TvRemoteAction.SEEK_BACK, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_REWIND, place))
            assertEquals(TvRemoteAction.SEEK_FORWARD, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, place))
            assertEquals(TvRemoteAction.NEXT_LINE, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_NEXT, place))
            assertEquals(TvRemoteAction.PREVIOUS_LINE, tvRemoteAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS, place))
        }
    }

    @Test
    fun onAVideoTheArrowsMoveBetweenLines() {
        val video = TvKeyPlace(onPage = true, watchPage = true)
        assertEquals(TvRemoteAction.TOGGLE_PLAY, tvRemoteAction(KeyEvent.KEYCODE_DPAD_CENTER, video))
        assertEquals(TvRemoteAction.TOGGLE_PLAY, tvRemoteAction(KeyEvent.KEYCODE_ENTER, video))
        assertEquals(TvRemoteAction.PREVIOUS_LINE, tvRemoteAction(KeyEvent.KEYCODE_DPAD_LEFT, video))
        assertEquals(TvRemoteAction.NEXT_LINE, tvRemoteAction(KeyEvent.KEYCODE_DPAD_RIGHT, video))
        assertEquals(TvRemoteAction.REPLAY_LINE, tvRemoteAction(KeyEvent.KEYCODE_DPAD_UP, video))
        assertEquals(TvRemoteAction.FOCUS_PANEL, tvRemoteAction(KeyEvent.KEYCODE_DPAD_DOWN, video))
        assertEquals(TvRemoteAction.SHOW_HOME, tvRemoteAction(KeyEvent.KEYCODE_BACK, video))
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_A, video))
    }

    @Test
    fun otherPlacesKeepTheirKeys() {
        // TV home, the panel and other pages move focus as Android and the page script do.
        val home = TvKeyPlace(homeShown = true, onPage = true, watchPage = true)
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_DPAD_LEFT, home))
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_BACK, home))
        val panel = TvKeyPlace(onPanel = true, watchPage = true)
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_DPAD_CENTER, panel))
        assertEquals(TvRemoteAction.FOCUS_VIDEO, tvRemoteAction(KeyEvent.KEYCODE_BACK, panel))
        val firstPage = TvKeyPlace(onPage = true)
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_DPAD_DOWN, firstPage))
        assertEquals(TvRemoteAction.SHOW_HOME, tvRemoteAction(KeyEvent.KEYCODE_BACK, firstPage))
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_BACK, TvKeyPlace(onPage = true, pageCanGoBack = true)))
        // The menu, settings buttons and the assistant keep Back for themselves.
        assertNull(tvRemoteAction(KeyEvent.KEYCODE_BACK, TvKeyPlace(watchPage = true)))
    }

    @Test
    fun onlySeekingRepeatsWhileHeld() {
        assertTrue(repeatsWhileHeld(TvRemoteAction.SEEK_BACK))
        assertTrue(repeatsWhileHeld(TvRemoteAction.SEEK_FORWARD))
        assertFalse(repeatsWhileHeld(TvRemoteAction.NEXT_LINE))
        assertFalse(repeatsWhileHeld(TvRemoteAction.TOGGLE_PLAY))
    }

    @Test
    fun lineActionsFindTheLineToPlay() {
        val starts = listOf(1_000L, 4_000L, 9_000L)
        assertEquals(1_000L, tvLineStartMs(starts, 1, null, TvRemoteAction.PREVIOUS_LINE))
        assertEquals(9_000L, tvLineStartMs(starts, 1, null, TvRemoteAction.NEXT_LINE))
        assertEquals(4_000L, tvLineStartMs(starts, 1, null, TvRemoteAction.REPLAY_LINE))
        // Previous and next stop at the ends.
        assertEquals(1_000L, tvLineStartMs(starts, 0, null, TvRemoteAction.PREVIOUS_LINE))
        assertEquals(9_000L, tvLineStartMs(starts, 2, null, TvRemoteAction.NEXT_LINE))
        // No playing line known: the position finds it, and before the first line next goes to it.
        assertEquals(9_000L, tvLineStartMs(starts, -1, 5_000L, TvRemoteAction.NEXT_LINE))
        assertEquals(1_000L, tvLineStartMs(starts, -1, 500L, TvRemoteAction.NEXT_LINE))
        assertEquals(1_000L, tvLineStartMs(starts, -1, null, TvRemoteAction.REPLAY_LINE))
        assertNull(tvLineStartMs(emptyList(), 0, 0L, TvRemoteAction.NEXT_LINE))
        assertNull(tvLineStartMs(starts, 1, null, TvRemoteAction.TOGGLE_PLAY))
    }

    @Test
    fun theFocusScriptChecksTheOriginAndCanBeRemoved() {
        for (enabled in listOf(true, false)) {
            val script = webTvFocusScript(enabled)
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("host === 'youtube.com' || host.endsWith('.youtube.com')"))
            assertTrue(script.contains("if (!$enabled)"))
        }
        assertTrue(webTvFocusScript(true).contains("removeEventListener('keydown'"))
    }

    @Test
    fun aTvWebViewIsMarkedMobileOnlyWhenItIsNot() {
        val webKit = "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0"
        val phone = "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) $webKit Chrome/139.0 Mobile Safari/537.36"
        assertEquals(phone, tvUserAgent(phone))
        val tv = "Mozilla/5.0 (Linux; Android 14; BRAVIA 4K; wv) $webKit Chrome/113.0 Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; BRAVIA 4K; wv) $webKit Chrome/113.0 Mobile Safari/537.36", tvUserAgent(tv))
        assertEquals("Odd Mobile", tvUserAgent("Odd"))
    }

    @Test
    fun recentSearchesPutTheNewestFirstWithoutCopies() {
        assertEquals(listOf("anime"), withRecentSearch(emptyList(), "  anime "))
        assertEquals(listOf("Anime", "news"), withRecentSearch(listOf("news", "anime"), "Anime"))
        assertEquals(listOf("news"), withRecentSearch(listOf("news"), "   "))
        val full = (1..6).map { "q$it" }
        assertEquals(listOf("new") + full.take(5), withRecentSearch(full, "new"))
        assertEquals(listOf("a", "b"), parseRecentSearches("a\n\n b \n"))
        assertEquals(emptyList<String>(), parseRecentSearches(null))
    }

    @Test
    fun videoDetailsSkipWhatYouTubeLeftOut() {
        val video = YouTubeVideoResult("abcdefghijk", "Title", "Channel", "12:34", "1.2M views", "1 year ago", subtitles = true)
        assertEquals("Channel · 12:34 · 1.2M views", tvVideoDetails(video))
        assertEquals("12:34", tvVideoDetails(video.copy(channel = "", views = "")))
    }

    @Test
    fun lineWordsLeaveOutPunctuation() {
        fun token(text: String) = AnalyzedToken(text, 0, text.length, PartOfSpeech.OTHER)
        val words = tvLineWords(listOf(token("今日"), token("、"), token("rain"), token("!"), token("42")))
        assertEquals(listOf("今日", "rain", "42"), words.map { it.text })
    }
}
