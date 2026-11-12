package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card fronts and pictures (spec docs/specs/2026-11-10-anki-card-picture-and-front.md). */
class AnkiCardLayoutTest {
    private val fields = ANKI_FIELDS.map { "{{$it}}" }

    private fun shown(template: String) = fields.filter { it in template }

    @Test
    fun wordAndSentenceIsTheCardEveryVersionMadeBefore() {
        assertEquals(AnkiCardTemplate(ANKI_QUESTION_FORMAT, ANKI_ANSWER_FORMAT), ankiCardTemplate(CardFront.WORD_AND_SENTENCE))
        assertEquals(CardFront.WORD_AND_SENTENCE, storedCardFront(null))
        assertEquals(CardFront.WORD_AND_SENTENCE, storedCardFront("gone"))
        assertEquals(CardFront.LISTENING, storedCardFront("listening"))
    }

    @Test
    fun everyFieldShowsOnEveryCardAndTheFrontHoldsOnlyItsOwn() {
        CardFront.entries.forEach { front ->
            val template = ankiCardTemplate(front)
            assertEquals(front.name, fields, shown(template.question + template.answer))
            assertTrue(front.name, template.answer.startsWith("{{FrontSide}}<hr id=answer>"))
        }
        assertEquals(listOf("{{Word}}", "{{Word audio}}"), shown(ankiCardTemplate(CardFront.WORD).question))
        assertEquals(listOf("{{Sentence}}"), shown(ankiCardTemplate(CardFront.SENTENCE).question))
        // Listening plays the line; until its sound is there the sentence shows, so Anki never makes an empty card.
        val listening = ankiCardTemplate(CardFront.LISTENING)
        assertEquals(listOf("{{Sentence}}", "{{Sentence audio}}"), shown(listening.question))
        assertTrue("{{^Sentence audio}}<div class=sentence>{{Sentence}}</div>{{/Sentence audio}}" in listening.question)
        assertTrue("{{#Sentence audio}}<div class=sentence>{{Sentence}}</div>{{/Sentence audio}}" in listening.answer)
    }

    @Test
    fun theTemplateChangesOnlyWhenTheFrontPickDoes() {
        // Never set by this version: the old fixed word-and-sentence card, left as the learner may have edited it.
        assertFalse(cardFrontNeedsUpdate(null, CardFront.WORD_AND_SENTENCE))
        assertTrue(cardFrontNeedsUpdate(null, CardFront.WORD))
        assertFalse(cardFrontNeedsUpdate("word", CardFront.WORD))
        assertTrue(cardFrontNeedsUpdate("word", CardFront.SENTENCE))
        val memory = InMemoryCardFronts()
        memory.remember("x", CardFront.SENTENCE)
        assertEquals("sentence", memory.applied("x"))
    }

    @Test
    fun sentenceSoundOnTheFrontMovesTheLinesSoundFromTheBack() {
        CardFront.entries.forEach { front ->
            val template = ankiCardTemplate(front, CardSides(sentenceSoundFront = true))
            assertEquals(front.name, fields, shown(template.question + template.answer))
            assertTrue(front.name, "{{Sentence audio}}" in template.question)
            assertFalse(front.name, "{{Sentence audio}}" in template.answer.removePrefix("{{FrontSide}}<hr id=answer>"))
        }
        assertEquals(
            listOf("{{Word}}", "{{Word audio}}", "{{Sentence audio}}"),
            shown(ankiCardTemplate(CardFront.WORD, CardSides(sentenceSoundFront = true)).question),
        )
        // Listening has nothing but the line's sound on the front already.
        assertEquals(ankiCardTemplate(CardFront.LISTENING), ankiCardTemplate(CardFront.LISTENING, CardSides(sentenceSoundFront = true)))
        assertEquals(CardSides(sentenceSoundFront = true), CardOptions(front = CardFront.WORD, sentenceSound = CardPlace.FRONT).sides)
        assertEquals(CardSides(), CardOptions(front = CardFront.WORD, sentenceSound = CardPlace.BACK).sides)
    }

    @Test
    fun turningSentenceSoundOnTheFrontOnOrOffChangesTheTemplateOnce() {
        assertTrue(cardFrontNeedsUpdate("word", CardFront.WORD, CardSides(sentenceSoundFront = true)))
        assertFalse(cardFrontNeedsUpdate("word+sound", CardFront.WORD, CardSides(sentenceSoundFront = true)))
        assertTrue(cardFrontNeedsUpdate("word+sound", CardFront.WORD))
        assertTrue(cardFrontNeedsUpdate(null, CardFront.WORD_AND_SENTENCE, CardSides(sentenceSoundFront = true)))
        // The switch means nothing for Listening, so it never changes that template.
        assertFalse(cardFrontNeedsUpdate("listening", CardFront.LISTENING, CardSides(sentenceSoundFront = true)))
        val memory = InMemoryCardFronts()
        memory.remember("x", CardFront.SENTENCE, CardSides(sentenceSoundFront = true))
        assertEquals("sentence+sound", memory.applied("x"))
        assertFalse(cardFrontNeedsUpdate(memory.applied("x"), CardFront.SENTENCE, CardSides(sentenceSoundFront = true)))
    }

    @Test
    fun aPictureFieldNamesOnlyAPlainMediaFile() {
        assertEquals(
            "<img src=\"dualsub_a-1-2.jpg\" style=\"max-width:100%;max-height:50vh;border-radius:8px\">",
            ankiPicture("/dualsub_a-1-2.jpg"),
        )
        assertEquals("", ankiPicture(null))
        assertEquals("", ankiPicture("a\".jpg"))
        assertEquals("", ankiPicture("a b.jpg"))
        assertEquals("", ankiPicture("dir/a.jpg"))
        assertEquals("", ankiPicture("<script>.jpg"))
    }

    @Test
    fun theWordsVoiceGoesWhereItIsPutAndTheCardStillShowsEveryField() {
        // Word sound → Back on a word front: the front is only the word, the back plays it.
        val back = ankiCardTemplate(CardFront.WORD, CardSides(wordSoundFront = false))
        assertEquals(listOf("{{Word}}"), shown(back.question))
        assertTrue(back.answer.startsWith("{{FrontSide}}<hr id=answer>{{Word audio}}"))
        // Word sound → Front on a sentence front, before the line's sound.
        val front = ankiCardTemplate(CardFront.SENTENCE, CardSides(wordSoundFront = true, sentenceSoundFront = true))
        assertEquals(listOf("{{Sentence}}", "{{Word audio}}", "{{Sentence audio}}"), shown(front.question))
        assertTrue(front.question.indexOf("{{Word audio}}") < front.question.indexOf("{{Sentence audio}}"))
        assertFalse("{{Word audio}}" in front.answer)
        // Its usual side is the template every version made.
        assertEquals(ankiCardTemplate(CardFront.WORD), ankiCardTemplate(CardFront.WORD, CardSides(wordSoundFront = true)))
        assertEquals(ankiCardTemplate(CardFront.SENTENCE), ankiCardTemplate(CardFront.SENTENCE, CardSides(wordSoundFront = false)))
        CardFront.entries.forEach { card ->
            listOf(null, true, false).forEach { word ->
                listOf(true, false).forEach { picture ->
                    val template = ankiCardTemplate(card, CardSides(word, sentenceSoundFront = true, pictureFront = picture))
                    assertEquals("$card $word $picture", fields, shown(template.question + template.answer))
                    // Each sound plays once: the back shows the front above it.
                    assertEquals(1, Regex("\\{\\{Word audio}}").findAll(template.question + template.answer).count())
                }
            }
        }
    }

    @Test
    fun aPictureOnTheFrontLeavesTheVideoLinkOnTheBack() {
        val template = ankiCardTemplate(CardFront.WORD, CardSides(pictureFront = true))
        assertTrue("<div class=\"source pf\">{{Source}}</div><style>.pf a,.pf br{display:none}</style>" in template.question)
        assertTrue(template.answer.endsWith("<div class=\"source sl\">{{Source}}</div><style>.sl img,.sl br{display:none}</style>"))
        // Back (the default) keeps the plain Source on the back only.
        assertFalse("{{Source}}" in ankiCardTemplate(CardFront.WORD).question)
    }

    @Test
    fun aCardTemplateKeyNamesEveryPlaceAndOldKeysStillMatch() {
        assertEquals("word_sentence", cardTemplateKey(CardFront.WORD_AND_SENTENCE, CardSides(wordSoundFront = true)))
        assertEquals("word+sound", cardTemplateKey(CardFront.WORD, CardSides(sentenceSoundFront = true)))
        assertEquals("word+wordback", cardTemplateKey(CardFront.WORD, CardSides(wordSoundFront = false)))
        assertEquals("sentence+wordfront+picture", cardTemplateKey(CardFront.SENTENCE, CardSides(true, pictureFront = true)))
        assertEquals("listening", cardTemplateKey(CardFront.LISTENING, CardSides(wordSoundFront = false, sentenceSoundFront = true)))
        assertFalse(
            cardFrontNeedsUpdate("word+sound", CardFront.WORD, CardOptions(CardPlace.FRONT, CardPlace.FRONT, front = CardFront.WORD).sides),
        )
        assertTrue(cardFrontNeedsUpdate("word", CardFront.WORD, CardOptions(CardPlace.BACK, CardPlace.BACK, front = CardFront.WORD).sides))
        assertTrue(cardFrontNeedsUpdate("word", CardFront.WORD, CardOptions(picture = CardPlace.FRONT, front = CardFront.WORD).sides))
        // A medium that is off changes nothing: its field is empty wherever the template puts it.
        assertFalse(
            cardFrontNeedsUpdate("word", CardFront.WORD, CardOptions(CardPlace.OFF, CardPlace.OFF, CardPlace.OFF, CardFront.WORD).sides),
        )
    }

    @Test
    fun theWordsVoiceUsuallyGoesWithTheWord() {
        assertEquals(CardPlace.FRONT, usualWordSoundPlace(CardFront.WORD_AND_SENTENCE))
        assertEquals(CardPlace.FRONT, usualWordSoundPlace(CardFront.WORD))
        assertEquals(CardPlace.BACK, usualWordSoundPlace(CardFront.SENTENCE))
        assertEquals(CardPlace.BACK, usualWordSoundPlace(CardFront.LISTENING))
        assertEquals(CardPlace.BACK, storedCardPlace("back", CardPlace.OFF))
        assertEquals(CardPlace.OFF, storedCardPlace("sideways", CardPlace.OFF))
        assertEquals(CardPlace.FRONT, storedCardPlace(null, CardPlace.FRONT))
    }
}
