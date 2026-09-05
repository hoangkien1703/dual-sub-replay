package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.*
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SavedWordsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun practiceRevealsMeaningAndPersistsRatingWithoutAnyClips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-ui-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val word = savedWordFrom(LearningWordSelection(AnalyzedToken("learn", 0, 5, PartOfSpeech.VERB), "en", "vi",
            "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "Learn a word", "Học một từ"), false), "học", false, false)
        runBlocking { repository.save(word) }
        try {
            compose.setContent { DualSubTheme { if (visible.value) SavedWordsScreen(repository, {}, {}, {}) } }
            compose.waitForIdle()
            saveUiEvidence("saved-words")
            compose.onNodeWithTag("practice_words").performClick()
            compose.onNodeWithTag("review_meaning").assertDoesNotExist()
            compose.onNodeWithTag("reveal_meaning").performClick()
            compose.onNodeWithTag("review_meaning").assertTextEquals("học")
            compose.onNodeWithTag("play_online_clip").assertDoesNotExist()
            compose.onNodeWithTag("play_offline_clip").assertDoesNotExist()
            saveUiEvidence("word-practice")
            compose.onNodeWithTag("review_good").performScrollTo().performClick()
            compose.waitUntil(5000) { repository.words.value.single().intervalMs == 3 * DAY_MS }
            compose.onNodeWithTag("practice_complete").assertIsDisplayed()
            assertTrue(repository.words.value.single().dueAt > System.currentTimeMillis())
        } finally {
            // Dispose the screen before closing the isolated test database.
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            repository.close(); context.deleteDatabase(name)
        }
    }
}
