package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CaptionDocumentParser
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleTimingSource
import com.kienhoang.dualsubreplay.data.SubtitleWord
import com.kienhoang.dualsubreplay.data.estimateWordTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeSyncUpgradeTest {
    @Test fun json3KeepsRealChunkStartsMarkedExact() {
        val json = """
            {"events":[{"tStartMs":1000,"dDurationMs":2000,"segs":[
              {"utf8":"hello there ","tOffsetMs":0},
              {"utf8":"friend","tOffsetMs":900}
            ]}]}
        """.trimIndent()

        val words = CaptionDocumentParser.parse(json).single().words

        assertEquals(SubtitleTimingSource.YOUTUBE_EXACT, words[0].timingSource)
        assertEquals(SubtitleTimingSource.ESTIMATED, words[1].timingSource)
        assertEquals(SubtitleTimingSource.YOUTUBE_EXACT, words[2].timingSource)
        assertEquals(1_900L, words[2].startMs)
    }

    @Test fun fallbackEstimatorGivesShortWordsMinimumTimeAndPunctuationPause() {
        val words = estimateWordTimings("I understand, completely.", 0, 2_000)

        assertEquals(3, words.size)
        assertTrue(words[0].endMs - words[0].startMs >= 60L)
        assertTrue(words[2].endMs - words[2].startMs > words[0].endMs - words[0].startMs)
        assertTrue(words.all { it.timingSource == SubtitleTimingSource.ESTIMATED })
        assertEquals(2_000L, words.last().endMs)
    }

    @Test fun frameBridgeParsesPlaybackAndCaptionMessages() {
        val playback = parseWebKaraokeSyncMessage(
            """{"type":"playback","url":"https://m.youtube.com/watch?v=abc","currentSecond":12.25}""",
        )
        val caption = parseWebKaraokeSyncMessage(
            """{"type":"caption","url":"https://m.youtube.com/watch?v=abc","currentSecond":12.3,"captionText":"hello world","previousCaptionText":"hello"}""",
        )

        assertEquals("playback", playback?.type)
        assertEquals(12.25f, playback?.currentSecond)
        assertEquals("caption", caption?.type)
        assertEquals("hello world", caption?.captionText)
        assertEquals("hello", caption?.previousCaptionText)
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("requestVideoFrameCallback"))
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("metadata.mediaTime"))
        assertTrue(WEB_KARAOKE_SYNC_SCRIPT.contains("MutationObserver"))
    }

    @Test fun bridgeOriginRulesRejectLookalikeDomains() {
        assertTrue(isTrustedYouTubeOrigin("https://m.youtube.com"))
        assertTrue(isTrustedYouTubeOrigin("https://youtube.com"))
        assertFalse(isTrustedYouTubeOrigin("https://youtube.com.evil.example"))
        assertFalse(isTrustedYouTubeOrigin("http://m.youtube.com"))
    }

    @Test fun domGrowthCanAdvanceEstimatedWordButNeverExactYouTubeAnchor() {
        val estimated = SubtitleSegment(
            id = 1,
            startMs = 0,
            endMs = 2_000,
            originalText = "hello brave new world",
            words = listOf(
                SubtitleWord("hello", 0, 500, SubtitleTimingSource.YOUTUBE_EXACT),
                SubtitleWord("brave", 500, 1_000, SubtitleTimingSource.ESTIMATED),
                SubtitleWord("new", 1_000, 1_400, SubtitleTimingSource.ESTIMATED),
                SubtitleWord("world", 1_400, 2_000, SubtitleTimingSource.ESTIMATED),
            ),
        )
        val observation = WebCaptionObservation(
            videoId = "abc",
            text = "hello brave",
            previousText = "hello",
            observedAtMs = 620,
        )

        assertEquals(1, domCaptionWordHint(estimated, observation, "abc", 620, 0))

        val exact = estimated.copy(
            words = estimated.words.mapIndexed { index, word ->
                if (index == 1) word.copy(timingSource = SubtitleTimingSource.YOUTUBE_EXACT) else word
            },
        )
        assertEquals(null, domCaptionWordHint(exact, observation, "abc", 620, 0))
    }

    @Test fun domFirstPhraseAndNonPrefixReplacementAreIgnored() {
        val segment = SubtitleSegment(
            id = 1,
            startMs = 0,
            endMs = 2_000,
            originalText = "hello brave world",
            words = estimateWordTimings("hello brave world", 0, 2_000),
        )
        assertEquals(
            null,
            domCaptionWordHint(
                segment,
                WebCaptionObservation("abc", "hello brave", null, 500),
                "abc",
                500,
                0,
            ),
        )
        assertEquals(
            null,
            domCaptionWordHint(
                segment,
                WebCaptionObservation("abc", "new window", "hello brave", 700),
                "abc",
                700,
                0,
            ),
        )
    }
}
