package com.kienhoang.dualsubreplay.ui

import android.view.View
import com.kienhoang.dualsubreplay.assistant.AiSetting
import com.kienhoang.dualsubreplay.assistant.aiSettingsInBuild
import com.kienhoang.dualsubreplay.data.IS_PLAY_BUILD
import com.kienhoang.dualsubreplay.data.MAX_REPORTED_ANSWER_CHARS
import com.kienhoang.dualsubreplay.data.UpdateChannel
import com.kienhoang.dualsubreplay.data.aiAnswerReportBody
import com.kienhoang.dualsubreplay.data.aiAnswerReportEmailUri
import com.kienhoang.dualsubreplay.data.updateChannelFor
import com.kienhoang.dualsubreplay.update.canUpdateItself
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Google Play build (docs/specs/2026-11-21-google-play-build.md) leaves out what Play forbids; the GitHub build keeps it all. */
class PlayBuildTest {
    @Test
    fun unitTestsRunTheGitHubBuild() {
        assertFalse(IS_PLAY_BUILD)
    }

    @Test
    fun playUpdatesThePlayBuild() {
        val channel = updateChannelFor("com.kienhoang.dualsubreplay", debuggable = false, versionName = "1.9.16", playStore = true)
        assertEquals(UpdateChannel.GooglePlay, channel)
        assertFalse(canUpdateItself(channel))
        assertEquals(UpdateChannel.Official, updateChannelFor("com.kienhoang.dualsubreplay", false, "1.9.16", playStore = false))
    }

    @Test
    fun thePlayBuildNeverSkipsAdsOrSponsors() {
        val stored = AdSkipSettings(skipAds = true, skipSponsors = true, alwaysSkipSponsors = true)
        assertEquals(AdSkipSettings(skipAds = false, skipSponsors = false, alwaysSkipSponsors = false), stored.forBuild(playStore = true))
        assertEquals(stored, stored.forBuild(playStore = false))
    }

    @Test
    fun thePlayBuildPausesWhenHidden() {
        assertEquals(View.GONE, windowVisibilityForPage(View.GONE, keepPlaying = true, playStore = true))
        assertEquals(View.VISIBLE, windowVisibilityForPage(View.GONE, keepPlaying = true, playStore = false))
        assertFalse(playsOffScreen(playStore = true))
    }

    @Test
    fun thePlayBuildsSettingsPageHasNoAdsOrUpdatesSection() {
        val play = settingsGroupsOnPage(withAiAssistant = true, playStore = true).flatMap { it.second }
        assertFalse(MoreSettingsSection.ADS in play)
        assertFalse(MoreSettingsSection.UPDATES in play)
        // The TV layout is still a Beta setting.
        assertTrue(MoreSettingsSection.BETA in play)
        val github = settingsGroupsOnPage(withAiAssistant = true, playStore = false).flatMap { it.second }
        assertEquals(MoreSettingsSection.entries.toList(), github)
    }

    @Test
    fun searchAndTheAssistantOnlyFindWhatThePlayBuildHas() {
        assertTrue(betaSitesInBuild(playStore = true).isEmpty())
        assertEquals(BetaSite.entries.toList(), betaSitesInBuild(playStore = false))
        listOf(SearchableSetting.SKIP_ADS, SearchableSetting.BILIBILI, SearchableSetting.BILIBILI_TV).forEach {
            assertFalse(it.name, it.inBuild(playStore = true))
            assertTrue(it.name, it.inBuild(playStore = false))
        }
        assertTrue(SearchableSetting.TV_LAYOUT.inBuild(playStore = true))
        val play = aiSettingsInBuild(playStore = true)
        listOf(AiSetting.SKIP_ADS, AiSetting.SKIP_SPONSORS, AiSetting.APP_AUTO_UPDATE, AiSetting.UPDATE_REMINDERS, AiSetting.BILIBILI)
            .forEach { assertFalse(it.key, it in play) }
        assertTrue(AiSetting.TV_LAYOUT in play)
        assertEquals(AiSetting.entries.toList(), aiSettingsInBuild(playStore = false))
    }

    @Test
    fun anAnswerReportCarriesTheReasonAndAShortenedAnswer() {
        val long = "a".repeat(MAX_REPORTED_ANSWER_CHARS + 500)
        val body = aiAnswerReportBody("Wrong or misleading", long, "1.9.16")
        assertTrue(body, body.startsWith("Reason: Wrong or misleading\nApp version: 1.9.16\n\nAI answer:\n"))
        assertTrue(body.endsWith("a…"))
        assertTrue(body.length < MAX_REPORTED_ANSWER_CHARS + 100)
        val uri = aiAnswerReportEmailUri(aiAnswerReportBody("Something else", "Hi & bye", "1.9.16"), "1.9.16")
        assertTrue(uri, uri.startsWith("mailto:dualsubreplay.help@gmail.com?subject=DualSub%20Replay%20AI%20answer%20report"))
        assertTrue(uri, "Hi%20%26%20bye" in uri)
    }
}
