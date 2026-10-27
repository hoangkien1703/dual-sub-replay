package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabsTest {
    private val video = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
    private val search = "https://m.youtube.com/results?search_query=japanese"

    private fun tabs(vararg urls: String) = urls.fold(BrowserTabs()) { state, url -> state.opened(url)!! }

    @Test
    fun aBrandNewUserStartsWithYouTubeHomeOpen() {
        val start = initialBrowserTabs(stored = null, lastUrl = null)
        assertEquals(listOf(YOUTUBE_HOME_URL), start.tabs.map { it.url })
        assertEquals(start.tabs.single().id, start.activeId)
    }

    @Test
    fun thePageOpenBeforeTabsExistedBecomesTheFirstTab() {
        val start = initialBrowserTabs(stored = null, lastUrl = video)
        assertEquals(video, start.active?.url)
        assertEquals(YOUTUBE_HOME_URL, initialBrowserTabs(null, "https://example.com/").active?.url)
    }

    @Test
    fun savedTabsComeBackAfterARestart() {
        val saved = tabs(video, search).withActive { it.copy(title = "Search", account = 2) }
        assertEquals(saved, decodeBrowserTabs(encodeBrowserTabs(saved)))
        assertEquals(saved, initialBrowserTabs(encodeBrowserTabs(saved), lastUrl = video))
    }

    @Test
    fun closingEveryTabIsKept() {
        val none = tabs(video).let { it.closed(it.activeId!!) }
        assertNull(none.active)
        val restored = initialBrowserTabs(encodeBrowserTabs(none), lastUrl = video)
        assertTrue(restored.tabs.isEmpty())
        assertNull(restored.active)
    }

    @Test
    fun savedTabsDropAddressesOutsideYouTube() {
        val stored = """{"active":2,"tabs":[{"id":1,"url":"https://example.com/"},{"id":2,"url":"$video","account":9}]}"""
        val restored = decodeBrowserTabs(stored)!!
        assertEquals(listOf(video), restored.tabs.map { it.url })
        assertEquals(TAB_ACCOUNT_COUNT, restored.active?.account)
        assertNull(decodeBrowserTabs("not json"))
    }

    @Test
    fun aNewTabOpensAfterTheOthersAndBecomesTheOpenOne() {
        val two = tabs(video, search)
        assertEquals(listOf(1L, 2L), two.tabs.map { it.id })
        assertEquals(2L, two.activeId)
        val background = two.opened(video, activate = false)!!
        assertEquals(2L, background.activeId)
        assertEquals(3, background.tabs.size)
    }

    @Test
    fun noMoreThanTenTabs() {
        val full = tabs(*Array(MAX_BROWSER_TABS) { video })
        assertTrue(full.full)
        assertNull(full.opened(search))
    }

    @Test
    fun closingTheOpenTabOpensTheNextOneOrThePreviousWhenItWasLast() {
        val three = tabs(video, search, YOUTUBE_HOME_URL).switchedTo(2)
        assertEquals(3L, three.closed(2).activeId)
        assertEquals(2L, three.switchedTo(3).closed(3).activeId)
        assertEquals(2L, three.closed(1).activeId)
        assertEquals(three, three.closed(42))
    }

    @Test
    fun switchingOnlyOpensATabThatExists() {
        val two = tabs(video, search)
        assertEquals(1L, two.switchedTo(1).activeId)
        assertEquals(2L, two.switchedTo(9).activeId)
    }

    @Test
    fun theTabTitleDropsYouTubesEnding() {
        assertEquals("Learn Japanese in 10 minutes", browserTabTitle("Learn Japanese in 10 minutes - YouTube"))
        assertEquals("", browserTabTitle("YouTube"))
        assertEquals("", browserTabTitle(null))
    }

    @Test
    fun tabKindsAndPictures() {
        assertEquals(BrowserTabKind.VIDEO, browserTabKind(video))
        assertEquals(BrowserTabKind.HOME, browserTabKind(YOUTUBE_HOME_URL))
        assertEquals(BrowserTabKind.SEARCH, browserTabKind(search))
        assertEquals(BrowserTabKind.OTHER, browserTabKind("https://m.youtube.com/@channel"))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", browserTabThumbnailUrl(video))
        assertNull(browserTabThumbnailUrl(search))
    }

    @Test
    fun aVideoReopensWhereItWasLeft() {
        assertEquals("$video&t=95s", watchUrlAt(video, 95_400))
        assertEquals("$video&t=120s", watchUrlAt("$video&t=10s", 120_000))
        assertEquals(video, watchUrlAt("$video&t=10s", 2_000))
        assertEquals(search, watchUrlAt(search, 95_000))
        assertEquals("https://m.youtube.com/shorts/dQw4w9WgXcQ", watchUrlAt("https://m.youtube.com/shorts/dQw4w9WgXcQ", 95_000))
    }

    @Test
    fun otherAccountsOnlyCountWhileTheSettingIsOn() {
        val tab = BrowserTab(id = 1, url = video, account = 3)
        assertEquals(3, effectiveTabAccount(tab, differentAccountsOn = true))
        assertEquals(MAIN_TAB_ACCOUNT, effectiveTabAccount(tab, differentAccountsOn = false))
        assertEquals(MAIN_TAB_ACCOUNT, effectiveTabAccount(null, differentAccountsOn = true))
        assertEquals(2, normalizeTabAccount(2))
        assertEquals(MAIN_TAB_ACCOUNT, normalizeTabAccount(0))
    }

    @Test
    fun onlyYouTubeVideoLinksOfferOpenInNewTab() {
        assertEquals(video, videoLinkOrNull(video))
        assertNull(videoLinkOrNull(search))
        assertNull(videoLinkOrNull("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(videoLinkOrNull(null))
        assertFalse(tabs(video).full)
    }
}
