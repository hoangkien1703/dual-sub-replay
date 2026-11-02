package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.PartOfSpeech
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Decks in Practice and on the word card (owner decision 2026-11-02, issue #184). */
class PracticeDecksUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun word(
        id: String,
        language: String,
        deck: String? = null,
    ) = SavedWord(id, "w$id", null, language, "en", "meaning $id", "", null, null, 0, 0, false, false, 0, 0, deck)

    @Before fun closeDialogs() = closeSystemDialogs()

    @Test fun repositoryMovesEveryWordOfADeckInOneStep() =
        runBlocking {
            val name = "vocabulary-decks-${UUID.randomUUID()}.db"
            val repository = VocabularyRepository(context, name)
            try {
                repository.save(word("1", "ar", "Egyptian"))
                repository.save(word("2", "ar"))
                repository.save(word("3", "ar", "Egyptian"))

                val moved = repository.updateWhere({ it.deck == "Egyptian" }) { it.copy(deck = "Masri") }

                assertEquals(2, moved)
                assertEquals(listOf("Masri", null, "Masri"), repository.words.value.map { it.deck })
                repository.close()
                val reopened = VocabularyRepository(context, name)
                reopened.refresh()
                assertEquals(listOf("Masri", null, "Masri"), reopened.words.value.map { it.deck })
                reopened.close()
            } finally {
                repository.close()
                context.deleteDatabase(name)
            }
        }

    @Test fun practiceOpensOnDecksAndRenamesAndDeletesANamedDeck() {
        val name = "vocabulary-decks-ui-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        var visible by mutableStateOf(true)
        runBlocking {
            repository.save(word("1", "ja"))
            repository.save(word("2", "ar"))
            repository.save(word("3", "ar", "Egyptian Arabic"))
        }
        try {
            compose.setContent { DualSubTheme { if (visible) SavedWordsScreen(repository, {}, {}, {}) } }
            compose.waitForIdle()
            saveUiEvidence("practice-decks")
            // Newest deck first: the named deck, then Arabic, then Japanese.
            compose.onNodeWithTag("deck_2").assertIsDisplayed()
            compose.onNodeWithTag("deck_0").performClick()
            compose.onNodeWithTag("open_deck_name").assertTextEquals("Egyptian Arabic")
            compose.onNodeWithTag("saved_word_3").assertIsDisplayed()
            compose.onNodeWithTag("saved_word_2").assertDoesNotExist()
            saveUiEvidence("practice-open-deck")

            compose.onNodeWithTag("deck_menu").performClick()
            compose.onNodeWithTag("deck_rename").performClick()
            compose.onNodeWithTag("deck_name_field").performTextReplacement("Masri")
            compose.onNodeWithTag("deck_name_confirm").performClick()
            compose.waitUntil(5_000) { repository.words.value.any { it.deck == "Masri" } }
            compose.onNodeWithTag("open_deck_name").assertTextEquals("Masri")

            compose.onNodeWithTag("deck_menu").performClick()
            compose.onNodeWithTag("deck_delete").performClick()
            compose.onNodeWithTag("deck_delete_confirm").performClick()
            compose.waitUntil(5_000) { repository.words.value.all { it.deck == null } }
            // Back on the deck list: Arabic now holds both Arabic words, and Japanese stays apart.
            compose.onNodeWithTag("deck_1").assertIsDisplayed()
            compose.onNodeWithTag("deck_2").assertDoesNotExist()
            assertEquals(3, repository.words.value.size)
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun savedWordMovesToAnotherDeckFromItsPage() {
        val name = "vocabulary-decks-move-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        var visible by mutableStateOf(true)
        runBlocking {
            repository.save(word("1", "ar", "Masri"))
            repository.save(word("2", "ar"))
        }
        try {
            compose.setContent { DualSubTheme { if (visible) SavedWordsScreen(repository, {}, {}, {}) } }
            compose.onNodeWithTag("deck_0").performClick()
            compose.onNodeWithTag("saved_word_2").performClick()
            compose.onNodeWithTag("word_deck").performScrollTo().performClick()
            compose.onNodeWithTag("deck_option_0").performClick()
            compose.waitUntil(5_000) { repository.words.value.all { it.deck == "Masri" } }
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun wordCardPicksANewDeckBeforeSaving() {
        val selection =
            LearningWordSelection(
                AnalyzedToken("word", 0, 4, PartOfSpeech.NOUN),
                "en",
                "vi",
                "dQw4w9WgXcQ",
                SubtitleSegment(1, 1000, 3000, "a word", null),
                false,
            )
        var deck by mutableStateOf<String?>(null)
        var savedDeck: String? = "not saved"
        compose.setContent {
            DualSubTheme {
                WordLearningDialog(
                    selection,
                    false,
                    { "từ" },
                    { _, _ -> savedDeck = deck },
                    {},
                    null,
                    {},
                    deck = deck,
                    namedDecks = listOf("Phrasal verbs"),
                    onDeckChange = { deck = it },
                )
            }
        }
        compose.onNodeWithTag("deck_chip").performScrollTo().assertTextContains("English", substring = true)
        compose.onNodeWithTag("deck_chip").performClick()
        compose.onNodeWithTag("deck_option_new").performClick()
        compose.onNodeWithTag("deck_name_field").performTextReplacement("  phrasal   VERBS ")
        compose.onNodeWithTag("deck_name_confirm").performClick()
        // Only the letter case differed, so the existing deck's spelling is reused.
        compose.runOnIdle { assertEquals("Phrasal verbs", deck) }
        compose.onNodeWithTag("deck_chip").assertTextContains("Phrasal verbs", substring = true)
        saveUiEvidence("word-card-deck")
        compose.onNodeWithTag("save_word").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Phrasal verbs", savedDeck) }

        compose.onNodeWithTag("deck_chip").performScrollTo().performClick()
        compose.onNodeWithTag("deck_option_language").performClick()
        compose.runOnIdle { assertNull(deck) }
    }
}
