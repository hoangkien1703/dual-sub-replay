package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishGrammarCatalogueTest {
    private companion object {
        val analyzer by lazy { loadSpanishAnalyzer() }

        /** One sentence per point (catalogue and rules), run through the real analyzer: each must find its point. */
        val EXAMPLES: List<Pair<GrammarMeaning, String>> =
            listOf(
                GrammarMeaning.ES_ME_GUSTARIA to "Me gustaría viajar a México.",
                GrammarMeaning.ES_GUSTAR to "Me gusta mucho el chocolate.",
                GrammarMeaning.ES_EMOTION_SUBJ to "Me alegra que estés aquí.",
                GrammarMeaning.ES_TENER_QUE to "Tengo que trabajar mañana.",
                GrammarMeaning.ES_HAY_QUE to "Hay que estudiar más.",
                GrammarMeaning.ES_VAMOS_A to "¡Vamos a bailar!",
                GrammarMeaning.ES_IR_A to "Voy a comer con mis padres.",
                GrammarMeaning.ES_ESTAR_GERUND to "Estoy comiendo.",
                GrammarMeaning.ES_SEGUIR_GERUND to "Sigue lloviendo.",
                GrammarMeaning.ES_LLEVAR_GERUND to "Llevo dos horas esperando.",
                GrammarMeaning.ES_IR_GERUND to "Poco a poco voy entendiendo.",
                GrammarMeaning.ES_PODER to "¿Puedes cerrar la puerta?",
                GrammarMeaning.ES_QUERER to "Quiero aprender español.",
                GrammarMeaning.ES_DEBER to "Debes descansar un poco.",
                GrammarMeaning.ES_SOLER to "Suelo levantarme temprano.",
                GrammarMeaning.ES_ACABAR_DE to "Acabo de llegar.",
                GrammarMeaning.ES_VOLVER_A to "Volví a leer el libro.",
                GrammarMeaning.ES_EMPEZAR_A to "Empezó a llover.",
                GrammarMeaning.ES_DEJAR_DE to "Dejé de fumar.",
                GrammarMeaning.ES_ESTAR_A_PUNTO to "Estoy a punto de salir.",
                GrammarMeaning.ES_HACER_FALTA to "No hace falta gritar.",
                GrammarMeaning.ES_TENER_NOUN to "Tengo hambre.",
                GrammarMeaning.ES_PRESENT_PERFECT to "He visto esa película.",
                GrammarMeaning.ES_PLUPERFECT to "Cuando llegué, ya se había ido.",
                GrammarMeaning.ES_FUTURE_PERFECT to "Para mañana habré terminado.",
                GrammarMeaning.ES_CONDITIONAL_PERFECT to "Yo lo habría hecho.",
                GrammarMeaning.ES_PERFECT_SUBJUNCTIVE to "Me alegro de que hayas venido.",
                GrammarMeaning.ES_PLUPERFECT_SUBJUNCTIVE to "Ojalá hubiera estudiado más.",
                GrammarMeaning.ES_PASSIVE to "El puente fue construido en 1990.",
                GrammarMeaning.ES_ESTAR_PARTICIPLE to "La tienda está cerrada.",
                GrammarMeaning.ES_WISH_SUBJ to "No quiero que te vayas.",
                GrammarMeaning.ES_IMPERSONAL_SUBJ to "Es importante que llegues a tiempo.",
                GrammarMeaning.ES_DOUBT_SUBJ to "No creo que venga.",
                GrammarMeaning.ES_OJALA to "Ojalá que llueva mañana.",
                GrammarMeaning.ES_PARA_QUE to "Te lo digo para que lo sepas.",
                GrammarMeaning.ES_ANTES_DE_QUE to "Vete antes de que llegue.",
                GrammarMeaning.ES_SUBJ_CONJUNCTIONS to "No iré a menos que me invites.",
                GrammarMeaning.ES_COMO_SI to "Habla como si fuera el jefe.",
                GrammarMeaning.ES_TAL_VEZ to "Quizás venga más tarde.",
                GrammarMeaning.ES_SER to "Mi hermana es médica.",
                GrammarMeaning.ES_ESTAR to "Estamos en casa.",
                GrammarMeaning.ES_HAY to "Hay un gato en el jardín.",
                GrammarMeaning.ES_QUE_EXCLAMATION to "¡Qué bonito!",
                GrammarMeaning.ES_POR_QUE to "¿Por qué no vienes?",
                GrammarMeaning.ES_QUESTION_WORD to "¿Dónde vives?",
                GrammarMeaning.ES_TAG_QUESTION to "Hace frío, ¿verdad?",
                GrammarMeaning.ES_NEGATIVE_WORD to "No veo nada.",
                GrammarMeaning.ES_TAMBIEN to "Yo también quiero ir.",
                GrammarMeaning.ES_COURTESY to "Perdón, ¿me ayudas, por favor?",
                GrammarMeaning.ES_CONTRACTION to "Vamos al parque.",
                GrammarMeaning.ES_DEFINITE_ARTICLE to "La casa es grande.",
                GrammarMeaning.ES_INDEFINITE_ARTICLE to "Tengo un perro.",
                GrammarMeaning.ES_LO_QUE to "No entiendo lo que dices.",
                GrammarMeaning.ES_LO_ADJ to "Lo bueno es que no llueve.",
                GrammarMeaning.ES_EL_QUE to "La que canta es mi hermana.",
                GrammarMeaning.ES_POSSESSIVE to "Mis padres viven en Lima.",
                GrammarMeaning.ES_DEMONSTRATIVE to "Esta camisa es nueva.",
                GrammarMeaning.ES_POSSESSIVE_PRONOUN to "Este libro es tuyo.",
                GrammarMeaning.ES_OTRO to "Quiero otro café.",
                GrammarMeaning.ES_TODO to "Todos los días como fruta.",
                GrammarMeaning.ES_CADA to "Cada niño tiene un libro.",
                GrammarMeaning.ES_INDEFINITE to "¿Quieres algo de beber?",
                GrammarMeaning.ES_QUANTITY to "Hay demasiada gente aquí.",
                GrammarMeaning.ES_MUY_MUCHO to "Es muy simpático y tiene muchos amigos.",
                GrammarMeaning.ES_MAS_QUE to "Mi casa es más grande que la tuya.",
                GrammarMeaning.ES_COMO to "Canta como un ángel.",
                GrammarMeaning.ES_TAN_COMO to "Es tan alto como su padre.",
                GrammarMeaning.ES_SUPERLATIVE to "Es la ciudad más bonita del mundo.",
                GrammarMeaning.ES_COMPARATIVE_IRREGULAR to "Este libro es mejor.",
                GrammarMeaning.ES_ISIMO to "La película es buenísima.",
                GrammarMeaning.ES_POR to "Gracias por todo.",
                GrammarMeaning.ES_PARA_INF to "Estudio para aprender.",
                GrammarMeaning.ES_PARA to "Este regalo es para ti.",
                GrammarMeaning.ES_A to "Voy a la playa.",
                GrammarMeaning.ES_EN to "Vivo en Madrid.",
                GrammarMeaning.ES_DE to "Soy de Colombia.",
                GrammarMeaning.ES_CON to "Vivo con mis padres.",
                GrammarMeaning.ES_SIN to "Café sin azúcar, por favor.",
                GrammarMeaning.ES_DESDE_HASTA to "Trabajo desde las ocho hasta las cinco.",
                GrammarMeaning.ES_OTHER_PREPOSITION to "Hablamos sobre el viaje.",
                GrammarMeaning.ES_ANTES_DESPUES_INF to "Lávate las manos antes de comer.",
                GrammarMeaning.ES_AL_INF to "Al llegar a casa, me duché.",
                GrammarMeaning.ES_Y to "Tengo un hermano y una hermana.",
                GrammarMeaning.ES_O to "¿Quieres té o café?",
                GrammarMeaning.ES_NI to "No tengo ni hambre ni sed.",
                GrammarMeaning.ES_PERO to "Es caro, pero es bueno.",
                GrammarMeaning.ES_SINO to "No es rojo, sino azul.",
                GrammarMeaning.ES_PORQUE to "No salgo porque llueve.",
                GrammarMeaning.ES_ASI_QUE to "Estaba cansado, así que me acosté.",
                GrammarMeaning.ES_MIENTRAS to "Leo mientras espero.",
                GrammarMeaning.ES_HACE_TIME to "Lo conocí hace tres años.",
                GrammarMeaning.ES_DESDE_HACE to "Vivo aquí desde hace dos años.",
                GrammarMeaning.ES_YA_TODAVIA to "Ya he comido.",
                GrammarMeaning.ES_CADA_VEZ to "Hace cada vez más calor.",
                GrammarMeaning.ES_FREQUENCY to "Siempre desayuno a las ocho.",
                GrammarMeaning.ES_USTED to "¿Cómo está usted?",
                GrammarMeaning.ES_SUBJECT_PRONOUN to "Yo trabajo y él estudia.",
                GrammarMeaning.ES_PREPOSITIONAL_PRONOUN to "Sin ti no soy nada.",
                GrammarMeaning.ES_VOSOTROS to "¿Vosotros venís mañana?",
                GrammarMeaning.ES_VOS to "Vos sabés que te quiero.",
                GrammarMeaning.ES_QUE_RELATIVE to "El chico que vive aquí es simpático.",
                GrammarMeaning.ES_QUE_CONJUNCTION to "Creo que tienes razón.",
                GrammarMeaning.ES_QUIEN_RELATIVE to "Ella es la persona con quien hablé.",
                GrammarMeaning.ES_DONDE_RELATIVE to "Esta es la casa donde nací.",
                GrammarMeaning.ES_CUYO to "Es el escritor cuyo libro leí.",
                GrammarMeaning.ES_PRESENT to "Ella habla tres idiomas.",
                GrammarMeaning.ES_PRETERITE to "Ayer comí paella.",
                GrammarMeaning.ES_IMPERFECT to "De niño, jugaba en la calle.",
                GrammarMeaning.ES_FUTURE to "Mañana lloverá.",
                GrammarMeaning.ES_CONDITIONAL to "Yo en tu lugar no lo haría.",
                GrammarMeaning.ES_PRESENT_SUBJUNCTIVE to "Me alegro de que estés bien.",
                GrammarMeaning.ES_IMPERFECT_SUBJUNCTIVE to "Si tuviera tiempo, viajaría más.",
                GrammarMeaning.ES_IMPERATIVE to "¡Habla más alto!",
                GrammarMeaning.ES_NEGATIVE_COMMAND to "No hables tan rápido.",
                GrammarMeaning.ES_GERUND to "Aprendo escuchando canciones.",
                GrammarMeaning.ES_PARTICIPLE to "Tengo la ventana abierta.",
                GrammarMeaning.ES_INFINITIVE to "Es difícil aprender idiomas.",
                GrammarMeaning.ES_NO to "No tengo tiempo.",
                GrammarMeaning.ES_OBJECT_PRONOUN to "Lo vi ayer en el parque.",
                GrammarMeaning.ES_REFLEXIVE to "Me llamo Ana y me levanto temprano.",
                GrammarMeaning.ES_SE_IMPERSONAL to "Se habla español en muchos países.",
                GrammarMeaning.ES_SE_LO to "Se lo di a mi hermano.",
                GrammarMeaning.ES_SE_ACCIDENTAL to "Se me olvidó la llave.",
                GrammarMeaning.ES_ATTACHED_PRONOUNS to "Dámelo ahora mismo.",
                GrammarMeaning.ES_SI_REAL to "Si llueve, no salgo.",
                GrammarMeaning.ES_SI_UNREAL to "Si tuviera dinero, viajaría.",
                GrammarMeaning.ES_SI_PAST_UNREAL to "Si hubiera sabido, te lo habría dicho.",
                GrammarMeaning.ES_SI_WHETHER to "No sé si viene.",
                GrammarMeaning.ES_CUANDO to "Cuando era niño, vivía en Quito.",
                GrammarMeaning.ES_CUANDO_FUTURE to "Cuando llegues, llámame.",
                GrammarMeaning.ES_AUNQUE to "Aunque estoy cansado, voy a salir.",
                GrammarMeaning.ES_AUNQUE_SUBJ to "Aunque llueva, iremos.",
                GrammarMeaning.ES_ES_QUE to "Es que no tengo tiempo.",
                GrammarMeaning.ES_DISCOURSE_MARKER to "Vale, nos vemos mañana.",
            )

        /** The points found by the rules in SpanishGrammar.kt rather than by catalogue patterns. */
        val RULE_MEANINGS: Set<GrammarMeaning> =
            setOf(
                GrammarMeaning.ES_SER,
                GrammarMeaning.ES_ESTAR,
                GrammarMeaning.ES_PRESENT,
                GrammarMeaning.ES_PRETERITE,
                GrammarMeaning.ES_IMPERFECT,
                GrammarMeaning.ES_FUTURE,
                GrammarMeaning.ES_CONDITIONAL,
                GrammarMeaning.ES_PRESENT_SUBJUNCTIVE,
                GrammarMeaning.ES_IMPERFECT_SUBJUNCTIVE,
                GrammarMeaning.ES_IMPERATIVE,
                GrammarMeaning.ES_NEGATIVE_COMMAND,
                GrammarMeaning.ES_GERUND,
                GrammarMeaning.ES_PARTICIPLE,
                GrammarMeaning.ES_INFINITIVE,
                GrammarMeaning.ES_NO,
                GrammarMeaning.ES_OBJECT_PRONOUN,
                GrammarMeaning.ES_REFLEXIVE,
                GrammarMeaning.ES_SE_IMPERSONAL,
                GrammarMeaning.ES_SE_LO,
                GrammarMeaning.ES_SE_ACCIDENTAL,
                GrammarMeaning.ES_ATTACHED_PRONOUNS,
                GrammarMeaning.ES_SI_REAL,
                GrammarMeaning.ES_SI_UNREAL,
                GrammarMeaning.ES_SI_PAST_UNREAL,
                GrammarMeaning.ES_SI_WHETHER,
                GrammarMeaning.ES_CUANDO,
                GrammarMeaning.ES_CUANDO_FUTURE,
                GrammarMeaning.ES_AUNQUE,
                GrammarMeaning.ES_AUNQUE_SUBJ,
                GrammarMeaning.ES_ES_QUE,
                GrammarMeaning.ES_DISCOURSE_MARKER,
            )
    }

    private fun morphemes(sentence: String) = spanishMorphemes(analyzer, sentence)

    private fun grammar(sentence: String) = spanishGrammar(morphemes(sentence))

    @Test
    fun everyPointIsFoundInItsExampleSentence() {
        val missed = EXAMPLES.filter { (meaning, sentence) -> grammar(sentence).none { meaning in it.meanings } }
        val report =
            missed.joinToString("\n") { (meaning, sentence) ->
                "$meaning in $sentence: ${morphemes(sentence).map { "${it.surface}/${it.pos}/${it.conjugationForm}" }} " +
                    "${grammar(sentence).map { it.form }}"
            }
        assertTrue("Not found:\n$report", missed.isEmpty())
    }

    @Test
    fun everyPointHasAnExampleAndItsOwnMeaning() {
        val catalogueMeanings = SPANISH_GRAMMAR_CATALOGUE.flatMap { it.meanings }
        assertEquals(catalogueMeanings.size, catalogueMeanings.toSet().size)
        assertEquals(RULE_MEANINGS, SPANISH_RULE_POINTS.keys)
        assertTrue(catalogueMeanings.none { it in RULE_MEANINGS })
        assertEquals(catalogueMeanings.toSet() + RULE_MEANINGS, EXAMPLES.map { it.first }.toSet())
        assertEquals(EXAMPLES.size, EXAMPLES.map { it.first }.toSet().size)
        val spanish = GrammarMeaning.entries.filter { it.name.startsWith("ES_") }.toSet()
        assertEquals(spanish, EXAMPLES.map { it.first }.toSet())
    }

    @Test
    fun theCatalogueCoversCefrA1ToB2() {
        val levels = SPANISH_GRAMMAR_CATALOGUE.map { it.level }.toSet()
        assertEquals(setOf(CefrLevel.A1, CefrLevel.A2, CefrLevel.B1, CefrLevel.B2), levels)
        assertTrue(SPANISH_GRAMMAR_CATALOGUE.size >= 90)
    }

    @Test
    fun tensesAreReadFromTheVerb() {
        assertEquals(listOf(GrammarMeaning.ES_PRETERITE), meaningsOf("Ayer comí paella.", "comí"))
        assertEquals(listOf(GrammarMeaning.ES_PRESENT, GrammarMeaning.ES_PRETERITE), meaningsOf("Hablamos mucho.", "hablamos"))
        assertEquals(GrammarMeaning.ES_SER, meaningsOf("Mi hermana es médica.", "es").first())
        assertEquals(GrammarMeaning.ES_IMPERATIVE, meaningsOf("¡Habla más alto!", "habla").first())
        assertEquals(GrammarMeaning.ES_IMPERATIVE, meaningsOf("Hable más despacio, por favor.", "hable").first())
        assertEquals(GrammarMeaning.ES_REFLEXIVE, meaningsOf("Cuando llegué, ya se había ido.", "se").first())
    }

    @Test
    fun clausesTellTheirMeaningsApart() {
        assertEquals(CefrLevel.B2, levelOf("Si tuviera dinero, viajaría.", GrammarMeaning.ES_SI_UNREAL))
        assertEquals(CefrLevel.C1, levelOf("Si hubiera sabido, te lo habría dicho.", GrammarMeaning.ES_SI_PAST_UNREAL))
        assertTrue(grammar("Si llueve, no salgo.").any { GrammarMeaning.ES_SI_REAL in it.meanings })
        assertTrue(grammar("Cuando era niño, vivía en Quito.").none { GrammarMeaning.ES_CUANDO_FUTURE in it.meanings })
        assertTrue(grammar("Aunque estoy cansado, voy a salir.").none { GrammarMeaning.ES_AUNQUE_SUBJ in it.meanings })
        assertEquals(listOf(GrammarMeaning.ES_REFLEXIVE, GrammarMeaning.ES_OBJECT_PRONOUN), meaningsOf("Me llamo Ana.", "me"))
        assertEquals(listOf(GrammarMeaning.ES_OBJECT_PRONOUN), meaningsOf("Te quiero mucho.", "te"))
    }

    @Test
    fun casualLinesFindCourtesyAndConversationWords() {
        val line = "Eh, sí porfa hale, ¡gracias!"
        assertEquals(listOf(GrammarMeaning.ES_DISCOURSE_MARKER), meaningsOf(line, "eh"))
        assertEquals(listOf(GrammarMeaning.ES_COURTESY), meaningsOf(line, "porfa"))
        assertEquals(listOf(GrammarMeaning.ES_COURTESY), meaningsOf(line, "gracias"))
        assertTrue(grammar("Vale gracias.").any { GrammarMeaning.ES_DISCOURSE_MARKER in it.meanings })
        assertTrue(grammar("Si te quieres sentar aquí hay mesa ¿vale?").any { GrammarMeaning.ES_DISCOURSE_MARKER in it.meanings })
        assertTrue(grammar("¿Cuánto vale?").none { GrammarMeaning.ES_DISCOURSE_MARKER in it.meanings })
        assertTrue(grammar("Mira la casa.").none { GrammarMeaning.ES_DISCOURSE_MARKER in it.meanings })
        assertTrue(grammar("Voy a ver la película.").none { GrammarMeaning.ES_DISCOURSE_MARKER in it.meanings })
        assertTrue(grammar("Es mi primo, o sea, el hijo de mi tío.").any { GrammarMeaning.ES_DISCOURSE_MARKER in it.meanings })
    }

    private fun meaningsOf(
        sentence: String,
        word: String,
    ): List<GrammarMeaning> {
        val start = sentence.lowercase().indexOf(word)
        return grammar(sentence).first { it.start <= start && it.end > start }.meanings
    }

    private fun levelOf(
        sentence: String,
        meaning: GrammarMeaning,
    ) = grammar(sentence).first { meaning in it.meanings }.level
}
