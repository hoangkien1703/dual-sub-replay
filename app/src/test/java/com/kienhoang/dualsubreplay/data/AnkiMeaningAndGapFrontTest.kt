package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card fronts Meaning (Discussion #257) and Fill the gap (Discussion #81), spec docs/specs/2026-11-23-github-comments-batch.md. */
class AnkiMeaningAndGapFrontTest {
    @Test
    fun meaningAsksForTheWordAndTheBackGivesItWithItsSentence() {
        val template = ankiCardTemplate(CardFront.MEANING)
        val front = template.question
        assertTrue(front.startsWith("<div class=meaning"))
        assertTrue("{{Translation}}" in front)
        // Only a word saved without a meaning shows itself on the front.
        assertTrue("{{^Meaning}}<div class=word>{{Word}}</div>{{/Meaning}}" in front)
        assertFalse("{{Sentence}}" in front)
        assertFalse("{{Word audio}}" in front)
        val back = template.answer.removePrefix("{{FrontSide}}<hr id=answer>")
        listOf("{{Word}}", "{{Word audio}}", "{{Reading}}", "{{Sentence}}", "{{Sentence audio}}", "{{Source}}").forEach {
            assertTrue(it, it in back)
        }
        // The meaning is already above, on the front side.
        assertFalse("{{Meaning}}" in back)
        assertEquals(CardPlace.BACK, usualWordSoundPlace(CardFront.MEANING))
        assertFalse(readingCanBeOnFront(CardFront.MEANING))
    }

    @Test
    fun fillTheGapHidesTheBoldWordOnTheFrontAndShowsItOnTheBack() {
        val template = ankiCardTemplate(CardFront.FILL_GAP)
        assertTrue("<div class=\"sentence gap\"" in template.question)
        assertTrue(".gap b{-webkit-text-fill-color:transparent" in template.question)
        assertFalse("{{Word}}" in template.question)
        assertFalse("{{Word audio}}" in template.question)
        val back = template.answer.removePrefix("{{FrontSide}}<hr id=answer>")
        assertTrue(back.startsWith("<style>.gap b{-webkit-text-fill-color:inherit!important"))
        assertTrue("{{Word}}" in back && "{{Meaning}}" in back && "{{Translation}}" in back)
        assertEquals(CardPlace.BACK, usualWordSoundPlace(CardFront.FILL_GAP))
    }

    @Test
    fun newFrontsKeepTheirOwnTemplateKeys() {
        assertEquals("meaning", cardTemplateKey(CardFront.MEANING))
        assertEquals("fill_gap+picture", cardTemplateKey(CardFront.FILL_GAP, CardSides(pictureFront = true)))
        assertTrue(cardFrontNeedsUpdate("sentence", CardFront.FILL_GAP))
        assertEquals(CardFront.MEANING, storedCardFront("meaning"))
        assertEquals(CardFront.FILL_GAP, storedCardFront("fill_gap"))
    }

    @Test
    fun basicCardsGetAGapInPlaceOfTheWord() {
        assertEquals("I was <b>＿＿＿</b> at home.", gappedSentence("I was <b>looking</b> at home."))
        assertEquals("No bold word here", gappedSentence("No bold word here"))
        val word = sampleWord()
        val meaning = ankiConnectFields(listOf("Front", "Back"), word, front = CardFront.MEANING)
        assertTrue(meaning.getValue("Front").startsWith(ankiHtml(word.meaning)))
        assertFalse(word.word in meaning.getValue("Front"))
        assertTrue(word.word in meaning.getValue("Back"))
        val gap = ankiConnectFields(listOf("Front", "Back"), word, front = CardFront.FILL_GAP)
        assertTrue("<b>＿＿＿</b>" in gap.getValue("Front"))
        assertFalse("<b>${word.word}</b>" in gap.getValue("Front"))
        assertTrue("<b>${word.word}</b>" in gap.getValue("Back"))
    }

    private fun sampleWord() =
        SavedWord(
            id = "1",
            word = "looking",
            reading = null,
            wordLanguage = "en",
            meaningLanguage = "vi",
            meaning = "đang tìm",
            sentence = "I was looking at home.",
            translatedSentence = "Tôi đang tìm ở nhà.",
            videoId = "abcdefghijk",
            startMs = 1_000,
            endMs = 3_000,
            translated = false,
        )
}
