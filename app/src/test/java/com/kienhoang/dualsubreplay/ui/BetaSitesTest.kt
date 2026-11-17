package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.VideoSite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaSitesTest {
    @After
    fun turnOff() = BetaSites.set(emptySet())

    @Test
    fun bilibiliPagesLeaveTheAppWhileTheirSwitchIsOff() {
        listOf("https://m.bilibili.com/", "https://passport.bilibili.com/login", "https://www.bilibili.tv/en").forEach { url ->
            assertEquals(url, EmbeddedNavigationDecision.OPEN_EXTERNAL, classifyMainFrameUrl(url))
            assertFalse(url, isVideoSiteUrl(url))
        }
        assertEquals(YOUTUBE_HOME_URL, trustedEmbeddedUrlOrHome("https://m.bilibili.com/"))
    }

    @Test
    fun eachSwitchKeepsOnlyItsOwnSiteInside() {
        BetaSites.set(setOf(VideoSite.BILIBILI))
        listOf(
            "https://m.bilibili.com/video/BV1GJ411x7h7",
            "https://www.bilibili.com/",
            "https://passport.bilibili.com/h5-app/passport/login",
            "https://b23.tv/abc123",
        ).forEach { url -> assertEquals(url, EmbeddedNavigationDecision.BILIBILI_WEB, classifyMainFrameUrl(url)) }
        assertEquals(EmbeddedNavigationDecision.OPEN_EXTERNAL, classifyMainFrameUrl("https://www.bilibili.tv/en"))

        BetaSites.set(setOf(VideoSite.BILIBILI_TV))
        assertEquals(EmbeddedNavigationDecision.BILIBILI_WEB, classifyMainFrameUrl("https://www.bilibili.tv/en/play/2059/11225817"))
        assertEquals(EmbeddedNavigationDecision.BILIBILI_WEB, classifyMainFrameUrl("https://passport.bilibili.tv/login"))
        assertEquals(EmbeddedNavigationDecision.OPEN_EXTERNAL, classifyMainFrameUrl("https://m.bilibili.com/"))
        assertTrue(shouldOpenInsideApp(EmbeddedNavigationDecision.BILIBILI_WEB))
    }

    @Test
    fun linksToBilibilisAppAndLookalikeHostsStayBlockedOrOutside() {
        BetaSites.set(setOf(VideoSite.BILIBILI, VideoSite.BILIBILI_TV))
        listOf(
            "bilibili://video/170001",
            "intent://video/170001#Intent;scheme=bilibili;package=tv.danmaku.bili;end",
            "http://m.bilibili.com/",
            "https://m.bilibili.com@evil.test/",
        ).forEach { url -> assertEquals(url, EmbeddedNavigationDecision.BLOCK, classifyMainFrameUrl(url)) }
        listOf("https://bilibili.com.evil.test/", "https://notbilibili.com/", "https://evilb23.tv/").forEach { url ->
            assertEquals(url, EmbeddedNavigationDecision.OPEN_EXTERNAL, classifyMainFrameUrl(url))
        }
    }

    @Test
    fun aBilibiliPageNamesItsVideoOnlyWhileItsSiteIsOn() {
        val url = "https://m.bilibili.com/video/BV1GJ411x7h7?p=2"
        assertNull(browseVideoSelection(url))
        BetaSites.set(setOf(VideoSite.BILIBILI))
        assertEquals("bili-BV1GJ411x7h7-p2", browseVideoSelection(url)?.videoId)
        assertEquals("dQw4w9WgXcQ", browseVideoSelection("https://m.youtube.com/watch?v=dQw4w9WgXcQ")?.videoId)
    }

    @Test
    fun bilibiliTabsGetTheirOwnNamesAndSurviveARestartWhileOn() {
        assertEquals(BrowserTabKind.BILIBILI_VIDEO, browserTabKind("https://www.bilibili.tv/en/video/4789581127420416"))
        assertEquals(BrowserTabKind.BILIBILI_PAGE, browserTabKind("https://m.bilibili.com/"))
        assertEquals("可爱的猫", browserTabTitle("可爱的猫_哔哩哔哩_bilibili"))

        val stored = encodeBrowserTabs(BrowserTabs(listOf(BrowserTab(1, "https://m.bilibili.com/video/BV1GJ411x7h7")), activeId = 1))
        assertTrue(decodeBrowserTabs(stored)!!.tabs.isEmpty())
        BetaSites.set(setOf(VideoSite.BILIBILI))
        assertEquals(1, decodeBrowserTabs(stored)!!.tabs.size)
    }
}
