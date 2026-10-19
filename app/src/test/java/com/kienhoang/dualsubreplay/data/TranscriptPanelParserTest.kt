package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPanelParserTest {
    @Test fun readsThePanelsGetTranscriptReply() {
        val reply =
            """
            {"actions":[{"updateEngagementPanelAction":{"content":{"transcriptRenderer":{"content":{
              "transcriptSearchPanelRenderer":{"body":{"transcriptSegmentListRenderer":{"initialSegments":[
                {"transcriptSectionHeaderRenderer":{"startMs":"0","endMs":"5000","snippet":{"simpleText":"Intro"}}},
                {"transcriptSegmentRenderer":{"startMs":"2400","endMs":"4100",
                  "snippet":{"runs":[{"text":"But that is "},{"text":"exactly what"}]}}},
                {"transcriptSegmentRenderer":{"startMs":"0","endMs":"2400","snippet":{"runs":[{"text":"be new base\nmodels"}]}}}
              ]}}}}}}}}]}
            """.trimIndent()
        val cues = parseTranscriptPanel(reply)
        assertEquals(listOf("be new base models", "But that is exactly what"), cues.map { it.text })
        assertEquals(listOf(0L to 2400L, 2400L to 4100L), cues.map { it.startMs to it.endMs })
        assertTrue("The panel has no word timing", cues.all { it.words.isEmpty() })
    }

    @Test fun readsOlderCueReplies() {
        val reply =
            """{"actions":[{"transcriptCueGroupRenderer":{"cues":[{"transcriptCueRenderer":""" +
                """{"cue":{"simpleText":"Hello"},"startOffsetMs":"1000","durationMs":"1500"}}]}}]}"""
        assertEquals(listOf(RawCaptionCue(1000, 2500, "Hello")), parseTranscriptPanel(reply))
    }

    @Test fun shownLinesEndWhereTheNextBeginsOrAfterTenSeconds() {
        val shown =
            """{"panelLines":[{"startMs":61000,"text":"second"},{"startMs":0,"text":" first "},""" +
                """{"startMs":-1,"text":"bad"},{"startMs":5,"text":""}]}"""
        assertEquals(listOf(RawCaptionCue(0, 10_000, "first"), RawCaptionCue(61_000, 71_000, "second")), parseTranscriptPanel(shown))
    }

    @Test fun anythingElseHoldsNoLines() {
        assertEquals(emptyList<RawCaptionCue>(), parseTranscriptPanel("<html>sorry</html>"))
        assertEquals(emptyList<RawCaptionCue>(), parseTranscriptPanel("""{"responseContext":{}}"""))
    }
}
