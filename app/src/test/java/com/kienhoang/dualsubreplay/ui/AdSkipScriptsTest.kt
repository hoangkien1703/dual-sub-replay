package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSkipScriptsTest {
    private val config = webAdSkipConfigScript(true, "dQw4w9WgXcQ", listOf(SponsorSegment("abc", 12.5, 40.0, 600.0)), "Skipped 'sponsor'")

    @Test
    fun theScriptsRunOnlyOnYouTubeAndNeverChangeARequest() {
        listOf(WEB_AD_SKIP_SCRIPT, config, webAdSkipConfigScript(false, null, emptyList(), "")).forEach { script ->
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("host.endsWith('.youtube.com')"))
            assertFalse(script.contains("fetch("))
            assertFalse(script.contains("XMLHttpRequest"))
            assertFalse(script.contains("JSON.parse = "))
            assertFalse(script.contains("preventDefault"))
        }
    }

    @Test
    fun adsAreMutedPlayedAt4xAndSkippedWithYouTubesOwnButton() {
        assertEquals(4, AD_PLAYBACK_RATE)
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("const RATE = 4;"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("root.classList.contains('ad-showing')"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("if (!video.muted) video.muted = true;"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains(".ytp-skip-ad-button"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("found.find(function(item) { return item.tagName === 'BUTTON'; }) || found[0] || null;"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("button.click();"))
        // Sound and speed come back after the ad; a speed changed meanwhile stays.
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("if (video.playbackRate === RATE && typeof video.__dualSubAdRate === 'number')"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("video.muted = video.__dualSubAdMuted;"))
        assertTrue("Installed once per page", WEB_AD_SKIP_SCRIPT.contains("if (window.__dualSubAdSkip) return true;"))
        assertTrue(
            "Off until the app turns it on",
            WEB_AD_SKIP_SCRIPT.contains("config: { ads: false, always: false, videoId: null, segments: []"),
        )
    }

    @Test
    fun sponsorPartsAreSkippedOnceAndASeekIntoOneKeepsIt() {
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("document.addEventListener('timeupdate'"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("document.addEventListener('seeking'"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("state.kept[segment.id] = true;"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("Math.abs(video.duration - segment.duration) > 3"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("config.videoId !== pageVideoId()"))
    }

    @Test
    fun theConfigQuotesItsTextAndStartsOverOnANewVideo() {
        assertTrue(config.contains("""segments: [{id:"abc",start:12.500,end:40.000,duration:600.000}]"""))
        assertTrue(config.contains("""note: "Skipped 'sponsor'""""))
        assertTrue(config.contains("""if (state.config.videoId !== "dQw4w9WgXcQ") state.kept = {};"""))
        assertTrue(config.contains("ads: true"))
        val off = webAdSkipConfigScript(false, null, emptyList(), "")
        assertTrue(off.contains("ads: false, always: false, videoId: null, segments: []"))
        assertTrue("Not installed: nothing to configure", off.contains("if (!state) return false;"))
    }

    @Test
    fun theAppTapsOnlyAShownSkipButtonThatIsReallyUnderTheTap() {
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("style.visibility === 'hidden'"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("const hit = document.elementFromPoint(x, y);"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("if (!hit || !(button.contains(hit) || (hit.contains(button) && /skip/i.test("))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("Date.now() - state.skipSince < 300"))
        assertTrue(WEB_SKIP_TARGET_SCRIPT.contains("host.endsWith('.youtube.com')"))
        assertEquals(262.5f to 1050f, parseSkipTarget("""{"x":262.5,"y":1050}"""))
        assertEquals(null, parseSkipTarget("{}"))
        assertEquals(null, parseSkipTarget("null"))
        assertEquals(null, parseSkipTarget(null))
        assertEquals(null, parseSkipTarget("""{"x":-5,"y":10}"""))
        assertEquals(null, parseSkipTarget("""{"x":"a","y":10}"""))
    }

    @Test
    fun alwaysSkipSponsorsSkipsASeekIntoOneToo() {
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("if (!config.always && state.kept[segment.id]) return false;"))
        assertTrue(WEB_AD_SKIP_SCRIPT.contains("if (state.config.always) {"))
        assertTrue(webAdSkipConfigScript(true, null, emptyList(), "", always = true).contains("always: true"))
        assertTrue(webAdSkipConfigScript(true, null, emptyList(), "").contains("always: false"))
    }

    @Test
    fun bothSwitchesAreOnByDefaultAndReset() {
        assertEquals(AdSkipSettings(skipAds = true, skipSponsors = true, alwaysSkipSponsors = false), AdSkipSettings())
        assertEquals(listOf("skip_ads", "skip_sponsors", "always_skip_sponsors"), AD_SKIP_SETTING_KEYS)
        assertTrue(RESETTABLE_SETTING_KEYS.containsAll(AD_SKIP_SETTING_KEYS))
    }
}
