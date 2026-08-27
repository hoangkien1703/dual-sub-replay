package com.kienhoang.dualsubreplay.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtcAlignmentTest {
    @Test
    fun `target uses wav2vec alphabet and preserves visible word indices`() {
        val target = requireNotNull(ctcTargetForWords(listOf("Hello,", "4you", "can't")))

        assertEquals("HELLO YOU CAN'T", target.normalizedText)
        assertTrue(target.labels.any { it.tokenId == CTC_WORD_DELIMITER_ID && it.wordIndex == -1 })
        assertTrue(target.labels.any { it.wordIndex == 1 })
        assertTrue(target.labels.any { it.tokenId == 27 && it.wordIndex == 2 })
    }

    @Test
    fun `greedy coverage accepts transcript surrounded by audio context`() {
        assertTrue(orderedTextCoverage("HELLO WORLD", "SO HELLO WORLD TODAY") > 0.95f)
        assertTrue(orderedTextCoverage("HELLO WORLD", "UNRELATED SPEECH") < 0.5f)
    }

    @Test
    fun `viterbi places words on their acoustic frames`() {
        val target = requireNotNull(ctcTargetForWords(listOf("H", "I")))
        val logits = logitsForPath(
            intArrayOf(
                CTC_BLANK_ID,
                11,
                CTC_WORD_DELIMITER_ID,
                10,
                CTC_BLANK_ID,
            ),
        )

        val labelSpans = requireNotNull(
            viterbiCtcAlignment(logits, 5, CTC_VOCAB_SIZE, target),
        )
        val words = ctcWordFrameSpans(target, labelSpans)

        assertEquals(CtcFrameSpan(1, 1), words[0])
        assertEquals(CtcFrameSpan(3, 3), words[1])
    }

    @Test
    fun `viterbi requires a blank between repeated letters`() {
        val target = requireNotNull(ctcTargetForWords(listOf("LL")))
        val logits = logitsForPath(
            intArrayOf(
                CTC_BLANK_ID,
                15,
                CTC_BLANK_ID,
                15,
                CTC_BLANK_ID,
            ),
        )

        val labelSpans = requireNotNull(
            viterbiCtcAlignment(logits, 5, CTC_VOCAB_SIZE, target),
        )
        val words = ctcWordFrameSpans(target, labelSpans)

        assertEquals(CtcFrameSpan(1, 3), words[0])
    }

    @Test
    fun `alignment order starts at current playback position`() {
        assertEquals(listOf(3, 2, 4, 1, 0), alignmentOrder(size = 5, preferredIndex = 3))
    }

    private fun logitsForPath(path: IntArray): FloatArray =
        FloatArray(path.size * CTC_VOCAB_SIZE) { -8f }.also { logits ->
            path.forEachIndexed { frame, tokenId ->
                logits[frame * CTC_VOCAB_SIZE + tokenId] = 8f
            }
        }
}
