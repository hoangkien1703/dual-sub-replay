package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionDocumentParserTest {
    @Test fun parsesLegacyTimedTextXml() {
        val xml = """
            <transcript>
              <text start="1.5" dur="2.25">Hello &amp; welcome</text>
            </transcript>
        """.trimIndent()

        val cues = CaptionDocumentParser.parse(xml)

        assertEquals(1, cues.size)
        assertEquals(1_500L, cues.single().startMs)
        assertEquals(3_750L, cues.single().endMs)
        assertEquals("Hello & welcome", cues.single().text)
    }

    @Test fun parsesSrv3NestedSegments() {
        val xml = """
            <timedtext><body>
              <p t="2200" d="1800"><s>Hello </s><s>world</s></p>
            </body></timedtext>
        """.trimIndent()

        val cues = CaptionDocumentParser.parse(xml)

        assertEquals(1, cues.size)
        assertEquals(2_200L, cues.single().startMs)
        assertEquals(4_000L, cues.single().endMs)
        assertEquals("Hello world", cues.single().text)
    }

    @Test fun json3ChunksCarryPerWordTimings() {
        val json = """
            {"events":[
              {"tStartMs":1000,"dDurationMs":2000,"segs":[
                {"utf8":"Hello","tOffsetMs":0},
                {"utf8":" ","tOffsetMs":400},
                {"utf8":"world","tOffsetMs":500},
                {"utf8":"\n"}
              ]}
            ]}
        """.trimIndent()

        val cue = CaptionDocumentParser.parse(json).single()

        assertEquals("Hello world", cue.text)
        assertEquals(2, cue.words.size)
        assertEquals("Hello", cue.words[0].text)
        assertEquals(1_000L, cue.words[0].startMs)
        assertEquals(1_500L, cue.words[1].startMs)
        assertEquals(3_000L, cue.words[1].endMs)
    }

    @Test fun json3MultiWordChunksExpandToIndividualWords() {
        val json = """
            {"events":[
              {"tStartMs":1000,"dDurationMs":2400,"segs":[
                {"utf8":"limits is really ","tOffsetMs":0},
                {"utf8":"the basis","tOffsetMs":1200}
              ]}
            ]}
        """.trimIndent()

        val words = CaptionDocumentParser.parse(json).single().words

        assertEquals(listOf("limits", "is", "really", "the", "basis"), words.map { it.text })
        assertEquals(1_000L, words.first().startMs)
        assertEquals(2_200L, words[3].startMs)
        assertEquals(3_400L, words.last().endMs)
        assertTrue(words.zipWithNext().all { (left, right) -> left.startMs <= right.startMs })
    }

    @Test fun srv3WordOffsetsProduceTimings() {
        val xml = """
            <timedtext><body>
              <p t="2200" d="1800"><s t="0">Hello </s><s t="600">brave</s><s t="1200"> world</s></p>
            </body></timedtext>
        """.trimIndent()

        val words = CaptionDocumentParser.parse(xml).single().words

        assertEquals(listOf("Hello", "brave", "world"), words.map { it.text })
        assertEquals(2_200L, words[0].startMs)
        assertEquals(2_800L, words[1].startMs)
        assertEquals(4_000L, words[2].endMs)
    }

    @Test fun srv3MultiWordChunksExpandInsideRealAnchors() {
        val xml = """
            <timedtext><body>
              <p t="1000" d="2000"><s t="0">we are really </s><s t="1000">ready now</s></p>
            </body></timedtext>
        """.trimIndent()

        val words = CaptionDocumentParser.parse(xml).single().words

        assertEquals(listOf("we", "are", "really", "ready", "now"), words.map { it.text })
        assertEquals(1_000L, words.first().startMs)
        assertEquals(2_000L, words[3].startMs)
        assertEquals(3_000L, words.last().endMs)
    }

    @Test fun legacyXmlHasNoWordTimings() {
        val xml = """
            <transcript>
              <text start="1.5" dur="2.25">Hello &amp; welcome</text>
            </transcript>
        """.trimIndent()

        assertTrue(CaptionDocumentParser.parse(xml).single().words.isEmpty())
    }
}
