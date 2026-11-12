package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CardFront
import com.kienhoang.dualsubreplay.data.CardPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word sound, Sentence sound and Picture (lion737, #81, 2026-11-12), and the play modes off screen (25kiuo, #81). */
class FlashcardPlacesTest {
    @Test
    fun aNewUserGetsTheCardsEveryVersionMade() {
        val settings = StudySettings()
        assertEquals(CardPlace.FRONT, settings.cardWordSound)
        assertEquals(CardPlace.BACK, settings.cardSentenceSound)
        assertEquals(CardPlace.OFF, settings.cardPicture)
    }

    @Test
    fun theOldSwitchesBecomeTheSamePlaces() {
        // Add audio to cards off: neither sound.
        assertEquals(CardPlace.OFF, earlierCardWordSound(audio = false, front = CardFront.WORD))
        assertEquals(CardPlace.OFF, earlierCardSentenceSound(audio = false, frontSound = true))
        // On: the word's voice with the word, the line's sound on the back unless it was put on the front.
        assertEquals(CardPlace.FRONT, earlierCardWordSound(audio = true, front = CardFront.WORD_AND_SENTENCE))
        assertEquals(CardPlace.BACK, earlierCardWordSound(audio = true, front = CardFront.SENTENCE))
        assertEquals(CardPlace.BACK, earlierCardSentenceSound(audio = true, frontSound = false))
        assertEquals(CardPlace.FRONT, earlierCardSentenceSound(audio = true, frontSound = true))
    }

    @Test
    fun thePlayModesAndTheLoopKeepFollowingTheVideoOffScreen() {
        assertFalse(studySteersPlayback(StudySettings(), loopLineId = null))
        assertTrue(studySteersPlayback(StudySettings(), loopLineId = 7L))
        assertTrue(studySteersPlayback(StudySettings(playMode = StudyPlayMode.REPEAT_LINE), loopLineId = null))
        assertTrue(studySteersPlayback(StudySettings(skipSilence = SkipSilence.entries.last()), loopLineId = null))
        // Pause on lines with one new word needs Known words; without it the video plays normally.
        assertFalse(studySteersPlayback(StudySettings(playMode = StudyPlayMode.NEW_WORD_LINES), loopLineId = null))
    }
}
