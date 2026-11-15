package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The development corpus `de-grammar-eval.tsv` is a regression gate: the analyzer was tuned until it passed, so a pass
 * proves no regression, not accuracy. The thresholds are stated in docs/specs/2026-11-14-german-grammar.md. A rule
 * below them is removed or marked tentative, never loosened here.
 */
class GermanGrammarEvalTest {
    @Test fun developmentCorpusKeepsItsPrecision() {
        val result = GermanEvaluation.run("de-grammar-eval.tsv")
        println(result.report("German grammar development corpus"))
        assertTrue("overall precision ${result.precision} is below 0.90", result.precision >= 0.90)
        val weak =
            result.byRule
                .filter { (_, c) -> c.truePositive + c.falsePositive >= 2 }
                .keys
                .filter { result.precision(it) < 0.80 }
        assertTrue("rules below 0.80 precision: $weak", weak.isEmpty())
    }

    /**
     * HISTORICAL. These 50 sentences were held out when they were written, and the 2026-11-14 measurement
     * (precision 0.984, recall 0.940) is recorded in the spec. The hardening cycle then fixed their failures, so
     * they are no longer independent: this is a second regression set now, and its numbers must not be quoted as accuracy.
     */
    @Test fun firstHeldOutSetIsHistoricalAndOnlyReported() {
        val result = GermanEvaluation.run("de-grammar-heldout.tsv")
        println(result.report("German grammar HISTORICAL held-out v1 (contaminated by the fixes made for it)"))
        assertTrue(result.sentences > 0)
    }

    /**
     * Held out: written before the hardening fixes were made, never edited, never fixed against. Reported only, because a gate
     * would invite tuning on it. The hash below makes any edit of the file fail this test.
     */
    @Test fun secondHeldOutSetIsUntouchedAndReported() {
        val bytes =
            GermanGrammarEvalTest::class.java.classLoader!!
                .getResourceAsStream("de-grammar-heldout-2.tsv")!!
                .readBytes()
        val hash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        assertEquals("de-grammar-heldout-2.tsv was edited; it is only held out while it is unchanged", HELD_OUT_2_SHA256, hash)
        val result = GermanEvaluation.run("de-grammar-heldout-2.tsv")
        println(result.report("German grammar HELD-OUT v2"))
        assertTrue(result.sentences > 0)
    }

    /**
     * Held out v3: written before the second hardening pass, never edited, and run once after that pass. The hash makes any
     * edit fail this test. Reported only.
     */
    @Test fun thirdHeldOutSetIsUntouchedAndReported() {
        val bytes =
            GermanGrammarEvalTest::class.java.classLoader!!
                .getResourceAsStream("de-grammar-heldout-3.tsv")!!
                .readBytes()
        val hash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        assertEquals("de-grammar-heldout-3.tsv was edited; it is only held out while it is unchanged", HELD_OUT_3_SHA256, hash)
        val result = GermanEvaluation.run("de-grammar-heldout-3.tsv")
        println(result.report("German grammar HELD-OUT v3"))
        assertTrue(result.sentences > 0)
    }

    /** Held out v4: written before the third pass and run once after it; the hash makes any edit fail this test. Reported only. */
    @Test fun fourthHeldOutSetIsUntouchedAndReported() {
        val bytes =
            GermanGrammarEvalTest::class.java.classLoader!!
                .getResourceAsStream("de-grammar-heldout-4.tsv")!!
                .readBytes()
        val hash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        assertEquals("de-grammar-heldout-4.tsv was edited; it is only held out while it is unchanged", HELD_OUT_4_SHA256, hash)
        val result = GermanEvaluation.run("de-grammar-heldout-4.tsv")
        println(result.report("German grammar HELD-OUT v4"))
        assertTrue(result.sentences > 0)
    }

    private companion object {
        const val HELD_OUT_4_SHA256 = "34da5f9b5c3066c0d4aab3d0744fd7e5f56be44845ab063306711cab7a3bb57e"
        const val HELD_OUT_3_SHA256 = "ce54e8ad8e6f88c16386f1cbede7f4a7f9575a9f87312595ae42c35f32c0a7bf"
        const val HELD_OUT_2_SHA256 = "2181cfd0eb044740387442c37c651a95fb0f966286454f9b0805bd1aeb3d4ebe"
    }
}
