package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeTimingTest {
    @Test
    fun automaticTimingUsesLiveProgressWhenAvailableAndTimestampsOtherwise() {
        val timed = KaraokePosition(1, 2)
        val live = KaraokePosition(2, 0)
        assertTrue(shouldCaptureLiveCaptions(true, true))
        assertFalse(shouldCaptureLiveCaptions(false, true))
        assertFalse(shouldCaptureLiveCaptions(true, false))
        assertEquals(live, effectiveKaraokePosition(true, true, timed, live))
        assertEquals(timed, effectiveKaraokePosition(true, true, timed, null))
        assertEquals(timed, effectiveKaraokePosition(false, true, timed, live))
        assertNull(effectiveKaraokePosition(true, false, timed, live))
    }

    @Test
    fun liveToTimestampFallbackCannotReturnToPreviousSentence() {
        val resolver = CaptionHighlightResolver()
        assertEquals(
            CaptionHighlightPosition(2, 0),
            resolver.resolve(true, true, 1, 3, KaraokePosition(2, 0)),
        )
        assertEquals(
            CaptionHighlightPosition(2, 0),
            resolver.resolve(true, true, 1, 3, null),
        )
    }

    @Test
    fun delayedLiveCaptionAndRegressiveWordMatchCannotMoveHighlightBackward() {
        val resolver = CaptionHighlightResolver()
        assertEquals(
            CaptionHighlightPosition(3, 2),
            resolver.resolve(true, true, 3, 2, null),
        )
        assertEquals(
            CaptionHighlightPosition(3, 2),
            resolver.resolve(true, true, 3, 3, KaraokePosition(2, 4)),
        )
        assertEquals(
            CaptionHighlightPosition(3, 2),
            resolver.resolve(true, true, 3, 1, KaraokePosition(3, 1)),
        )
    }

    @Test
    fun multiWordLiveUpdateFollowsTimestampsInsideTheRevealedRange() {
        val resolver = CaptionHighlightResolver()
        val live = KaraokePosition(3, 5, firstRevealedWordIndex = 2)
        assertEquals(CaptionHighlightPosition(3, 3), resolver.resolve(true, true, 3, 3, live, 1_000))
        assertEquals(CaptionHighlightPosition(3, 4), resolver.resolve(true, true, 3, 4, live, 1_200))
        // Timestamps outside the revealed words are bounded by the live range.
        assertEquals(CaptionHighlightPosition(3, 5), resolver.resolve(true, true, 3, 7, live, 1_400))
        val resolver2 = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(3, 2), resolver2.resolve(true, true, 3, 0, live, 0))
        assertEquals(CaptionHighlightPosition(3, 2), CaptionHighlightResolver().resolve(true, true, 2, 9, live, 0))
    }

    @Test
    fun oneWordLiveUpdateKeepsTheLiveWord() {
        assertEquals(
            CaptionHighlightPosition(2, 4),
            CaptionHighlightResolver().resolve(true, true, 2, 1, KaraokePosition(2, 4), 0),
        )
    }

    @Test
    fun wrongLiveWordMatchRecoversInsideTheSentenceAfterSustainedDisagreementOnly() {
        val resolver = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(5, 6), resolver.resolve(true, true, 5, 6, KaraokePosition(5, 6), 10_000))
        assertEquals(CaptionHighlightPosition(5, 6), resolver.resolve(true, true, 5, 2, KaraokePosition(5, 2), 10_100))
        assertEquals(CaptionHighlightPosition(5, 6), resolver.resolve(true, true, 5, 2, KaraokePosition(5, 2), 11_000))
        assertEquals(CaptionHighlightPosition(5, 2), resolver.resolve(true, true, 5, 2, KaraokePosition(5, 2), 11_100))
        // Catching up clears the timer, so a later single dip is held again.
        assertEquals(CaptionHighlightPosition(5, 3), resolver.resolve(true, true, 5, 3, KaraokePosition(5, 3), 11_200))
        assertEquals(CaptionHighlightPosition(5, 3), resolver.resolve(true, true, 5, 2, KaraokePosition(5, 2), 11_300))
    }

    @Test
    fun liveProgressAloneCannotPullTheHighlightBackToTheFirstWord() {
        // PR #76 phone report: after a reset, live progress restarts at word 0 while the
        // timestamps are still ahead. Without both signals agreeing, the highlight holds.
        val resolver = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(3, 5), resolver.resolve(true, true, 3, 5, KaraokePosition(3, 5), 10_000))
        listOf(10_100L, 11_000L, 12_500L, 15_000L).forEach { time ->
            assertEquals(CaptionHighlightPosition(3, 5), resolver.resolve(true, true, 3, 6, KaraokePosition(3, 0), time))
        }
        // Timestamps in the previous row do not count as agreement either.
        listOf(15_100L, 17_000L).forEach { time ->
            assertEquals(CaptionHighlightPosition(3, 5), resolver.resolve(true, true, 2, 4, KaraokePosition(3, 0), time))
        }
    }

    @Test
    fun windowShiftKeepsHoldingTheSameRow() {
        val resolver = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(6, 3), resolver.resolve(true, true, 6, 3, null, 30_000))
        resolver.shift(2)
        // Timestamps point at the overlapping previous row (was 5, now 3): the highlight stays.
        assertEquals(CaptionHighlightPosition(4, 3), resolver.resolve(true, true, 3, 7, null, 30_100))
        assertEquals(CaptionHighlightPosition(4, 4), resolver.resolve(true, true, 4, 4, null, 30_400))
    }

    @Test
    fun windowShiftPastTheHeldRowResets() {
        val resolver = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(1, 3), resolver.resolve(false, true, 1, 3, null))
        resolver.shift(2)
        assertEquals(CaptionHighlightPosition(0, 0), resolver.resolve(false, true, 0, 0, null))
    }

    @Test
    fun liveTrackerShiftKeepsProgressWithoutWarmUp() {
        val tracker = LiveCaptionTracker()
        val old = listOf(segment(0, 0, "we start here"), segment(1, 1_200, "you explain it kind now"))
        tracker.resolve(sample("you explain", 1, 1_600), old, 1, 1_600)
        assertEquals(KaraokePosition(1, 2), tracker.resolve(sample("you explain it", 2, 2_000), old, 1, 2_000))
        tracker.shift(1)
        val slid = old.drop(1)
        // The next revision is emitted at once, in the shifted window, continuing forward.
        assertEquals(KaraokePosition(0, 3), tracker.resolve(sample("you explain it kind", 3, 2_400), slid, 0, 2_400))
    }

    @Test
    fun windowShiftFindsTheNewFirstRowInThePreviousWindow() {
        val previous = listOf(segment(10, 0, "a"), segment(11, 1_000, "b"), segment(12, 2_000, "c"))
        assertEquals(0, windowShift(previous, previous))
        assertEquals(2, windowShift(previous, listOf(segment(12, 2_000, "c"), segment(13, 3_000, "d"))))
        assertNull(windowShift(previous, listOf(segment(9, 0, "z"), segment(10, 0, "a"))))
        assertNull(windowShift(emptyList(), previous))
        assertNull(windowShift(previous, emptyList()))
    }

    @Test
    fun highlightNeverReturnsToThePreviousSentenceWithoutASeek() {
        // Phone report: live captions vanish between lines while timestamps still point at the
        // previous sentence. Neither the timestamp fallback nor a stale live match may go back.
        val resolver = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(6, 0), resolver.resolve(true, true, 5, 9, KaraokePosition(6, 0), 20_000))
        listOf(20_100L, 21_500L, 23_000L, 26_000L).forEach { time ->
            assertEquals(CaptionHighlightPosition(6, 0), resolver.resolve(true, true, 5, 9, null, time))
            assertEquals(CaptionHighlightPosition(6, 0), resolver.resolve(true, true, 5, 9, KaraokePosition(5, 9), time + 50))
        }
        assertEquals(CaptionHighlightPosition(5, 9), CaptionHighlightResolver().resolve(true, true, 5, 9, null, 26_100))
    }

    @Test
    fun timestampOnlyHighlightNeverMovesBackwardInsideASentence() {
        val resolver = CaptionHighlightResolver()
        assertEquals(CaptionHighlightPosition(4, 5), resolver.resolve(false, true, 4, 5, null, 0))
        assertEquals(CaptionHighlightPosition(4, 5), resolver.resolve(false, true, 4, 2, null, 5_000))
    }

    @Test
    fun liveProgressRecordsEveryWordRevealedByOneUpdate() {
        val first = reconcileLiveCaptionProgress(null, sample("look", 1, 0))
        val grown = reconcileLiveCaptionProgress(first, sample("look at this now", 2, 300))!!
        assertEquals(3, grown.activeWordIndex)
        assertEquals(1, grown.firstAppendedIndex)
        val rolled = reconcileLiveCaptionProgress(grown, sample("this now it works", 3, 600))!!
        assertEquals(3, rolled.activeWordIndex)
        assertEquals(2, rolled.firstAppendedIndex)
    }

    @Test
    fun genuineGapClearsHighlightAndResetAllowsIntentionalReplay() {
        val resolver = CaptionHighlightResolver()
        assertEquals(
            CaptionHighlightPosition(4, 1),
            resolver.resolve(false, true, 4, 1, null),
        )
        assertNull(resolver.resolve(false, true, -1, -1, null))
        resolver.reset()
        assertEquals(
            CaptionHighlightPosition(1, 0),
            resolver.resolve(false, true, 1, 0, null),
        )
    }

    @Test
    fun rollingCaptionProgressNeverFlashesBackToTheFirstWord() {
        val first = reconcileLiveCaptionProgress(
            null,
            sample("You explain it kind of", revision = 1, mediaTimeMs = 20_000),
        )!!
        assertEquals(0, first.activeWordIndex)

        val grown = reconcileLiveCaptionProgress(
            first,
            sample("You explain it kind of now", revision = 2, mediaTimeMs = 20_300),
        )!!
        assertEquals(5, grown.activeWordIndex)

        val rolled = reconcileLiveCaptionProgress(
            grown,
            sample("it kind of now please", revision = 3, mediaTimeMs = 20_600),
        )!!
        assertEquals(4, rolled.activeWordIndex)

        val shrunk = reconcileLiveCaptionProgress(
            rolled,
            sample("kind of now please", revision = 4, mediaTimeMs = 20_900),
        )!!
        assertEquals(3, shrunk.activeWordIndex)
    }

    @Test
    fun unrelatedCaptionStartsAtItsFirstWordAndPunctuationIsNormalized() {
        val old = reconcileLiveCaptionProgress(
            null,
            sample("We're ready!", revision = 1, mediaTimeMs = 1_000),
        )!!
        val fresh = reconcileLiveCaptionProgress(
            old,
            sample("Completely new sentence.", revision = 2, mediaTimeMs = 2_000),
        )!!

        assertEquals(listOf("completely", "new", "sentence"), fresh.tokens)
        assertEquals(0, fresh.activeWordIndex)
        assertEquals(listOf("we're", "ready"), karaokeTokens("WE'RE ready!"))
    }

    @Test
    fun liveWordMapsAcrossSplitSegmentBoundary() {
        val segments = listOf(
            segment(0, 0, "We are"),
            segment(1, 1_000, "really ready"),
        )

        assertEquals(
            KaraokePosition(1, 0),
            mapLiveCaptionWord(
                segments = segments,
                referenceSegmentIndex = 0,
                liveTokens = karaokeTokens("are really"),
                liveActiveWordIndex = 1,
            ),
        )
    }

    @Test
    fun repeatedWordUsesContextAndPreviousForwardPosition() {
        val segments = listOf(segment(0, 0, "go now go home"))

        assertEquals(
            KaraokePosition(0, 2),
            mapLiveCaptionWord(
                segments = segments,
                referenceSegmentIndex = 0,
                liveTokens = karaokeTokens("go home"),
                liveActiveWordIndex = 0,
                previousPosition = KaraokePosition(0, 2),
            ),
        )
    }

    @Test
    fun adaptiveNeedsTwoCoherentRevisionsAndFallsBackWhenStale() {
        val tracker = LiveCaptionTracker()
        val segments = listOf(segment(0, 0, "you explain it kind now"))

        assertNull(
            tracker.resolve(
                sample = sample("you explain", 1, 1_000),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 1_000,
            ),
        )
        assertEquals(
            KaraokePosition(0, 2),
            tracker.resolve(
                sample = sample("you explain it", 2, 1_300),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 1_300,
            ),
        )
        assertNull(
            tracker.resolve(
                sample = sample("you explain it", 2, 1_300),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 3_301,
            ),
        )
        assertNull(
            tracker.resolve(
                sample = sample("it kind", 3, 3_400),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 3_400,
            ),
        )
        assertEquals(
            KaraokePosition(0, 4),
            tracker.resolve(
                sample = sample("it kind now", 4, 3_700),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 3_700,
            ),
        )
    }

    private fun sample(text: String, revision: Long, mediaTimeMs: Long) = LiveCaptionSample(
        text = text,
        revision = revision,
        mediaTimeMs = mediaTimeMs,
        present = text.isNotBlank(),
    )

    private fun segment(id: Long, startMs: Long, text: String): SubtitleSegment {
        val tokens = text.split(' ')
        val duration = tokens.size * 400L
        return SubtitleSegment(
            id = id,
            startMs = startMs,
            endMs = startMs + duration,
            originalText = text,
            words = tokens.mapIndexed { index, token ->
                SubtitleWord(
                    text = token,
                    startMs = startMs + index * 400L,
                    endMs = startMs + (index + 1) * 400L,
                )
            },
        )
    }
}
