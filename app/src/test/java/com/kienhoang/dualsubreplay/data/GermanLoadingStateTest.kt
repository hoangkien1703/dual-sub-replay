package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch

/** While German data loads, or when a word is not in the lexicon, nothing is said that the data does not support. */
class GermanLoadingStateTest {
    @Test fun nothingIsAvailableWhileTheLoaderRunsAndItAppearsOnceItFinishes() {
        val release = CountDownLatch(1)
        val loaded =
            BackgroundLoaded("test-german") {
                release.await()
                "ready"
            }
        assertNull("nothing while loading", loaded.get())
        assertNull("still nothing, and the second call must not start another load", loaded.get())
        assertEquals(0, loaded.revision.value)
        release.countDown()
        assertTrue(loaded.awaitReady(10_000))
        assertEquals("ready", loaded.get())
        assertEquals(1, loaded.revision.value)
    }

    @Test fun aLoaderThatFailsLeavesTheValueMissingForGood() {
        val loaded = BackgroundLoaded<String>("test-german-fail") { error("no data") }
        assertNull(loaded.get())
        assertFalse(loaded.awaitReady(2_000))
        assertNull(loaded.get())
        assertEquals(0, loaded.revision.value)
    }

    @Test fun aLexiconWithoutTheWordsGivesNoVerbReadingTableOrAdjectiveExplanation() {
        val tagger = LearnerData.open("de-pos.zip").use(OpenNlpPosTagger::load)
        val empty =
            ByteArrayInputStream(ByteArray(0)).use { verbs ->
                ByteArrayInputStream(ByteArray(0)).use { loadGermanLexicon(verbs, it) }
            }
        val line = "Ich trinke kalten Tee und gehe heute nach Hause."
        val sentence = germanSentence(tagger, empty, line)

        fun at(word: String) = line.indexOf(word).let { it to it + word.length }
        for (word in listOf("trinke", "gehe", "kalten", "Tee", "heute")) {
            val (from, to) = at(word)
            val details = germanWordDetails(sentence, from, to)
            assertNull("$word got a verb without a lexicon", details?.verb)
            assertNull("$word got a conjugation without a lexicon", germanSelectionConjugation(sentence, empty, from, to))
        }
        val (from, to) = at("kalten")
        assertNull("an adjective is not explained without its reading", germanWordRole(sentence, from, to))
        // words from the built-in closed lists are still explained: they need no lexicon
        val (undFrom, undTo) = at("und")
        assertNotNull(germanWordRole(sentence, undFrom, undTo))
    }
}
