package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.JapaneseDictionaryStatus
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.learningSelection
import com.kienhoang.dualsubreplay.data.learningSourceLanguage
import com.kienhoang.dualsubreplay.data.savedWordFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseSelectionTest {
    private val sentence = "I'm really looking forward to seeing you."
    private val segment = SubtitleSegment(1, 1_000, 3_000, sentence, "Mình rất mong được gặp bạn.")

    @Test
    fun tapsSelectExtendAndClearAWordRange() {
        assertEquals(3..3, nextPhraseRange(null, 3))
        // A later word extends forward, an earlier one extends backward.
        assertEquals(3..5, nextPhraseRange(3..3, 5))
        assertEquals(1..5, nextPhraseRange(3..5, 1))
        // Tapping inside the selection clears it.
        assertNull(nextPhraseRange(3..3, 3))
        assertNull(nextPhraseRange(1..5, 4))
    }

    @Test
    fun phraseKeepsTheExactTextBetweenItsFirstAndLastWord() {
        val tokens = subtitleWordTokens(sentence, "en")
        val looking = tokens.indexOfFirst { it.text == "looking" }
        val to = tokens.indexOfFirst { it.text == "to" }

        val tap = phraseTap(sentence, tokens, looking..to, segment, translated = false)

        assertEquals("looking forward to", tap.token.text)
        assertEquals(listOf("looking", "forward", "to"), tap.parts.map { it.text })
        assertEquals(PartOfSpeech.OTHER, tap.token.partOfSpeech)
        assertEquals(sentence.indexOf("looking"), tap.token.startIndex)
        assertEquals(segment, tap.segment)
        assertFalse(tap.translated)
    }

    @Test
    fun japanesePhraseStaysWithoutAddedSpaces() {
        val text = "思ったより早く着いた"
        val tokens = subtitleWordTokens(text, "ja")
        assertTrue("needs several words, got ${tokens.map { it.text }}", tokens.size >= 3)

        val tap = phraseTap(text, tokens, 0..1, null, translated = false)

        assertEquals(text.substring(tokens[0].startIndex, tokens[1].endIndex), tap.token.text)
        assertFalse(tap.token.text.contains(' '))
    }

    @Test
    fun singleWordTapIsTheWordItself() {
        val tokens = subtitleWordTokens(sentence, "en")
        val tap = phraseTap(sentence, tokens, 2..2, segment, translated = false)

        assertEquals(tokens[2], tap.token)
        assertEquals(listOf(tokens[2]), tap.parts)
    }

    @Test
    fun translatedLinePhraseUsesTheSameTokensAsTapToLearn() {
        val translated = segment.translatedText!!
        val original = subtitleWordTokens(sentence, "en")
        val tokens = subtitleWordTokens(translated, "vi", original)
        val mong = tokens.indexOfFirst { it.text == "mong" }
        val gap = tokens.indexOfFirst { it.text == "gặp" }

        val tap = phraseTap(translated, tokens, mong..gap, segment, translated = true)

        assertEquals("mong được gặp", tap.token.text)
        assertEquals(tokens[mong], findWordAtOffset(translated, tokens[mong].startIndex, "vi", original))
        assertTrue(tap.translated)
    }

    @Test
    fun savedPhraseKeepsItsTextAndDiffersFromItsFirstWord() {
        val tokens = subtitleWordTokens(sentence, "en")
        val looking = tokens.indexOfFirst { it.text == "looking" }
        val phrase = phraseTap(sentence, tokens, looking..looking + 2, segment, translated = false)
        val word = phraseTap(sentence, tokens, looking..looking, segment, translated = false)

        val phraseSelection = learningSelection(phrase, "en", "vi", "dQw4w9WgXcQ")
        val saved = savedWordFrom(phraseSelection, "mong chờ", online = true)

        assertTrue(phraseSelection.isPhrase)
        assertEquals(3, phraseSelection.parts.size)
        assertEquals("looking forward to", saved.word)
        assertEquals(sentence, saved.sentence)
        assertNotEquals(savedWordFrom(learningSelection(word, "en", "vi", "dQw4w9WgXcQ"), "", true).id, saved.id)
    }

    @Test
    fun phraseReadingJoinsEveryWordsReading() {
        val tokens =
            listOf(
                AnalyzedToken("思った", 0, 3, PartOfSpeech.VERB, reading = "おもった"),
                AnalyzedToken("より", 3, 5, PartOfSpeech.PARTICLE, reading = "より"),
            )
        assertEquals("おもった より", phraseTap("思ったより", tokens, 0..1, null, false).token.reading)
        val partial = listOf(tokens[0], tokens[1].copy(reading = null))
        assertNull(phraseTap("思ったより", partial, 0..1, null, false).token.reading)
    }

    @Test
    fun barSitsAboveTheSelectionOrBelowWhenThereIsNoRoom() {
        val selection = IntRect(left = 200, top = 500, right = 400, bottom = 540)

        assertEquals(
            IntOffset(200, 500 - 10 - 80),
            phraseBarPosition(selection, popupWidth = 200, popupHeight = 80, windowWidth = 1_000, minTop = 60, gap = 10, margin = 16),
        )
        val nearTop = IntRect(left = 200, top = 100, right = 400, bottom = 140)
        assertEquals(
            IntOffset(200, 140 + 10),
            phraseBarPosition(nearTop, popupWidth = 200, popupHeight = 80, windowWidth = 1_000, minTop = 60, gap = 10, margin = 16),
        )
    }

    @Test
    fun barStaysInsideTheWindowHorizontally() {
        val atLeftEdge = IntRect(left = 0, top = 500, right = 40, bottom = 540)
        assertEquals(16, phraseBarPosition(atLeftEdge, 300, 80, 1_000, 0, 10, 16).x)
        val atRightEdge = IntRect(left = 960, top = 500, right = 1_000, bottom = 540)
        assertEquals(1_000 - 300 - 16, phraseBarPosition(atRightEdge, 300, 80, 1_000, 0, 10, 16).x)
        // Wider than the window: centered.
        assertEquals(-10, phraseBarPosition(atRightEdge, 1_020, 80, 1_000, 0, 10, 16).x)
    }

    @Test
    fun sourceLanguageFallsBackToThePreferenceThenEnglish() {
        assertEquals("ja", learningSourceLanguage("ja", "auto"))
        assertEquals("fr", learningSourceLanguage(null, "fr"))
        assertEquals("en", learningSourceLanguage(null, "auto"))
    }

    @Test
    fun selectingPausesOnceAndResumingClearsTheSelection() {
        var pauses = 0
        var stops = 0
        val controller = PhraseSelectionController()
        controller.actions = PhraseActions(pause = { pauses++ }, stopSpeech = { stops++ })
        val line = Any()

        controller.tap(line, 2)
        controller.tap(line, 4)
        assertEquals(PhraseSelection(line, 2..4), controller.selection)
        assertEquals(1, pauses)

        controller.clearAll()
        assertNull(controller.selection)
        assertEquals(1, stops)
        // Nothing selected: resuming again does nothing.
        controller.clearAll()
        assertEquals(1, stops)
    }

    @Test
    fun onlyASingleRealWordSpeaksOnSelectAndOnlyWhenTheSettingIsOn() {
        assertTrue(speaksOnSelect("思います", singleWord = true, autoPronounce = true))
        assertFalse(speaksOnSelect("思います", singleWord = true, autoPronounce = false))
        assertFalse(speaksOnSelect("台湾はもう", singleWord = false, autoPronounce = true))
        assertFalse(speaksOnSelect("。", singleWord = true, autoPronounce = true))
    }

    @Test
    fun theBarExplainsTheJapaneseDictionaryOnlyUntilItIsReady() {
        assertEquals(
            R.string.practice_japanese_dictionary_downloading,
            japaneseDictionaryNote(JapaneseDictionaryStatus.DOWNLOADING),
        )
        assertEquals(
            R.string.practice_japanese_dictionary_unavailable,
            japaneseDictionaryNote(JapaneseDictionaryStatus.UNAVAILABLE),
        )
        assertNull(japaneseDictionaryNote(JapaneseDictionaryStatus.READY))
        assertNull(japaneseDictionaryNote(JapaneseDictionaryStatus.IDLE))
    }
}
