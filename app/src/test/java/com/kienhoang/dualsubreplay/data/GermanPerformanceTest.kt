package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the German lexicon's load time and heap, and the analysis time of one line, on the JVM that runs the
 * unit tests (not on a phone, and with the JIT cold on first use). The budgets are the ones in
 * docs/specs/2026-11-14-german-grammar.md; the numbers are printed so the spec can record them.
 */
class GermanPerformanceTest {
    private fun usedHeap(): Long {
        repeat(3) { System.gc() }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    @Test fun lexiconLoadHeapAndLatencyStayInTheBudget() {
        val before = usedHeap()
        val startLoad = System.nanoTime()
        val lexicon =
            LearnerData.open("de-verbs.txt").use { verbs ->
                LearnerData.open("de-words.txt").use { loadGermanLexicon(verbs, it) }
            }
        val loadMs = (System.nanoTime() - startLoad) / NANOS_PER_MS
        val heapMb = (usedHeap() - before) / BYTES_PER_MB
        check(GermanMorphology.awaitGrammarReady())
        val line = "Wenn ich mehr Zeit hätte, würde ich das Buch lesen, das du mir gestern gegeben hast."
        repeat(WARM_UP) { germanGrammar(GermanMorphology.sentence(line)!!) }
        val times =
            List(RUNS) {
                val start = System.nanoTime()
                germanGrammar(GermanMorphology.sentence(line)!!)
                (System.nanoTime() - start) / 1e6
            }.sorted()
        val median = times[RUNS / 2]
        println(
            "German lexicon: load $loadMs ms, heap ${"%.1f".format(
                heapMb.toDouble(),
            )} MB, ${lexicon.verbs.size} verbs ${lexicon.nouns.size} nouns",
        )
        println(
            "German analysis of a 17-word line: median ${"%.2f".format(
                median,
            )} ms, worst ${"%.2f".format(times.last())} ms over $RUNS runs",
        )
        assertTrue("load $loadMs ms is over 300 ms", loadMs <= 300)
        assertTrue("heap $heapMb MB is over 15 MB", heapMb <= 15)
        assertTrue("median $median ms is over 20 ms", median <= 20)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val BYTES_PER_MB = 1_048_576L
        const val WARM_UP = 20
        const val RUNS = 101
    }
}
