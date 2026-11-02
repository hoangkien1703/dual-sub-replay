package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PracticeDecksTest {
    private fun word(
        id: String,
        language: String,
        deck: String? = null,
        dueAt: Long = 0,
    ) = SavedWord(id, "w$id", null, language, "en", "meaning", "", null, null, 0, 0, false, false, dueAt, 0, deck)

    @Test fun wordsWithoutADeckSitInTheirLanguageDeck() {
        assertEquals(PracticeDeck(language = "ar"), deckOf(word("1", "ar")))
        assertEquals(PracticeDeck(name = "Egyptian Arabic"), deckOf(word("2", "ar", "Egyptian Arabic")))
    }

    @Test fun summariesCountWordsAndDueWordsNewestDeckFirst() {
        val words =
            listOf(
                word("1", "ar", "Egyptian Arabic", dueAt = 0),
                word("2", "ar", dueAt = 5_000),
                word("3", "ja", dueAt = 0),
                word("4", "ar", "Egyptian Arabic", dueAt = 9_000),
                word("5", "ar", dueAt = 0),
            )

        val summaries = deckSummaries(words, now = 1_000)

        assertEquals(
            listOf(
                DeckSummary(PracticeDeck(name = "Egyptian Arabic"), words = 2, due = 1),
                DeckSummary(PracticeDeck(language = "ar"), words = 2, due = 1),
                DeckSummary(PracticeDeck(language = "ja"), words = 1, due = 1),
            ),
            summaries,
        )
    }

    @Test fun deckNamesAreTidiedCappedAndReuseExistingSpelling() {
        assertEquals("Egyptian Arabic", cleanDeckName("  Egyptian \n  Arabic "))
        assertNull(cleanDeckName("   "))
        assertEquals(MAX_DECK_NAME_LENGTH, cleanDeckName("x".repeat(80))!!.length)
        assertEquals("Egyptian Arabic", resolveDeckName("egyptian arabic", listOf("Egyptian Arabic")))
        assertEquals("MSA", resolveDeckName(" MSA ", listOf("Egyptian Arabic")))
        assertNull(resolveDeckName(" ", listOf("Egyptian Arabic")))
    }

    @Test fun namedDecksAreListedOnceFromAToZ() {
        val words = listOf(word("1", "ar", "msa"), word("2", "ar"), word("3", "ar", "Egyptian"), word("4", "ja", "msa"))

        assertEquals(listOf("Egyptian", "msa"), namedDecks(words))
    }

    @Test fun backupKeepsTheDeckAndOldBackupsStayInLanguageDecks() {
        val saved = word("1", "ar", "Egyptian Arabic")

        assertEquals(saved, decodeWord(encodeWord(saved)))
        val old = encodeWord(saved).apply { remove("deck") }
        assertNull(decodeWord(old).deck)
        val document = parseTransfer(exportBackup(listOf(saved)))
        assertEquals("Egyptian Arabic", document.backup!!.single().deck)
    }

    @Test fun ankiTextCarriesTheDeckColumn() {
        val saved = word("1", "ar", "Egyptian Arabic").copy(word = "ازيك", meaning = "how are you")

        val document = parseTransfer(exportAnkiTsv(listOf(saved, saved.copy(id = "2", word = "كتاب", deck = null))))
        val words = previewImport(document, defaultTransferMapping(document), "ar", "en", emptyList()).words

        assertEquals(listOf("Egyptian Arabic", null), words.map { it.deck })
    }

    @Test fun importRejectsAnUntidyDeckName() {
        assertThrows(IllegalArgumentException::class.java) {
            validateTransferWord(word("1", "ar", "  spaced  "))
        }
    }
}
