package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiSubtitleProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box over the video for a video YouTube has no captions for (Reddit report 2026-11-24): it said
 * "Live subtitles · Waiting for YouTube captions… Play the video with captions enabled", so the
 * viewer kept pressing CC on a video that has none.
 */
class NoCaptionsOverlayTest {
    private val noCaptions =
        DualSubUiState(
            activeVideoId = VIDEO,
            liveFallback = true,
            noCaptions = true,
            statusMessage = TestUiStrings.get(R.string.status_waiting_for_captions),
        )

    @Test fun theBoxSaysTheVideoHasNoCaptionsInsteadOfWaiting() {
        val content = learningOverlayContent(noCaptions, TestUiStrings)!!
        assertEquals(TestUiStrings.get(R.string.ai_subtitles_no_captions_body), content.statusText)
        assertNull(content.originalText)
        assertNull(content.translatedText)
    }

    @Test fun itOffersAiSubtitlesLikeThePanelCardWithoutTurningAnythingOn() {
        assertEquals(NoCaptionsOverlayAction.ADD_GEMINI_KEY, noCaptionsOverlayAction(noCaptions, geminiReady = false))
        assertEquals(NoCaptionsOverlayAction.MAKE_AI_SUBTITLES, noCaptionsOverlayAction(noCaptions, geminiReady = true))
        assertFalse("Retrying finds no captions either", showsOverlayRetry(noCaptions))
    }

    @Test fun aSongOffersItsFreeLyricsFirstAndAiOnlyAfterNoneMatched() {
        // A lyric video with the words burned in and no captions (owner's report 2026-11-25).
        assertEquals(NoCaptionsOverlayAction.GET_REAL_LYRICS, noCaptionsOverlayAction(noCaptions, geminiReady = true, song = true))
        assertEquals(NoCaptionsOverlayAction.GET_REAL_LYRICS, noCaptionsOverlayAction(noCaptions, geminiReady = false, song = true))
        val none = noCaptions.copy(aiSubtitles = AiSubtitleStatus(VIDEO, working = false, problem = AiSubtitleProblem.NO_FREE_LYRICS))
        assertEquals(NoCaptionsOverlayAction.MAKE_AI_SUBTITLES, noCaptionsOverlayAction(none, geminiReady = true, song = true))
        assertEquals(NoCaptionsOverlayAction.ADD_GEMINI_KEY, noCaptionsOverlayAction(none, geminiReady = false, song = true))
    }

    @Test fun whileCaptionsMayStillComeTheWaitingHintAndRetryStay() {
        val waiting = noCaptions.copy(noCaptions = false)
        assertEquals(
            TestUiStrings.get(R.string.player_live_subtitles_status, TestUiStrings.get(R.string.status_waiting_for_captions)),
            learningOverlayContent(waiting, TestUiStrings)!!.statusText,
        )
        assertNull(noCaptionsOverlayAction(waiting, geminiReady = true))
        assertTrue(showsOverlayRetry(waiting))
    }

    @Test fun captionsThePlayerShowsWinOverTheNoCaptionsLine() {
        val live = noCaptions.copy(liveOriginal = "Hello", liveTranslated = "Xin chào")
        assertEquals("Hello", learningOverlayContent(live, TestUiStrings)!!.originalText)
        assertNull(noCaptionsOverlayAction(live, geminiReady = true))
    }

    @Test fun whileAiSubtitlesAreMadeTheBoxShowsHowFarTheyAre() {
        val working = noCaptions.copy(aiSubtitles = AiSubtitleStatus(VIDEO, working = true))
        assertEquals(TestUiStrings.get(R.string.ai_subtitles_starting), learningOverlayContent(working, TestUiStrings)!!.statusText)
        assertNull(noCaptionsOverlayAction(working, geminiReady = true))
        val failed = noCaptions.copy(aiSubtitles = AiSubtitleStatus(VIDEO, working = false, problem = AiSubtitleProblem.LIMIT))
        assertEquals(TestUiStrings.get(R.string.ai_subtitles_problem_limit), learningOverlayContent(failed, TestUiStrings)!!.statusText)
        assertEquals(NoCaptionsOverlayAction.MAKE_AI_SUBTITLES, noCaptionsOverlayAction(failed, geminiReady = true))
        val otherVideo = noCaptions.copy(aiSubtitles = AiSubtitleStatus("zzzzzzzzzzz", working = true))
        assertEquals(
            TestUiStrings.get(R.string.ai_subtitles_no_captions_body),
            learningOverlayContent(otherVideo, TestUiStrings)!!.statusText,
        )
    }

    @Test fun liveStreamsAndBilibiliGetNoAiButton() {
        val stream = noCaptions.copy(liveStream = true)
        assertEquals(TestUiStrings.get(R.string.live_no_captions_title), learningOverlayContent(stream, TestUiStrings)!!.statusText)
        assertNull(noCaptionsOverlayAction(stream, geminiReady = true))
        val bilibili =
            noCaptions.copy(activeVideoId = "bili-BV1GJ411x7h7", statusMessage = TestUiStrings.get(R.string.bilibili_sign_in_title))
        assertEquals(TestUiStrings.get(R.string.bilibili_sign_in_title), learningOverlayContent(bilibili, TestUiStrings)!!.statusText)
        assertNull(noCaptionsOverlayAction(bilibili, geminiReady = true))
        assertTrue("Bilibili's Try again stays", showsOverlayRetry(bilibili))
    }

    private companion object {
        const val VIDEO = "yjsAuMiLc_A"
    }
}
