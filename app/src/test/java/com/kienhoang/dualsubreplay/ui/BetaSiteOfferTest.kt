package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.VideoSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A shared link to a Beta site that is off asks to turn it on (25kiuo, Discussion #81, 2026-11-21). */
class BetaSiteOfferTest {
    private val allSites = BetaSite.entries

    @Test
    fun aBilibiliShareWhileItsSwitchIsOffOffersThatSite() {
        val shared = "【标题】 https://b23.tv/AbCdEf1 。"
        assertEquals(
            BetaSiteOffer(BetaSite.BILIBILI, "https://b23.tv/AbCdEf1"),
            offBetaSiteLink(shared, isOn = { false }, sites = allSites),
        )
        assertEquals(
            BetaSite.BILIBILI_TV,
            offBetaSiteLink("https://www.bilibili.tv/en/video/123", isOn = { false }, sites = allSites)?.site,
        )
    }

    @Test
    fun noOfferForSitesThatAreOnOtherLinksOrThePlayBuild() {
        assertNull(offBetaSiteLink("https://m.bilibili.com/video/BV1GJ411x7h7", isOn = { it == VideoSite.BILIBILI }, sites = allSites))
        assertNull(offBetaSiteLink("https://example.com/video", isOn = { false }, sites = allSites))
        assertNull(offBetaSiteLink("bilibili.com/video/BV1", isOn = { false }, sites = allSites))
        // The Google Play build has no Beta sites.
        assertNull(offBetaSiteLink("https://m.bilibili.com/video/BV1GJ411x7h7", isOn = { false }, sites = emptyList()))
    }
}
