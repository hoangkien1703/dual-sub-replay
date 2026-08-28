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
    fun timingModeStorageDefaultsToAdaptive() {
        assertEquals(KaraokeTimingMode.ADAPTIVE, storedKaraokeTimingMode(null))
        assertEquals(KaraokeTimingMode.ADAPTIVE, storedKaraokeTimingMode("unknown"))
        assertEquals(KaraokeTimingMode.YOUTUBE_LIVE, storedKaraokeTimingMode("youtube_live"))
        assertEquals(KaraokeTimingMode.TRANSCRIPT, storedKaraokeTimingMode("transcript"))
        assertTrue(RESETTABLE_SETTING_KEYS.contains(KARAOKE_TIMING_MODE_PREFERENCE))
    }

    @Test
    fun liveCaptureRunsOnlyForGeneratedHighlightedTracks() {
        assertTrue(shouldCaptureLiveCaptions(KaraokeTimingMode.ADAPTIVE, true, true))
        assertTrue(shouldCaptureLiveCaptions(KaraokeTimingMode.YOUTUBE_LIVE, true, true))
        assertFalse(shouldCaptureLiveCaptions(KaraokeTimingMode.TRANSCRIPT, true, true))
        assertFalse(shouldCaptureLiveCaptions(KaraokeTimingMode.ADAPTIVE, false, true))
        assertFalse(shouldCaptureLiveCaptions(KaraokeTimingMode.ADAPTIVE, true, false))
    }

    @Test
    fun effectiveModeKeepsManualCaptionsOnTranscriptTiming() {
        val timed = KaraokePosition(1, 2)
        val live = KaraokePosition(2, 0)

        assertEquals(
            timed,
            effectiveKaraokePosition(KaraokeTimingMode.YOUTUBE_LIVE, false, true, timed, live),
        )
        assertEquals(
            live,
            effectiveKaraokePosition(KaraokeTimingMode.ADAPTIVE, true, true, timed, live),
        )
        assertEquals(
            timed,
            effectiveKaraokePosition(KaraokeTimingMode.ADAPTIVE, true, true, timed, null),
        )
        assertNull(
            effectiveKaraokePosition(KaraokeTimingMode.YOUTUBE_LIVE, true, true, timed, null),
        )
        assertEquals(
            timed,
            effectiveKaraokePosition(KaraokeTimingMode.TRANSCRIPT, true, true, timed, live),
        )
        assertNull(
            effectiveKaraokePosition(KaraokeTimingMode.ADAPTIVE, true, false, timed, live),
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
                strict = false,
            ),
        )
        assertEquals(
            KaraokePosition(0, 2),
            tracker.resolve(
                sample = sample("you explain it", 2, 1_300),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 1_300,
                strict = false,
            ),
        )
        assertNull(
            tracker.resolve(
                sample = sample("you explain it", 2, 1_300),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 3_301,
                strict = false,
            ),
        )
        assertNull(
            tracker.resolve(
                sample = sample("it kind", 3, 3_400),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 3_400,
                strict = false,
            ),
        )
        assertEquals(
            KaraokePosition(0, 4),
            tracker.resolve(
                sample = sample("it kind now", 4, 3_700),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 3_700,
                strict = false,
            ),
        )
    }

    @Test
    fun strictLiveAcceptsFirstMappingButNeverUsesMissingSignal() {
        val tracker = LiveCaptionTracker()
        val segments = listOf(segment(0, 0, "strict live words"))

        assertEquals(
            KaraokePosition(0, 0),
            tracker.resolve(
                sample = sample("strict live", 1, 500),
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 500,
                strict = true,
            ),
        )
        assertNull(
            tracker.resolve(
                sample = null,
                segments = segments,
                referenceSegmentIndex = 0,
                playbackTimeMs = 600,
                strict = true,
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
