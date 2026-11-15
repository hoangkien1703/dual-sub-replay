package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the bundled lexicon (`de-verbs.txt`, `de-words.txt`) and the conjugation rules against forms taken
 * from Wiktionary's own tables (`de-verbs-golden.tsv`, `de-nouns-golden.tsv`, made by the same script that made
 * the lexicon). The golden lemmas are a sample of the lexicon, so these rates measure the rules, not German.
 */
class GermanLexiconTest {
    private companion object {
        val lexicon: GermanLexicon by lazy {
            LearnerData.open("de-verbs.txt").use { verbs -> LearnerData.open("de-words.txt").use { loadGermanLexicon(verbs, it) } }
        }

        fun golden(name: String): List<Triple<String, String, String>> =
            GermanLexiconTest::class.java.classLoader!!
                .getResourceAsStream(name)!!
                .bufferedReader()
                .readLines()
                .filter { !it.startsWith("#") && it.isNotBlank() }
                .map { it.split('\t').let { p -> Triple(p[0], p[1], p[2]) } }

        fun cellOf(code: String): GermanCell {
            val tense = GermanTense.entries.first { code.startsWith(it.code) }
            val rest = code.removePrefix(tense.code)
            return if (rest.isEmpty()) GermanCell(tense) else GermanCell(tense, rest[0].digitToInt(), rest[1] == 'p')
        }
    }

    @Test fun conjugationReproducesWiktionarysTables() {
        val rows = golden("de-verbs-golden.tsv")
        var total = 0
        var matched = 0
        val misses = LinkedHashMap<String, MutableList<String>>()
        for ((lemma, code, form) in rows) {
            val verb = lexicon.verbs.getValue(lemma)
            val cell = cellOf(code)
            val generated = (if (cell.person > 0) verb.attached[cell] else verb.forms[cell]).orEmpty()
            total++
            if (form in
                generated
            ) {
                matched++
            } else {
                misses.getOrPut(lemma) { ArrayList() }.add("$code:$form (have ${generated.joinToString("/")})")
            }
        }
        val rate = matched.toDouble() / total
        println("German verb forms: $matched of $total match Wiktionary (${"%.1f".format(rate * 100)}%); verbs with misses ${misses.size}")
        misses.entries.take(25).forEach { println("  ${it.key}: ${it.value.take(3)}") }
        assertTrue("only ${"%.1f".format(rate * 100)}% of Wiktionary's verb forms are generated", rate >= 0.97)
    }

    @Test fun nounFormsAreKnownWithTheirCases() {
        val rows = golden("de-nouns-golden.tsv")
        var total = 0
        var matched = 0
        val misses = ArrayList<String>()
        for ((lemma, code, form) in rows) {
            val case = GermanCase.entries.first { it.name.take(3).lowercase() == code.take(3) }
            val plural = code.endsWith("p")
            val ok = lexicon.nounReadings(form).any { it.noun.lemma == lemma && it.plural == plural && case in it.cases }
            total++
            if (ok) matched++ else misses += "$code $lemma $form"
        }
        println("German noun misses by cell: " + misses.groupingBy { it.substringBefore(' ') }.eachCount())
        val rate = matched.toDouble() / total
        println("German noun forms: $matched of $total match Wiktionary (${"%.1f".format(rate * 100)}%)")
        misses.take(25).forEach { println("  $it") }
        assertTrue("only ${"%.1f".format(rate * 100)}% of Wiktionary's noun forms are known", rate >= 0.95)
    }

    @Test fun commonVerbFormsHaveTheirLemmaAndCell() {
        fun has(
            word: String,
            lemma: String,
            cell: GermanCell,
        ) = lexicon.verbReadings(word).any { it.verb.infinitive == lemma && it.cell == cell }
        assertTrue(has("gehe", "gehen", GermanCell(GermanTense.PRESENT, 1)))
        assertTrue("gehe is also Konjunktiv I", has("gehe", "gehen", GermanCell(GermanTense.SUBJUNCTIVE_I, 1)))
        assertTrue(has("gegangen", "gehen", GermanCell(GermanTense.PARTICIPLE_II)))
        assertTrue(has("hätte", "haben", GermanCell(GermanTense.SUBJUNCTIVE_II, 1)))
        assertTrue(has("hätte", "haben", GermanCell(GermanTense.SUBJUNCTIVE_II, 3)))
        assertTrue(has("wissen", "wissen", GermanCell(GermanTense.INFINITIVE)))
        assertTrue(has("ist", "sein", GermanCell(GermanTense.PRESENT, 3)))
        assertTrue(has("aufgestanden", "aufstehen", GermanCell(GermanTense.PARTICIPLE_II)))
        assertTrue(has("Gibst", "geben", GermanCell(GermanTense.PRESENT, 2)))
        assertTrue(lexicon.verbReadings("Xyzzy").isEmpty())
        assertTrue(lexicon.verbReadings("").isEmpty())
    }

    @Test fun separableFiniteFormsComeWithTheirPrefixAttachedOrNot() {
        val detached = lexicon.verbReadings("stehe").filter { it.verb.infinitive == "aufstehen" }
        assertTrue(detached.isNotEmpty())
        assertTrue(detached.all { !it.attached })
        val attached = lexicon.verbReadings("aufsteht").filter { it.verb.infinitive == "aufstehen" }
        assertTrue(attached.any { it.attached && it.cell.code == "pres3s" })
    }

    @Test fun nounsAdjectivesAndAmbiguityKeepAllReadings() {
        val man = lexicon.nounReadings("Männer").single()
        assertEquals("Mann", man.noun.lemma)
        assertTrue(man.plural && GermanCase.GENITIVE in man.cases && GermanCase.DATIVE !in man.cases)
        assertEquals(setOf(GermanCase.DATIVE), lexicon.nounReadings("Männern").single().cases)
        val boy = lexicon.nounReadings("Jungen").first { it.noun.lemma == "Junge" }
        assertTrue(GermanCase.ACCUSATIVE in boy.cases || boy.plural)
        assertTrue(lexicon.adjectiveReadings("älteren").any { it.adjective.lemma == "alt" && it.degree == GermanDegree.COMPARATIVE })
        assertTrue(lexicon.adjectiveReadings("alten").any { it.adjective.lemma == "alt" && it.degree == GermanDegree.POSITIVE })
        assertTrue(lexicon.nounReadings("xyz").isEmpty())
        assertFalse(lexicon.verbs.isEmpty())
        assertNotNull(lexicon.nouns["Haus"])
    }

    @Test fun everyGeneratedVerbFormCanBeAnalysedBackToItsLemmaAndCell() {
        val rows = golden("de-verbs-golden.tsv")
        var found = 0
        val misses = ArrayList<String>()
        for ((lemma, code, form) in rows) {
            val cell = cellOf(code)
            val ok = lexicon.verbReadings(form).any { it.verb.infinitive == lemma && it.cell == cell }
            if (ok) found++ else misses += "$lemma $code $form"
        }
        val rate = found.toDouble() / rows.size
        println(
            "German reverse analysis: $found of ${rows.size} Wiktionary verb forms analysed back to their lemma and cell (${"%.1f".format(
                rate * 100,
            )}%)",
        )
        misses.take(20).forEach { println("  $it") }
        assertTrue("only ${"%.1f".format(rate * 100)}% of the forms analyse back", rate >= 0.98)
    }
}
