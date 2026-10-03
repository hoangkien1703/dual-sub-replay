package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubtitleUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsKeepsLanguageAndTextOptionsWithoutFocus() {
        var selectedLanguage: String? = null
        var selectedTarget: String? = null
        var dismissed = false
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = listOf(
                        CaptionLanguage("en", "English"),
                        CaptionLanguage("ja", "Japanese"),
                    ),
                    fontScale = 1f,
                    landscapeSplitEnabled = false,
                    onSourceChange = { selectedLanguage = it },
                    onTargetChange = { selectedTarget = it },
                    onFontScaleChange = {},
                    onLandscapeSplitChange = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Original language").assertIsDisplayed()
        composeRule.onNodeWithText("Translate to").assertIsDisplayed()
        composeRule.onNodeWithText("Vietnamese").assertIsDisplayed()
        saveUiEvidence("settings")
        composeRule.onNodeWithText("Focus").assertDoesNotExist()
        composeRule.onNodeWithTag("source_language_picker").performClick()
        composeRule.onNodeWithTag("language_option_source_ja").performClick()
        composeRule.runOnIdle { assertEquals("ja", selectedLanguage) }
        composeRule.onNodeWithTag("target_language_picker").performClick()
        composeRule.onNodeWithTag("language_search").performTextInput("English")
        composeRule.onNodeWithTag("language_option_target_en").performClick()
        composeRule.runOnIdle { assertEquals("en", selectedTarget) }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun gearPopupShowsOnlyLanguagesAndOpensFullSettings() {
        var selectedTarget: String? = null
        var openedAllSettings = false
        var dismissed = false
        composeRule.setContent {
            DualSubTheme {
                QuickLanguageSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = listOf(CaptionLanguage("en", "English")),
                    onSourceChange = {},
                    onTargetChange = { selectedTarget = it },
                    onOpenAllSettings = { openedAllSettings = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Subtitle languages").assertIsDisplayed()
        composeRule.onNodeWithText("Vietnamese").assertIsDisplayed()
        composeRule.onNodeWithText("Text size: 100%").assertDoesNotExist()
        composeRule.onNodeWithTag("target_language_picker").performClick()
        composeRule.onNodeWithTag("language_search").performTextInput("English")
        composeRule.onNodeWithTag("language_option_target_en").performClick()
        composeRule.runOnIdle { assertEquals("en", selectedTarget) }
        composeRule.onNodeWithText("Subtitle languages").assertIsDisplayed()
        composeRule.onNodeWithTag("open_all_settings").performClick()
        composeRule.runOnIdle { assertTrue(openedAllSettings) }
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun webPageErrorOffersReload() {
        var reloaded = false
        composeRule.setContent {
            DualSubTheme {
                WebPageErrorCard(
                    message = "Renderer stopped",
                    onReload = { reloaded = true },
                )
            }
        }

        composeRule.onNodeWithText("YouTube unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Renderer stopped").assertIsDisplayed()
        composeRule.onNodeWithText("Reload").performClick()
        composeRule.runOnIdle { assertTrue(reloaded) }
    }

    @Test
    fun activeSubtitleIsIdentifiedAndReplays() {
        var replayed = false
        composeRule.setContent {
            DualSubTheme {
                CompactSubtitleCard(
                    segment = SubtitleSegment(1, 1_000, 2_000, "Active original", "Bản dịch"),
                    active = true,
                    fontScale = 1f,
                    onReplay = { replayed = true },
                )
            }
        }

        composeRule.onNodeWithText("Active original")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Active subtitle"))
            .performClick()
        composeRule.onNodeWithText("Bản dịch").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(replayed) }
    }

    @Test
    fun inactiveSubtitleSupportsLargeTextAndVietnamese() {
        composeRule.setContent {
            DualSubTheme {
                CompactSubtitleCard(
                    segment = SubtitleSegment(2, 2_000, 3_000, "Next sentence", "Câu tiếp theo"),
                    active = false,
                    fontScale = 2f,
                    onReplay = {},
                )
            }
        }

        composeRule.onNodeWithText("Next sentence")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Subtitle"))
        composeRule.onNodeWithText("Câu tiếp theo").assertIsDisplayed()
    }

    @Test
    fun pausingKeepsTheActiveLineAndItsTranslationOnScreen() {
        val segments =
            (0 until 16).map { index ->
                SubtitleSegment(
                    id = index.toLong(),
                    startMs = index * 2_000L,
                    endMs = index * 2_000L + 1_900L,
                    originalText = "Original line $index",
                    translatedText = "Translated line $index, long enough to wrap onto a second line in the panel",
                )
            }
        var state by mutableStateOf(
            DualSubUiState(
                segments = segments,
                currentIndex = 0,
                translatedVisibility = CaptionVisibility.PAUSED,
                wordHighlightEnabled = false,
                wordLearningEnabled = false,
            ),
        )
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxWidth().height(360.dp).testTag("timeline_viewport")) {
                    SubtitleTimeline(state, onReplay = {})
                }
            }
        }

        fun viewport() = composeRule.onNodeWithTag("timeline_viewport").getUnclippedBoundsInRoot()

        fun rowBounds(text: String): DpRect? =
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().firstOrNull()?.let {
                composeRule.onNodeWithText(text).getUnclippedBoundsInRoot()
            }

        fun fits(bounds: DpRect?) = bounds != null && bounds.top >= viewport().top && bounds.bottom <= viewport().bottom

        // Play forward one line at a time until the spoken line is the last one that fits, as in the report.
        var active = 0
        while (fits(rowBounds("Original line ${active + 1}")) && rowBounds("Original line ${active + 2}") != null) {
            active++
            state = state.copy(currentIndex = active)
            composeRule.waitForIdle()
        }
        assertTrue("active line must fit before the pause", fits(rowBounds("Original line $active")))

        state = state.copy(playbackPaused = true)
        composeRule.waitForIdle()

        val original = rowBounds("Original line $active")
        val translation = rowBounds("Translated line $active, long enough to wrap onto a second line in the panel")
        assertTrue("active original stays on screen: $original in ${viewport()}", fits(original))
        assertTrue("active translation stays on screen: $translation in ${viewport()}", fits(translation))
    }

    @Test
    fun pausingOnTheLastLineAtTheEndOfAVideoKeepsItOnScreen() {
        val segments =
            (0 until 16).map { index ->
                SubtitleSegment(
                    id = index.toLong(),
                    startMs = index * 2_000L,
                    endMs = index * 2_000L + 1_900L,
                    originalText = "Original line $index",
                    translatedText = "Translated line $index, long enough to wrap onto a second line in the panel",
                )
            }
        // The video reaches its last line; the list is scrolled to its end.
        var state by mutableStateOf(
            DualSubUiState(
                segments = segments,
                currentIndex = segments.lastIndex,
                translatedVisibility = CaptionVisibility.PAUSED,
                wordHighlightEnabled = false,
                wordLearningEnabled = false,
            ),
        )
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxWidth().height(360.dp).testTag("timeline_viewport")) {
                    SubtitleTimeline(state, onReplay = {})
                }
            }
        }
        composeRule.waitForIdle()

        // The video ends and YouTube pauses it, so every row shows its translation. Before the fix
        // the list scrolled back and forth without end here and the app stopped responding.
        state = state.copy(playbackPaused = true)
        composeRule.waitForIdle()

        val viewport = composeRule.onNodeWithTag("timeline_viewport").getUnclippedBoundsInRoot()
        val original = composeRule.onNodeWithText("Original line 15").getUnclippedBoundsInRoot()
        val translation =
            composeRule
                .onNodeWithText("Translated line 15, long enough to wrap onto a second line in the panel")
                .getUnclippedBoundsInRoot()
        assertTrue(
            "last original stays on screen: $original in $viewport",
            original.top >= viewport.top && original.bottom <= viewport.bottom,
        )
        assertTrue(
            "last translation stays on screen: $translation in $viewport",
            translation.top >= viewport.top && translation.bottom <= viewport.bottom,
        )
    }

    @Test
    fun jumpBackPillShowsWhileScrollingAwayAndReturnsToTheSpokenLine() {
        val segments =
            (0 until 40).map { index ->
                SubtitleSegment(
                    id = index.toLong(),
                    startMs = index * 2_000L,
                    endMs = index * 2_000L + 1_900L,
                    originalText = "Original line $index",
                    translatedText = "Translated line $index",
                )
            }
        val state =
            DualSubUiState(
                segments = segments,
                currentIndex = 4,
                playbackPaused = true,
                wordHighlightEnabled = false,
                wordLearningEnabled = false,
            )
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxWidth().height(360.dp).testTag("timeline_viewport")) {
                    SubtitleTimeline(state, onReplay = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Original line 4").assertIsDisplayed()
        composeRule.onNodeWithTag("jump_back_pill").assertDoesNotExist()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("timeline_viewport").performTouchInput { swipeUp() }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.onNodeWithTag("jump_back_pill").assertExists()
        composeRule.onNodeWithText("Now playing · 0:08").assertExists()

        // Stopping to read hides it; scrolling again brings it back.
        composeRule.mainClock.advanceTimeBy(JUMP_BACK_LINGER_MS + 3_000)
        composeRule.onNodeWithTag("jump_back_pill").assertDoesNotExist()
        composeRule.onNodeWithTag("timeline_viewport").performTouchInput { swipeUp() }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.onNodeWithTag("jump_back_pill").assertExists().performClick()

        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Original line 4").assertIsDisplayed()
        composeRule.onNodeWithTag("jump_back_pill").assertDoesNotExist()
    }

    @Test
    fun translationFailureKeepsOriginalRowsAndOffersRetry() {
        var retries = 0
        val state =
            DualSubUiState(
                activeVideoId = "video",
                segments = listOf(SubtitleSegment(0, 0, 2000, "Original line 0")),
                currentIndex = 0,
                wordLearningEnabled = false,
                translationError = "The translation model download took too long.",
            )
        composeRule.setContent {
            DualSubTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    TranslatedSubtitleTimeline(state, onRetryTranslation = { retries++ }, onWordClick = {}, onReplay = {})
                }
            }
        }
        composeRule.onNodeWithText("Original line 0").assertIsDisplayed()
        composeRule.onNodeWithText("Translation unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("The translation model download took too long.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry translation").performClick()
        assertEquals(1, retries)
    }
}
