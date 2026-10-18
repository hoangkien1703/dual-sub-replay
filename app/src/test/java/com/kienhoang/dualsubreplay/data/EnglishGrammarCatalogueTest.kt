package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishGrammarCatalogueTest {
    private companion object {
        val analyzer by lazy { EnglishAnalyzer(LearnerData.open("en-pos.zip").use(EnglishPosTagger::load), loadEnglishLexicon()) }

        /** One sentence per point (catalogue and rules), run through the real analyzer: each must find its point. */
        val EXAMPLES: List<Pair<GrammarMeaning, String>> =
            listOf(
                GrammarMeaning.EN_WOULD_RATHER to "I'd rather stay at home.",
                GrammarMeaning.EN_DO_NEGATIVE to "I don't like coffee.",
                GrammarMeaning.EN_DO_QUESTION to "Do you like music?",
                GrammarMeaning.EN_LETS to "Let's go to the beach.",
                GrammarMeaning.EN_WOULD_LIKE to "I'd like a cup of tea.",
                GrammarMeaning.EN_WANT_TO to "I want to learn English.",
                GrammarMeaning.EN_CAN to "She can swim very well.",
                GrammarMeaning.EN_PRESENT_CONTINUOUS to "They are watching TV now.",
                GrammarMeaning.EN_THERE_BE to "There are two cats in the garden.",
                GrammarMeaning.EN_LIKE_ING to "I love dancing.",
                GrammarMeaning.EN_A_LOT_OF to "She has a lot of friends.",
                GrammarMeaning.EN_HOW_MANY to "How many brothers do you have?",
                GrammarMeaning.EN_TO_INFINITIVE to "I went to the shop to buy bread.",
                GrammarMeaning.EN_BE to "She is a doctor.",
                GrammarMeaning.EN_PRESENT_S to "He plays tennis every Sunday.",
                GrammarMeaning.EN_PAST_SIMPLE to "We visited Paris last year.",
                GrammarMeaning.EN_A_AN to "She has a dog.",
                GrammarMeaning.EN_THE to "Close the door, please.",
                GrammarMeaning.EN_AND to "I bought apples and oranges.",
                GrammarMeaning.EN_BUT to "It's small but comfortable.",
                GrammarMeaning.EN_OR to "Is it hot or cold?",
                GrammarMeaning.EN_BECAUSE to "I stayed home because I was sick.",
                GrammarMeaning.EN_FREQUENCY to "I always get up early.",
                GrammarMeaning.EN_POSSESSIVE to "This is Tom's bag.",
                GrammarMeaning.EN_TOO_ALSO to "I like it too.",
                GrammarMeaning.EN_AGO to "I met him three years ago.",
                GrammarMeaning.EN_IN to "She lives in London.",
                GrammarMeaning.EN_ON to "The book is on the table.",
                GrammarMeaning.EN_AT to "Let's meet at six o'clock.",
                GrammarMeaning.EN_TO to "We went to the park.",
                GrammarMeaning.EN_FOR to "This gift is for you.",
                GrammarMeaning.EN_FROM to "I come from Vietnam.",
                GrammarMeaning.EN_WITH to "I live with my parents.",
                GrammarMeaning.EN_OF to "She drank a glass of water.",
                GrammarMeaning.EN_ABOUT to "Tell me about your trip.",
                GrammarMeaning.EN_WH_QUESTION to "Where do you live?",
                GrammarMeaning.EN_YES_NO_QUESTION to "Are you ready?",
                GrammarMeaning.EN_IMPERATIVE to "Open the window, please.",
                GrammarMeaning.EN_DONT_HAVE_TO to "You don't have to come.",
                GrammarMeaning.EN_WHY_DONT to "Why don't we eat out tonight?",
                GrammarMeaning.EN_GOING_TO to "It's going to rain.",
                GrammarMeaning.EN_USED_TO to "She used to live in Tokyo.",
                GrammarMeaning.EN_HAVE_TO to "I have to work tomorrow.",
                GrammarMeaning.EN_NEED_TO to "I need to buy some milk.",
                GrammarMeaning.EN_MUST_NOT to "You mustn't smoke here.",
                GrammarMeaning.EN_VERB_TO to "We decided to stay at home.",
                GrammarMeaning.EN_PAST_CONTINUOUS to "I was reading when you called.",
                GrammarMeaning.EN_HAVE_GOT to "I've got a new phone.",
                GrammarMeaning.EN_PRESENT_PERFECT to "I have finished my homework.",
                GrammarMeaning.EN_WILL to "I will call you tomorrow.",
                GrammarMeaning.EN_COULD to "Could you help me?",
                GrammarMeaning.EN_MUST to "You must wear a seatbelt.",
                GrammarMeaning.EN_SHOULD to "You should see a doctor.",
                GrammarMeaning.EN_COMPARATIVE to "My brother is taller than me.",
                GrammarMeaning.EN_SUPERLATIVE to "This is the biggest house in town.",
                GrammarMeaning.EN_TOO_ADJ to "This coffee is too hot.",
                GrammarMeaning.EN_ENOUGH to "She is old enough to drive.",
                GrammarMeaning.EN_SO_ADJ to "The film was so boring.",
                GrammarMeaning.EN_SO_RESULT to "It was raining, so we stayed home.",
                GrammarMeaning.EN_BEFORE_AFTER to "Wash your hands before eating.",
                GrammarMeaning.EN_UNTIL to "Wait here until I come back.",
                GrammarMeaning.EN_ALREADY to "They already know the answer.",
                GrammarMeaning.EN_YET to "Has she called yet?",
                GrammarMeaning.EN_STILL to "He is still asleep.",
                GrammarMeaning.EN_JUST to "It's just a game.",
                GrammarMeaning.EN_EVER to "Have you ever been to Japan?",
                GrammarMeaning.EN_SOME_ANY to "Do you have any questions?",
                GrammarMeaning.EN_MUCH_MANY to "There aren't many people here.",
                GrammarMeaning.EN_A_FEW to "I'll be back in a few minutes.",
                GrammarMeaning.EN_EVERY_EACH to "I go running every morning.",
                GrammarMeaning.EN_NO_NOUN to "There is no milk left.",
                GrammarMeaning.EN_REFLEXIVE to "Did you hurt yourself?",
                GrammarMeaning.EN_LY_ADVERB to "Please drive carefully.",
                GrammarMeaning.EN_PHRASAL_VERB to "He gave up smoking.",
                GrammarMeaning.EN_HOW_ABOUT to "How about going to the cinema?",
                GrammarMeaning.EN_BY to "I go to school by bus.",
                GrammarMeaning.EN_DURING to "I fell asleep during the movie.",
                GrammarMeaning.EN_WITHOUT to "He left without saying goodbye.",
                GrammarMeaning.EN_INTO to "She jumped into the pool.",
                GrammarMeaning.EN_BETWEEN to "The shop is between the bank and the cafe.",
                GrammarMeaning.EN_BOTH to "Both my parents are teachers.",
                GrammarMeaning.EN_IF_REAL to "If it rains, we will stay home.",
                GrammarMeaning.EN_WHEN_CLAUSE to "Call me when you arrive.",
                GrammarMeaning.EN_PRESENT_PERFECT_CONTINUOUS to "I have been living here for three years.",
                GrammarMeaning.EN_PAST_PERFECT to "When we arrived, the film had started.",
                GrammarMeaning.EN_FUTURE_CONTINUOUS to "This time tomorrow I'll be flying to Rome.",
                GrammarMeaning.EN_MODAL_PASSIVE to "The work must be finished today.",
                GrammarMeaning.EN_PASSIVE to "The book was written by my friend.",
                GrammarMeaning.EN_BE_ABLE_TO to "She wasn't able to come.",
                GrammarMeaning.EN_SUPPOSED_TO to "You're supposed to be at work.",
                GrammarMeaning.EN_HAD_BETTER to "You'd better hurry.",
                GrammarMeaning.EN_HAVE_GOT_TO to "I've got to go now.",
                GrammarMeaning.EN_BE_USED_TO to "I'm used to getting up early.",
                GrammarMeaning.EN_BE_ABOUT_TO to "The train is about to leave.",
                GrammarMeaning.EN_MAY to "It may rain later.",
                GrammarMeaning.EN_MIGHT to "I might be late.",
                GrammarMeaning.EN_SHALL to "Shall we dance?",
                GrammarMeaning.EN_WOULD to "I would buy a house if I had money.",
                GrammarMeaning.EN_VERB_ING to "I enjoy reading books.",
                GrammarMeaning.EN_VERB_SOMEONE_TO to "She asked me to help her.",
                GrammarMeaning.EN_LET_MAKE to "My parents let me stay up late.",
                GrammarMeaning.EN_PREPOSITION_ING to "Thank you for coming.",
                GrammarMeaning.EN_IN_ORDER_TO to "He left early in order to catch the train.",
                GrammarMeaning.EN_SO_THAT to "Speak louder so that everyone can hear.",
                GrammarMeaning.EN_SO_ADJ_THAT to "It was so cold that we stayed inside.",
                GrammarMeaning.EN_BECAUSE_OF to "The game was cancelled because of the rain.",
                GrammarMeaning.EN_ALTHOUGH to "Although it was late, we kept working.",
                GrammarMeaning.EN_EVEN_IF to "I'll go even if it rains.",
                GrammarMeaning.EN_WHILE to "I listen to music while I work.",
                GrammarMeaning.EN_AS_SOON_AS to "Call me as soon as you arrive.",
                GrammarMeaning.EN_IN_CASE to "Take an umbrella in case it rains.",
                GrammarMeaning.EN_WHETHER to "I don't know whether he will come.",
                GrammarMeaning.EN_UNLESS to "You will miss the bus unless you hurry.",
                GrammarMeaning.EN_SINCE to "I have lived here since 2010.",
                GrammarMeaning.EN_INSTEAD_OF to "Let's walk instead of taking the bus.",
                GrammarMeaning.EN_EITHER_NEITHER to "You can have either tea or coffee.",
                GrammarMeaning.EN_WISH to "I wish I knew the answer.",
                GrammarMeaning.EN_RELATIVE_NONDEFINING to "My brother, who lives in Paris, is a chef.",
                GrammarMeaning.EN_WHOSE to "That's the girl whose father is a pilot.",
                GrammarMeaning.EN_RELATIVE to "The man who lives next door is a doctor.",
                GrammarMeaning.EN_REPORTED to "She said that she was tired.",
                GrammarMeaning.EN_THAT_CLAUSE to "I think that he is right.",
                GrammarMeaning.EN_LOOK_FORWARD to "I look forward to seeing you.",
                GrammarMeaning.EN_GET_ADJECTIVE to "She got angry.",
                GrammarMeaning.EN_SO_DO_I to "I love pizza. So do I.",
                GrammarMeaning.EN_IT_ADJ_TO to "It is important to drink water.",
                GrammarMeaning.EN_QUESTION_TAG to "You're coming, aren't you?",
                GrammarMeaning.EN_SUCH_AS to "I like fruits such as apples and bananas.",
                GrammarMeaning.EN_ACCORDING_TO to "According to the news, it will snow.",
                GrammarMeaning.EN_HOWEVER to "It was cheap. However, it broke quickly.",
                GrammarMeaning.EN_SUFFIX_NESS to "Kindness is important.",
                GrammarMeaning.EN_SUFFIX_MENT to "The government made an announcement.",
                GrammarMeaning.EN_SUFFIX_TION to "We need more information.",
                GrammarMeaning.EN_SUFFIX_FUL to "What a beautiful day!",
                GrammarMeaning.EN_SUFFIX_LESS to "The phone is useless.",
                GrammarMeaning.EN_SUFFIX_ABLE to "This chair is very comfortable.",
                GrammarMeaning.EN_IF_UNREAL to "If I were you, I would go home.",
                GrammarMeaning.EN_PAST_PERFECT_CONTINUOUS to "She had been waiting for an hour when he arrived.",
                GrammarMeaning.EN_FUTURE_PERFECT to "By next year, I will have graduated.",
                GrammarMeaning.EN_PERFECT_PASSIVE to "The window has been broken.",
                GrammarMeaning.EN_CONTINUOUS_PASSIVE to "The road is being repaired.",
                GrammarMeaning.EN_GET_PASSIVE to "He got caught by the police.",
                GrammarMeaning.EN_SHOULD_HAVE to "You should have told me.",
                GrammarMeaning.EN_COULD_HAVE to "We could have won the game.",
                GrammarMeaning.EN_MUST_HAVE to "She must have forgotten.",
                GrammarMeaning.EN_MIGHT_HAVE to "He might have missed the bus.",
                GrammarMeaning.EN_WOULD_HAVE to "I would have helped you.",
                GrammarMeaning.EN_OUGHT_TO to "You ought to apologize.",
                GrammarMeaning.EN_IF_ONLY to "If only I had more time.",
                GrammarMeaning.EN_AS_IF to "He talks as if he knows everything.",
                GrammarMeaning.EN_AS_LONG_AS to "You can go out as long as you finish your homework.",
                GrammarMeaning.EN_DESPITE to "Despite the rain, we went out.",
                GrammarMeaning.EN_NOT_ONLY to "She is not only smart but also kind.",
                GrammarMeaning.EN_OTHERWISE to "Hurry up, otherwise we'll be late.",
                GrammarMeaning.EN_THEREFORE to "He was ill and therefore absent.",
                GrammarMeaning.EN_DUE_TO to "The flight was delayed due to fog.",
                GrammarMeaning.EN_WHATEVER to "Do whatever you want.",
                GrammarMeaning.EN_IF_PAST_UNREAL to "If I had known, I would have told you.",
                GrammarMeaning.EN_NEGATIVE_INVERSION to "Never have I seen such a mess.",
                GrammarMeaning.EN_SUBJUNCTIVE to "I suggest that he go home.",
                GrammarMeaning.EN_AS_AS to "He is as tall as his father.",
                GrammarMeaning.EN_THIS_THAT to "This song is great.",
                GrammarMeaning.EN_NOT to "I'm not hungry.",
                GrammarMeaning.EN_ALL to "All the students passed the test.",
                GrammarMeaning.EN_LIKE_SIMILAR to "She sings like a bird.",
                GrammarMeaning.EN_PLACE_MOVEMENT to "The cat is sleeping under the table.",
                GrammarMeaning.EN_AS to "She works as a nurse.",
                GrammarMeaning.EN_THAN to "He has more money than me.",
                GrammarMeaning.EN_ANOTHER to "Can I have another cup?",
                GrammarMeaning.EN_SUCH to "It was such a nice day.",
                GrammarMeaning.EN_EXCEPT to "Everyone came except Tom.",
                GrammarMeaning.EN_ING_AFTER_NOUN to "I saw a dog running in the park.",
            )

        /** Points found by the rules in EnglishGrammar.kt rather than the catalogue. */
        val RULE_MEANINGS =
            setOf(
                GrammarMeaning.EN_WH_QUESTION,
                GrammarMeaning.EN_YES_NO_QUESTION,
                GrammarMeaning.EN_IMPERATIVE,
                GrammarMeaning.EN_IF_REAL,
                GrammarMeaning.EN_WHEN_CLAUSE,
                GrammarMeaning.EN_IF_UNREAL,
                GrammarMeaning.EN_IF_PAST_UNREAL,
            )
    }

    private fun morphemes(text: String) = englishMorphemes(analyzer, text)

    private fun grammar(text: String) = englishGrammar(morphemes(text))

    @Test
    fun everyPointIsFoundInItsExampleSentence() {
        val missed = EXAMPLES.filter { (meaning, sentence) -> grammar(sentence).none { meaning in it.meanings } }
        val report =
            missed.joinToString("\n") { (meaning, sentence) ->
                "$meaning in $sentence: ${morphemes(
                    sentence,
                ).map { "${it.surface}/${it.pos}/${it.conjugationForm}" }} ${grammar(sentence).map { it.form }}"
            }
        assertTrue("Not found:\n$report", missed.isEmpty())
    }

    @Test
    fun everyCataloguePointHasAnExampleAndItsOwnMeaning() {
        val catalogueMeanings = ENGLISH_GRAMMAR_CATALOGUE.flatMap { it.meanings }
        assertEquals(catalogueMeanings.size, catalogueMeanings.toSet().size)
        assertEquals(catalogueMeanings.toSet() + RULE_MEANINGS, EXAMPLES.map { it.first }.toSet())
        assertEquals(EXAMPLES.size, EXAMPLES.map { it.first }.toSet().size)
    }

    @Test
    fun theCatalogueCoversCefrA1ToB2() {
        val levels = ENGLISH_GRAMMAR_CATALOGUE.map { it.level }.toSet()
        assertEquals(setOf(CefrLevel.A1, CefrLevel.A2, CefrLevel.B1, CefrLevel.B2), levels)
        assertTrue(ENGLISH_GRAMMAR_CATALOGUE.size >= 140)
    }

    /** A few points whose level the CEFR-J Grammar Profile or Wordlist sets, as the spec's rule reads them. */
    @Test
    fun levelsFollowCefrJ() {
        assertEquals(CefrLevel.A2, levelOf(GrammarMeaning.EN_GOING_TO))
        assertEquals(CefrLevel.A2, levelOf(GrammarMeaning.EN_PRESENT_PERFECT))
        assertEquals(CefrLevel.B1, levelOf(GrammarMeaning.EN_DONT_HAVE_TO))
        assertEquals(CefrLevel.B2, levelOf(GrammarMeaning.EN_PRESENT_PERFECT_CONTINUOUS))
        assertEquals(CefrLevel.A2, levelOf(GrammarMeaning.EN_ALTHOUGH))
        assertEquals(CefrLevel.B1, ruleLevel(GrammarMeaning.EN_IF_UNREAL, "If I were you, I would go home."))
        assertEquals(CefrLevel.B2, ruleLevel(GrammarMeaning.EN_IF_PAST_UNREAL, "If I had known, I would have told you."))
        assertEquals(CefrLevel.A1, ruleLevel(GrammarMeaning.EN_WHEN_CLAUSE, "Call me when you arrive."))
    }

    private fun levelOf(meaning: GrammarMeaning) = ENGLISH_GRAMMAR_CATALOGUE.single { meaning in it.meanings }.level

    private fun ruleLevel(
        meaning: GrammarMeaning,
        sentence: String,
    ) = grammar(sentence).first { meaning in it.meanings }.level
}
