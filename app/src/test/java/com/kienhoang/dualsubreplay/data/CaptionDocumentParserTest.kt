package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
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
}
