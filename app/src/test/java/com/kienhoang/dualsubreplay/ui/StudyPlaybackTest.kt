package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.PageTokenMode
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlaybackTest {
    private val lines =
        listOf(
            SubtitleSegment(1, 1_000, 3_000, "first"),
            SubtitleSegment(2, 3_500, 5_000, "second"),
            SubtitleSegment(3, 9_000, 10_000, "third"),
        )

    /** Plays [driver] from [fromMs] to [toMs] in 33 ms ticks, as the page reports them, and returns what it asked for. */
    private fun play(
        driver: StudyPlaybackDriver,
        settings: StudySettings,
        fromMs: Long,
        toMs: Long,
        session: String = "s1",
        newWordLines: Set<Int> = emptySet(),
        active: Boolean = true,
    ): List<Pair<Long, StudyAction>> {
        val asked = mutableListOf<Pair<Long, StudyAction>>()
        var time = fromMs
        while (time <= toMs) {
            driver.tick(StudyTick("abcdefghijk", time, session, false, lines, settings, newWordLines, active))?.let { asked += time to it }
            time += 33
        }
        return asked
    }

    private fun tick(
        driver: StudyPlaybackDriver,
        settings: StudySettings,
        timeMs: Long,
        session: String = "s1",
        paused: Boolean = false,
        videoId: String = "abcdefghijk",
    ): StudyAction? = driver.tick(StudyTick(videoId, timeMs, session, paused, lines, settings))

    @Test
    fun normalPlayAsksForNothing() {
        assertEquals(emptyList<Pair<Long, StudyAction>>(), play(StudyPlaybackDriver(), StudySettings(), 0, 11_000))
    }

    @Test
    fun pauseAfterLinePausesOnceJustBeforeEachLineEnds() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.PAUSE_AFTER_LINE)
        val asked = play(driver, settings, 900, 11_000)
        assertEquals(listOf(StudyAction.Pause, StudyAction.Pause, StudyAction.Pause), asked.map { it.second })
        assertEquals(3_000 - STUDY_PAUSE_LEAD_MS, asked[0].first, 33.0)
        assertEquals(5_000 - STUDY_PAUSE_LEAD_MS, asked[1].first, 33.0)
        // Playing on after the pause goes to the next line instead of pausing again.
        val again = StudyPlaybackDriver()
        assertEquals(StudyAction.Pause, play(again, settings, 900, 2_913).last().second)
        assertNull(tick(again, settings, 2_950))
    }

    @Test
    fun aSeekTheUserMadeStartsAgainWithoutPausingFirst() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.PAUSE_AFTER_LINE)
        play(driver, settings, 900, 3_000)
        // Tapping the first line again plays it, and pauses at its end once more.
        val again = play(driver, settings, 1_000, 3_000, session = "s2")
        assertEquals(listOf(StudyAction.Pause), again.map { it.second })
        // Landing near a line's end does not pause straight away.
        assertEquals(emptyList<Pair<Long, StudyAction>>(), play(driver, settings, 4_950, 5_000, session = "s3"))
    }

    @Test
    fun repeatEachLinePlaysALineTheChosenNumberOfTimes() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = 3)
        assertEquals(listOf(StudyAction.PlayFrom(1_000)), play(driver, settings, 900, 3_000).map { it.second })
        // The driver's own seek lands as a new page session: the line plays again.
        assertEquals(listOf(StudyAction.PlayFrom(1_000)), play(driver, settings, 1_000, 3_000, session = "s2").map { it.second })
        assertEquals(emptyList<StudyAction>(), play(driver, settings, 1_000, 3_400, session = "s3").map { it.second })
        // The next line starts its own count.
        assertEquals(listOf(StudyAction.PlayFrom(3_500)), play(driver, settings, 3_500, 5_000, session = "s3").map { it.second })
    }

    @Test
    fun foreverLoopsALineUntilTheLearnerMovesOn() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = STUDY_REPEAT_FOREVER)
        assertEquals(listOf(StudyAction.PlayFrom(1_000)), play(driver, settings, 900, 3_000).map { it.second })
        // Far past any count: each of the driver's own seeks plays the line once more.
        repeat(8) { round ->
            val session = "loop$round"
            assertEquals(listOf(StudyAction.PlayFrom(1_000)), play(driver, settings, 1_000, 3_000, session = session).map { it.second })
        }
        // A tap on another line (a seek the driver did not ask for) loops that line instead.
        assertEquals(listOf(StudyAction.PlayFrom(3_500)), play(driver, settings, 3_500, 5_000, session = "tap").map { it.second })
    }

    @Test
    fun tappingTheNextLineNeverTakesTheVideoBackToTheOldOne() {
        // Back-to-back lines, as YouTube's auto captions often are.
        val joined = listOf(SubtitleSegment(1, 1_000, 3_000, "first"), SubtitleSegment(2, 3_000, 5_000, "second"))
        for (count in listOf(2, STUDY_REPEAT_FOREVER)) {
            val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = count)
            val driver = StudyPlaybackDriver()

            fun at(
                timeMs: Long,
                session: String,
            ) = driver.tick(StudyTick("abcdefghijk", timeMs, session, false, joined, settings))
            var time = 900L
            while (time <= 2_500) {
                assertNull(at(time, "s1"))
                time += 33
            }
            driver.learnerSeek(3_000)
            // A tick from before the seek, then the page reporting the new time before its seek event.
            assertNull(at(2_533, "s1"))
            assertNull("count $count", at(3_000, "s1"))
            assertNull(at(3_033, "s2"))
            // The tapped line is the one that repeats.
            val asked = mutableListOf<StudyAction>()
            time = 3_066
            while (time <= 5_000) {
                at(time, "s2")?.let { asked += it }
                time += 33
            }
            assertEquals("count $count", listOf(StudyAction.PlayFrom(3_000)), asked)
        }
    }

    @Test
    fun aCountedRepeatMovesOnWhenTheSeekLandsTwiceOrEarly() {
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = 2)
        val driver = StudyPlaybackDriver()
        // Line two ends: it plays once more.
        assertEquals(listOf(StudyAction.PlayFrom(3_500)), play(driver, settings, 3_400, 5_000).map { it.second })
        // The seek lands 200 ms early, in the gap after line one, and the page reports it as two sessions.
        assertNull(tick(driver, settings, 3_300, session = "s2"))
        assertNull(tick(driver, settings, 3_333, session = "s3"))
        // Line two plays its second time, then the video goes on to line three: no third play.
        assertEquals(emptyList<StudyAction>(), play(driver, settings, 3_366, 9_500, session = "s3").map { it.second })
    }

    @Test
    fun aSeekLandingEarlyInTheLineBeforeDoesNotRepeatThatLine() {
        val joined = listOf(SubtitleSegment(1, 1_000, 3_000, "first"), SubtitleSegment(2, 3_000, 5_000, "second"))
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = 3)
        val driver = StudyPlaybackDriver()
        // Line one plays; the learner taps line two.
        assertNull(driver.tick(StudyTick("abcdefghijk", 1_500, "s1", false, joined, settings)))
        driver.learnerSeek(3_000)
        val asked = mutableListOf<StudyAction>()
        // The tapped line's seek lands 250 ms early, inside line one.
        var time = 2_750L
        while (time <= 5_000) {
            driver.tick(StudyTick("abcdefghijk", time, "s2", false, joined, settings))?.let { asked += it }
            time += 33
        }
        assertEquals(listOf(StudyAction.PlayFrom(3_000)), asked)
    }

    @Test
    fun aSlidingTranscriptWindowNeitherLoopsACountNorGoesBackALine() {
        // The segments are a window that slides with playback, also back after a repeat: the same
        // line has another index from one tick to the next (seen on a real video, 2026-11-04).
        val joined =
            listOf(
                SubtitleSegment(1, 1_000, 3_000, "first"),
                SubtitleSegment(2, 3_000, 5_000, "second"),
                SubtitleSegment(3, 5_000, 7_000, "third"),
            )
        val settings = StudySettings(playMode = StudyPlayMode.REPEAT_LINE, repeatCount = 2)
        val driver = StudyPlaybackDriver()
        var slid = false

        fun from(
            fromMs: Long,
            toMs: Long,
            session: String,
        ): List<StudyAction> {
            val asked = mutableListOf<StudyAction>()
            var time = fromMs
            while (time <= toMs) {
                slid = !slid
                val window = if (slid) joined.drop(1) else joined
                driver.tick(StudyTick("abcdefghijk", time, session, false, window, settings))?.let { asked += it }
                time += 33
            }
            return asked
        }
        driver.learnerSeek(3_000)
        assertEquals(listOf(StudyAction.PlayFrom(3_000)), from(3_000, 5_000, "s2"))
        // The second play goes on to the next line, which gets its own two plays.
        assertEquals(listOf(StudyAction.PlayFrom(5_000)), from(3_000, 7_000, "s3"))
        assertEquals(emptyList<StudyAction>(), from(5_000, 7_500, "s4"))
    }

    @Test
    fun readFirstStopsAtTheStartOfEachLine() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.READ_FIRST)
        val start = play(driver, settings, 0, 1_000)
        assertEquals(listOf(StudyAction.PauseAt(1_000 + STUDY_READ_OFFSET_MS)), start.map { it.second })
        // Played on, the line's end stops at the next line's start.
        val next = play(driver, settings, 1_020, 3_000, session = "s2")
        assertEquals(listOf(StudyAction.PauseAt(3_500 + STUDY_READ_OFFSET_MS)), next.map { it.second })
        // The last line has no next one, so the video pauses at its end.
        val last = play(driver, settings, 9_020, 10_000, session = "s3")
        assertEquals(listOf(StudyAction.Pause), last.map { it.second })
    }

    @Test
    fun listenFirstPausesAfterEachLineAndHidesSubtitlesWhilePlaying() {
        val settings = StudySettings(playMode = StudyPlayMode.LISTEN_FIRST)
        assertEquals(StudyAction.Pause, play(StudyPlaybackDriver(), settings, 900, 3_000).single().second)
        assertEquals(CaptionVisibility.PAUSED, studyCaptionVisibility(CaptionVisibility.ALWAYS, StudyPlayMode.LISTEN_FIRST))
        assertEquals(CaptionVisibility.NEVER, studyCaptionVisibility(CaptionVisibility.NEVER, StudyPlayMode.LISTEN_FIRST))
        assertEquals(CaptionVisibility.ALWAYS, studyCaptionVisibility(CaptionVisibility.ALWAYS, StudyPlayMode.PAUSE_AFTER_LINE))
    }

    @Test
    fun newWordLinesPausesOnlyOnLinesWithOneNewWord() {
        val settings = StudySettings(playMode = StudyPlayMode.NEW_WORD_LINES, knownWordsMode = KnownWordsMode.MANUAL)
        val asked = play(StudyPlaybackDriver(), settings, 900, 11_000, newWordLines = setOf(1))
        assertEquals(listOf(StudyAction.Pause), asked.map { it.second })
        assertEquals(5_000 - STUDY_PAUSE_LEAD_MS, asked.single().first, 33.0)
        // Without the known-word list the mode cannot tell, so the video plays normally.
        assertEquals(StudyPlayMode.NORMAL, settings.copy(knownWordsMode = KnownWordsMode.OFF).effectivePlayMode)
    }

    @Test
    fun skipSilenceSpeedsUpLongGapsAndBringsTheSpeedBackBeforeTheNextLine() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(skipSilence = SkipSilence.DOUBLE)
        val asked = play(driver, settings, 3_500, 9_000).map { it.second }
        // The 4 s gap after the second line plays at 2x until just before the third (first tick in it: 5018 ms).
        assertEquals(StudyAction.GapSpeed(2f, (9_000L - 5_018) / 2), asked.first())
        assertEquals(StudyAction.NormalSpeed, asked.last())
        assertEquals(2, asked.size)
    }

    @Test
    fun skipSilenceGivesTheSpeedBackWhenPausedOrTurnedOff() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(skipSilence = SkipSilence.QUADRUPLE)
        assertEquals(StudyAction.GapSpeed(4f, 1_000), tick(driver, settings, 5_000))
        assertEquals(StudyAction.NormalSpeed, tick(driver, settings, 5_100, paused = true))
        assertEquals(StudyAction.GapSpeed(4f, 975), tick(driver, settings, 5_100))
        assertEquals(StudyAction.NormalSpeed, tick(driver, StudySettings(), 5_200))
        assertEquals(StudyAction.GapSpeed(4f, 950), tick(driver, settings, 5_200))
        assertEquals(StudyAction.NormalSpeed, tick(driver, settings, 5_300, videoId = "zzzzzzzzzzz"))
    }

    @Test
    fun jumpSkipsALongGapOnceAndLandsJustBeforeTheNextLine() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(skipSilence = SkipSilence.JUMP)
        val asked = play(driver, settings, 0, 9_000).map { it.second }
        assertEquals(listOf(StudyAction.PlayFrom(9_000 - SKIP_SILENCE_JUMP_LEAD_MS)), asked)
        // Before the first line only 1 s is silent, under the shortest gap skip silence acts on.
        assertNull(tick(StudyPlaybackDriver(), settings, 0))
    }

    @Test
    fun nothingHappensForLiveSubtitlesClipsOrAPausedVideo() {
        val settings = StudySettings(playMode = StudyPlayMode.PAUSE_AFTER_LINE, skipSilence = SkipSilence.JUMP)
        assertEquals(emptyList<Pair<Long, StudyAction>>(), play(StudyPlaybackDriver(), settings, 0, 11_000, active = false))
        val driver = StudyPlaybackDriver()
        assertNull(tick(driver, settings, 2_950, paused = true))
    }

    @Test
    fun linesAreFoundByTheirStartAndEndBeforeTheNextOne() {
        assertEquals(-1, lineAtOrBefore(lines, 999))
        assertEquals(0, lineAtOrBefore(lines, 1_000))
        assertEquals(0, lineAtOrBefore(lines, 3_200))
        assertEquals(2, lineAtOrBefore(lines, 20_000))
        val overlapping = listOf(SubtitleSegment(1, 0, 2_000, "a"), SubtitleSegment(2, 1_500, 3_000, "b"))
        assertEquals(1_500, lineBoundary(overlapping, 0))
        assertEquals(3_000, lineBoundary(overlapping, 1))
    }

    @Test
    fun storedStudySettingsFallBackToTheirDefaults() {
        assertEquals(StudyPlayMode.LISTEN_FIRST, storedStudyPlayMode("listen_first"))
        assertEquals(StudyPlayMode.NORMAL, storedStudyPlayMode("shadowing"))
        assertEquals(StudyPlayMode.NORMAL, storedStudyPlayMode(null))
        assertEquals(SkipSilence.JUMP, storedSkipSilence("jump"))
        assertEquals(SkipSilence.OFF, storedSkipSilence("8x"))
        assertEquals(5, storedStudyRepeatCount(5))
        assertEquals(DEFAULT_STUDY_REPEAT_COUNT, storedStudyRepeatCount(4))
        assertEquals(STUDY_REPEAT_FOREVER, storedStudyRepeatCount(STUDY_REPEAT_FOREVER))
        assertEquals(STUDY_REPEAT_FOREVER, aiStudyRepeatCount("forever"))
        assertEquals(5, aiStudyRepeatCount("5"))
        assertEquals("Unknown text keeps the default, not Forever", DEFAULT_STUDY_REPEAT_COUNT, aiStudyRepeatCount("lots"))
        val defaults = StudySettings()
        assertEquals(PageTokenMode.AUTO, defaults.pageTokenCaptions)
        assertEquals(false, defaults.blurBox)
        assertTrue("Kanji details start on", defaults.kanjiDetails)
        assertEquals("Off until the user turns it on", KnownWordsMode.OFF, defaults.knownWordsMode)
        assertEquals(10, defaults.knownWordsAfter)
        assertFalse(defaults.knownWords)
        assertTrue(defaults.copy(knownWordsMode = KnownWordsMode.AUTO).knownWords)
        assertEquals(KnownWordsMode.MANUAL, storedKnownWordsMode("manual"))
        assertEquals(KnownWordsMode.OFF, storedKnownWordsMode(null))
        assertTrue(shouldOfferKnownWords(defaults, offeredBefore = false))
        assertFalse("Offered once", shouldOfferKnownWords(defaults, offeredBefore = true))
        assertFalse(shouldOfferKnownWords(defaults.copy(knownWordsMode = KnownWordsMode.MANUAL), offeredBefore = false))
        assertEquals(20, storedKnownWordsAfter(20))
        assertEquals(DEFAULT_KNOWN_WORDS_AFTER, storedKnownWordsAfter(7))
    }

    private fun assertEquals(
        expected: Long,
        actual: Long,
        tolerance: Double,
    ) = org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), tolerance)

    /** Like [play], with the loop button on: [loop] is the transcript panel's, following the driver's moves. */
    private fun playLooping(
        driver: StudyPlaybackDriver,
        fromMs: Long,
        toMs: Long,
        session: String,
        settings: StudySettings = StudySettings(loopButton = true),
        loop: LineLoop = LineLoop().apply { lineId = 1 },
        segments: List<SubtitleSegment> = lines,
        active: Boolean = true,
    ): List<Pair<Long, StudyAction>> {
        val asked = mutableListOf<Pair<Long, StudyAction>>()
        var time = fromMs
        while (time <= toMs) {
            driver
                .tick(StudyTick("abcdefghijk", time, session, false, segments, settings, active = active, loopLineId = loop.lineId))
                ?.let { asked += time to it }
            driver.takeLoopMove()?.let(loop::follow)
            time += 33
        }
        return asked
    }

    @Test
    fun loopButtonPlaysTheLineAgainAndAgain() {
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        repeat(3) { round ->
            val asked = playLooping(driver, if (round == 0) 900 else 1_000, 3_100, session = "s$round", loop = loop)
            assertEquals(listOf(StudyAction.PlayFrom(1_000)), asked.map { it.second })
            assertEquals(3_000 - STUDY_PAUSE_LEAD_MS, asked.single().first, 33.0)
        }
        assertEquals(1L, loop.lineId)
    }

    @Test
    fun loopButtonWinsOverThePlayMode() {
        val driver = StudyPlaybackDriver()
        val settings = StudySettings(playMode = StudyPlayMode.PAUSE_AFTER_LINE, loopButton = true)
        assertEquals(listOf(StudyAction.PlayFrom(1_000)), playLooping(driver, 900, 3_100, "s1", settings).map { it.second })
    }

    @Test
    fun aSeekElsewhereTakesTheLoopAlong() {
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        playLooping(driver, 900, 2_000, "s1", loop = loop)
        // The learner drags YouTube's bar into the third line: that line loops now.
        assertEquals(
            listOf(StudyAction.PlayFrom(9_000)),
            playLooping(driver, 9_100, 10_000, "s2", loop = loop).map { it.second },
        )
        assertEquals(3L, loop.lineId)
        // Dragged into the gap before the third line: the third line is the next to loop.
        playLooping(driver, 6_000, 6_100, "s3", loop = loop)
        assertEquals(3L, loop.lineId)
    }

    @Test
    fun aLongSeekWaitsForTheLinesToCatchUp() {
        // The lines are a window that slides with playback: right after a long seek it still holds the old place.
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        val oldWindow = lines.take(2)
        playLooping(driver, 900, 2_000, "s1", loop = loop, segments = oldWindow)
        assertEquals(emptyList<Pair<Long, StudyAction>>(), playLooping(driver, 9_100, 9_300, "s2", loop = loop, segments = oldWindow))
        assertEquals(1L, loop.lineId)
        assertEquals(
            listOf(StudyAction.PlayFrom(9_000)),
            playLooping(driver, 9_333, 10_000, "s2", loop = loop, segments = lines.drop(1)).map { it.second },
        )
        assertEquals(3L, loop.lineId)
    }

    @Test
    fun aSeekPastTheLastLineKeepsTheLoopWaiting() {
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        playLooping(driver, 900, 2_000, "s1", loop = loop)
        assertEquals(emptyList<Pair<Long, StudyAction>>(), playLooping(driver, 12_000, 13_000, "s2", loop = loop))
        assertEquals(1L, loop.lineId)
        // Tapping a line from there loops that line.
        driver.learnerSeek(3_500)
        assertEquals(listOf(StudyAction.PlayFrom(3_500)), playLooping(driver, 3_500, 5_100, "s3", loop = loop).map { it.second })
        assertEquals(2L, loop.lineId)
    }

    @Test
    fun anAdLeavesTheLoopAloneAndTheLoopFollowsTheVideoAfterIt() {
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        playLooping(driver, 900, 2_000, "s1", loop = loop)
        // The ad plays from 0 s on the page's clock, past the first line's end: nothing is asked of it.
        assertEquals(emptyList<Pair<Long, StudyAction>>(), playLooping(driver, 0, 5_000, "ad", loop = loop, active = false))
        assertEquals(1L, loop.lineId)
        // The video comes back where the learner had sought to: the third line loops.
        assertEquals(listOf(StudyAction.PlayFrom(9_000)), playLooping(driver, 9_100, 10_000, "s2", loop = loop).map { it.second })
        assertEquals(3L, loop.lineId)
    }

    @Test
    fun tappingAnotherLineLoopsThatLine() {
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        playLooping(driver, 900, 2_000, "s1", loop = loop)
        driver.learnerSeek(1_000)
        assertEquals(listOf(StudyAction.PlayFrom(1_000)), playLooping(driver, 1_000, 3_100, "s2", loop = loop).map { it.second })
        assertEquals(1L, loop.lineId)

        driver.learnerSeek(3_500)
        assertEquals(listOf(StudyAction.PlayFrom(3_500)), playLooping(driver, 3_500, 5_100, "s3", loop = loop).map { it.second })
        assertEquals(2L, loop.lineId)
    }

    @Test
    fun tappingTheLoopButtonAgainEndsTheLoop() {
        val driver = StudyPlaybackDriver()
        val loop = LineLoop().apply { lineId = 1 }
        playLooping(driver, 900, 2_000, "s1", loop = loop)
        loop.toggle(1)
        assertEquals(emptyList<Pair<Long, StudyAction>>(), playLooping(driver, 2_033, 3_400, "s1", loop = loop))
        assertNull(loop.lineId)
    }

    @Test
    fun anotherVideoEndsTheLoop() {
        val driver = StudyPlaybackDriver()
        playLooping(driver, 900, 2_000, "s1")
        driver.tick(StudyTick("lmnopqrstuv", 500, "s1", false, lines, StudySettings(loopButton = true), loopLineId = 1))
        assertEquals(LoopMove(1, null), driver.takeLoopMove())
        assertNull(driver.takeLoopMove())
    }

    @Test
    fun theLoopFindsTheLineAtATime() {
        assertEquals(1L, loopLineAt(lines, 960)?.id)
        assertEquals(1L, loopLineAt(lines, 2_999)?.id)
        assertEquals(2L, loopLineAt(lines, 3_200)?.id)
        assertEquals(3L, loopLineAt(lines, 6_000)?.id)
        assertNull(loopLineAt(lines, 10_000))
        assertEquals(1L, loopLineAt(lines, 0)?.id)
    }
}
