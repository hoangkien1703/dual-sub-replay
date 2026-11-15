package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every German grammar point has its own example sentence in which it is found with evidence, and the level it is
 * taught at is the one in [GERMAN_RULE_LEVELS] (the pattern of Spanish's SPANISH_RULE_POINTS test).
 */
class GermanRulePointsTest {
    private companion object {
        init {
            check(GermanMorphology.awaitGrammarReady()) { "German model or lexicon did not load" }
        }

        val EXAMPLES: Map<GrammarMeaning, String> =
            mapOf(
                GrammarMeaning.DE_PRAESENS to "Ich gehe nach Hause.",
                GrammarMeaning.DE_PRAETERITUM to "Er war gestern krank.",
                GrammarMeaning.DE_PERFEKT to "Ich habe das Buch gelesen.",
                GrammarMeaning.DE_PLUSQUAMPERFEKT to "Er hatte das Buch gelesen.",
                GrammarMeaning.DE_FUTUR_I to "Ich werde morgen kommen.",
                GrammarMeaning.DE_FUTUR_II to "Er wird es gesehen haben.",
                GrammarMeaning.DE_IMPERATIV to "Komm bitte schnell!",
                GrammarMeaning.DE_KONJUNKTIV_I to "Er sagte, er sei krank.",
                GrammarMeaning.DE_KONJUNKTIV_II to "Ich hätte gern einen Tee.",
                GrammarMeaning.DE_WUERDE_INFINITIV to "Ich würde gern reisen.",
                GrammarMeaning.DE_KONJUNKTIV_II_VERGANGENHEIT to "Ich hätte das gewusst.",
                GrammarMeaning.DE_ERSATZINFINITIV to "Ich hätte das früher wissen müssen.",
                GrammarMeaning.DE_VORGANGSPASSIV to "Das Haus wird gebaut.",
                GrammarMeaning.DE_ZUSTANDSPASSIV to "Das Haus ist gebaut.",
                GrammarMeaning.DE_PASSIV_PERFEKT to "Das Haus ist gebaut worden.",
                GrammarMeaning.DE_MODALVERB to "Ich muss jetzt gehen.",
                GrammarMeaning.DE_TRENNBARES_VERB to "Ich stehe früh auf.",
                GrammarMeaning.DE_REFLEXIVES_VERB to "Ich freue mich.",
                GrammarMeaning.DE_ZU_INFINITIV to "Er hofft, dich zu sehen.",
                GrammarMeaning.DE_UM_ZU to "Er lernt, um zu verstehen.",
                GrammarMeaning.DE_OHNE_ZU to "Er geht, ohne zu sprechen.",
                GrammarMeaning.DE_PREP_AKKUSATIV to "Das ist für dich.",
                GrammarMeaning.DE_PREP_DATIV to "Wir fahren mit dem Bus.",
                GrammarMeaning.DE_PREP_GENITIV to "Wegen des Wetters bleiben wir hier.",
                GrammarMeaning.DE_WECHSEL_AKKUSATIV to "Ich gehe in die Stadt.",
                GrammarMeaning.DE_WECHSEL_DATIV to "Ich bin in der Stadt.",
                GrammarMeaning.DE_VERB_PREP_AKKUSATIV to "Ich warte auf den Bus.",
                GrammarMeaning.DE_VERB_PREP_DATIV to "Sie träumt von einem Haus.",
                GrammarMeaning.DE_ADJEKTIV_PREP_AKKUSATIV to "Er ist stolz auf seine Tochter.",
                GrammarMeaning.DE_ADJEKTIV_PREP_DATIV to "Wir sind zufrieden mit dem Hotel.",
                GrammarMeaning.DE_DATIV_VERB to "Ich helfe dir.",
                GrammarMeaning.DE_DATIV_AKKUSATIV to "Ich gebe dem Hund einen Knochen.",
                GrammarMeaning.DE_GENITIVATTRIBUT to "Die Bücher der Kinder liegen hier.",
                GrammarMeaning.DE_ADJEKTIV_SCHWACH to "Der alte Mann kommt.",
                GrammarMeaning.DE_ADJEKTIV_GEMISCHT to "Ein alter Mann kommt.",
                GrammarMeaning.DE_ADJEKTIV_STARK to "Er trinkt kalten Tee.",
                GrammarMeaning.DE_KOMPARATIV to "Er ist größer als ich.",
                GrammarMeaning.DE_SUPERLATIV to "Das ist am besten.",
                GrammarMeaning.DE_SUB_WEIL to "Ich bleibe, weil ich krank bin.",
                GrammarMeaning.DE_SUB_DASS to "Ich weiß, dass er kommt.",
                GrammarMeaning.DE_SUB_OB to "Ich frage, ob er kommt.",
                GrammarMeaning.DE_SUB_WENN to "Wenn es regnet, bleibe ich hier.",
                GrammarMeaning.DE_SUB_ALS to "Als ich jung war, wohnte ich hier.",
                GrammarMeaning.DE_SUB_OBWOHL to "Obwohl er müde war, arbeitete er.",
                GrammarMeaning.DE_SUB_DAMIT to "Er spart, damit er reisen kann.",
                GrammarMeaning.DE_SUB_ZEIT to "Nachdem er gegessen hatte, ging er.",
                GrammarMeaning.DE_SUB_FOLGE to "Er sprach leise, sodass niemand es hörte.",
                GrammarMeaning.DE_RELATIVSATZ to "Das Buch, das ich gelesen habe, ist gut.",
                GrammarMeaning.DE_INDIREKTE_FRAGE to "Ich weiß nicht, wo er wohnt.",
                GrammarMeaning.DE_W_FRAGE to "Wo wohnst du?",
                GrammarMeaning.DE_JA_NEIN_FRAGE to "Kommst du heute?",
                GrammarMeaning.DE_INVERSION to "Heute gehe ich ins Kino.",
                GrammarMeaning.DE_KOORDINATION to "Ich bleibe und du gehst.",
                GrammarMeaning.DE_NICHT to "Ich komme nicht.",
                GrammarMeaning.DE_KEIN to "Ich habe keine Zeit.",
                GrammarMeaning.DE_ES_GIBT to "Es gibt hier einen Supermarkt.",
                GrammarMeaning.DE_KONDITIONAL_IRREAL to "Wenn ich Zeit hätte, würde ich kommen.",
            )

        fun germanMeanings() = GrammarMeaning.entries.filter { it.name.startsWith("DE_") && it != GrammarMeaning.DE_ARU }
    }

    @Test fun everyGermanPointHasAnExampleWhereItIsFoundWithEvidence() {
        val missingExample = germanMeanings().filter { it !in EXAMPLES }
        assertTrue("points without an example: $missingExample", missingExample.isEmpty())
        val levels = LinkedHashMap<GrammarMeaning, CefrLevel>()
        val notFound = ArrayList<GrammarMeaning>()
        for ((meaning, line) in EXAMPLES) {
            val hit = germanGrammar(GermanMorphology.sentence(line)!!).firstOrNull { meaning in it.meanings && it.evidence.isNotEmpty() }
            if (hit == null) notFound += meaning else levels[meaning] = hit.level as CefrLevel
        }
        println("German observed levels: " + levels.entries.joinToString(", ") { "${it.key.name}=${it.value.name}" })
        assertTrue("points not found in their own example: $notFound", notFound.isEmpty())
    }

    @Test fun theRegistryHoldsTheLevelOfEveryPointAndAgreesWithWhatIsFound() {
        for ((meaning, line) in EXAMPLES) {
            val registered = GERMAN_RULE_LEVELS[meaning]
            assertTrue("$meaning has no level in GERMAN_RULE_LEVELS", registered != null)
            val hit = germanGrammar(GermanMorphology.sentence(line)!!).first { meaning in it.meanings && it.evidence.isNotEmpty() }
            if (hit.meanings.first() == meaning) assertEquals("$meaning in \"$line\"", registered, hit.level)
        }
    }

    @Test fun theCatalogueCoversCefrA1ToB2AndNoC1() {
        val covered = GERMAN_RULE_LEVELS.values.toSet()
        assertTrue(listOf(CefrLevel.A1, CefrLevel.A2, CefrLevel.B1, CefrLevel.B2).all { it in covered })
        assertTrue("no C1 point is claimed without a reliable offline detection", CefrLevel.C1 !in covered && CefrLevel.C2 !in covered)
    }
}
