package com.kienhoang.dualsubreplay.data

/** Precision and recall of the German rules on an annotated corpus of `RULE|evidence words` per sentence. */
internal class GermanEvaluation(
    val sentences: Int,
    val byRule: Map<String, Counts>,
    val problems: List<String>,
) {
    class Counts(
        var truePositive: Int = 0,
        var falsePositive: Int = 0,
        var falseNegative: Int = 0,
    )

    val truePositive get() = byRule.values.sumOf { it.truePositive }
    val falsePositive get() = byRule.values.sumOf { it.falsePositive }
    val falseNegative get() = byRule.values.sumOf { it.falseNegative }
    val precision get() = truePositive.toDouble() / (truePositive + falsePositive)
    val recall get() = truePositive.toDouble() / (truePositive + falseNegative)

    fun precision(rule: String): Double = byRule.getValue(rule).let { it.truePositive.toDouble() / (it.truePositive + it.falsePositive) }

    fun report(title: String): String =
        buildString {
            appendLine("$title: $sentences sentences, true positives $truePositive, false positives $falsePositive, missed $falseNegative")
            appendLine("  overall precision ${"%.3f".format(precision)}, recall ${"%.3f".format(recall)}")
            for ((rule, c) in byRule) {
                val p =
                    if (c.truePositive + c.falsePositive ==
                        0
                    ) {
                        Double.NaN
                    } else {
                        c.truePositive.toDouble() / (c.truePositive + c.falsePositive)
                    }
                val r =
                    if (c.truePositive + c.falseNegative ==
                        0
                    ) {
                        Double.NaN
                    } else {
                        c.truePositive.toDouble() / (c.truePositive + c.falseNegative)
                    }
                appendLine(
                    "  $rule: tp ${c.truePositive} fp ${c.falsePositive} fn ${c.falseNegative} precision ${"%.2f".format(
                        p,
                    )} recall ${"%.2f".format(r)}",
                )
            }
            problems.forEach { appendLine("  $it") }
        }

    companion object {
        /** Rules that are lexical and tested one by one, so they are left out of the corpus. */
        private val notEvaluated =
            setOf(
                GrammarMeaning.DE_PRAESENS,
                GrammarMeaning.DE_PRAETERITUM,
                GrammarMeaning.DE_KOORDINATION,
                GrammarMeaning.DE_NICHT,
                GrammarMeaning.DE_KEIN,
            )

        /** A point counts as found when its first meaning and its evidence words both equal the annotation. */
        fun run(resource: String): GermanEvaluation {
            check(GermanMorphology.awaitGrammarReady())
            val rows =
                GermanEvaluation::class.java.classLoader!!
                    .getResourceAsStream(resource)!!
                    .bufferedReader()
                    .readLines()
                    .filter { !it.startsWith("#") && it.isNotBlank() }
                    .map { it.split('\t') }
            val byRule = sortedMapOf<String, Counts>()
            val problems = ArrayList<String>()
            for (row in rows) {
                val line = row[0]
                val expected =
                    row
                        .getOrElse(1) { "" }
                        .split(';')
                        .filter { it.isNotBlank() }
                        .map {
                            it.substringBefore('|') to
                                it.substringAfter('|')
                        }.toSet()
                val found =
                    germanGrammar(GermanMorphology.sentence(line)!!)
                        .filter { it.meanings.first() !in notEvaluated }
                        .map { it.meanings.first().name to it.evidence.joinToString(" ") { r -> line.substring(r.first, r.last + 1) } }
                        .toSet()
                for (hit in found) {
                    val counts = byRule.getOrPut(hit.first) { Counts() }
                    if (hit in expected) {
                        counts.truePositive++
                    } else {
                        counts.falsePositive++
                        problems += "FALSE  $line -> ${hit.first}|${hit.second}"
                    }
                }
                for (miss in expected - found) {
                    byRule.getOrPut(miss.first) { Counts() }.falseNegative++
                    problems += "MISSED $line -> ${miss.first}|${miss.second}"
                }
            }
            return GermanEvaluation(rows.size, byRule, problems)
        }
    }
}
