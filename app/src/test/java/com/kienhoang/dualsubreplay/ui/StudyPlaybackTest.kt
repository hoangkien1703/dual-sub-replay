package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val settings = StudySettings(playMode = StudyPlayMode.NEW_WORD_LINES)
        val asked = play(StudyPlaybackDriver(), settings, 900, 11_000, newWordLines = setOf(1))
        assertEquals(listOf(StudyAction.Pause), asked.map { it.second })
        assertEquals(5_000 - STUDY_PAUSE_LEAD_MS, asked.single().first, 33.0)
        // Without the known-word list the mode cannot tell, so the video plays normally.
        assertEquals(StudyPlayMode.NORMAL, settings.copy(knownWords = false).effectivePlayMode)
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
        val defaults = StudySettings()
        assertEquals(false, defaults.pageTokenCaptions)
        assertEquals(false, defaults.blurBox)
        assertEquals(true, defaults.knownWords)
    }

    private fun assertEquals(
        expected: Long,
        actual: Long,
        tolerance: Double,
    ) = org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), tolerance)
}
