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
            val template = ankiCardTemplate(front, frontSound = true)
            assertEquals(front.name, fields, shown(template.question + template.answer))
            assertTrue(front.name, "{{Sentence audio}}" in template.question)
            assertFalse(front.name, "{{Sentence audio}}" in template.answer.removePrefix("{{FrontSide}}<hr id=answer>"))
        }
        assertEquals(listOf("{{Word}}", "{{Word audio}}", "{{Sentence audio}}"), shown(ankiCardTemplate(CardFront.WORD, true).question))
        // Listening has nothing but the line's sound on the front already.
        assertEquals(ankiCardTemplate(CardFront.LISTENING), ankiCardTemplate(CardFront.LISTENING, true))
        assertEquals(CardOptions(front = CardFront.WORD, frontSound = true).soundOnFront, true)
        assertFalse(CardOptions(front = CardFront.LISTENING, frontSound = true).soundOnFront)
    }

    @Test
    fun turningSentenceSoundOnTheFrontOnOrOffChangesTheTemplateOnce() {
        assertTrue(cardFrontNeedsUpdate("word", CardFront.WORD, frontSound = true))
        assertFalse(cardFrontNeedsUpdate("word+sound", CardFront.WORD, frontSound = true))
        assertTrue(cardFrontNeedsUpdate("word+sound", CardFront.WORD))
        assertTrue(cardFrontNeedsUpdate(null, CardFront.WORD_AND_SENTENCE, frontSound = true))
        // The switch means nothing for Listening, so it never changes that template.
        assertFalse(cardFrontNeedsUpdate("listening", CardFront.LISTENING, frontSound = true))
        val memory = InMemoryCardFronts()
        memory.remember("x", CardFront.SENTENCE, frontSound = true)
        assertEquals("sentence+sound", memory.applied("x"))
        assertFalse(cardFrontNeedsUpdate(memory.applied("x"), CardFront.SENTENCE, frontSound = true))
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
}
