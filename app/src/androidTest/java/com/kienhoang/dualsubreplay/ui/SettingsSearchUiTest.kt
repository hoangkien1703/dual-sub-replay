package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Search settings, Often changed and the loop button on the playing line (owner request 2026-11-06). */
class SettingsSearchUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)
    private val usage = context.getSharedPreferences("settings_usage", Context.MODE_PRIVATE)

    @After
    fun forgetTheSettings() {
        preferences.edit().apply { STUDY_SETTING_KEYS.forEach(::remove) }.commit()
        usage.edit().clear().commit()
    }

    private fun showSettings() {
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = emptyList(),
                    fontScale = 1f,
                    landscapeSplitEnabled = true,
                    onSourceChange = {},
                    onTargetChange = {},
                    onFontScaleChange = {},
                    onLandscapeSplitChange = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun searchFindsTheLoopButtonAndJumpsToIt() {
        showSettings()
        composeRule.onNodeWithTag("settings_search").performTextInput("loop")
        composeRule.onNodeWithTag("settings_search_result_LOOP_BUTTON").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("settings_section_study").fetchSemanticsNodes().isEmpty())
        saveUiEvidence("settings-search-results")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("settings_search_result_LOOP_BUTTON").performClick()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithTag("loop_button_switch").assertIsDisplayed()
        // Mid-glow: the setting the search landed on is highlighted for a moment. The settings page is a
        // dialog, the last root; a device screenshot would lag behind the paused test clock.
        saveUiEvidence("settings-search-jump", composeRule.onAllNodes(isRoot()).onLast().captureToImage())
        composeRule.mainClock.autoAdvance = true
        // The page stays scrolled to the setting once the glow is gone.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("loop_button_switch").assertIsDisplayed()
    }

    @Test
    fun aSearchWithNoMatchSaysSo() {
        showSettings()
        composeRule.onNodeWithTag("settings_search").performTextInput("zzzz")
        composeRule.onNodeWithTag("settings_search_no_results").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_search_clear").performClick()
        composeRule.onNodeWithTag("settings_section_study").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun oftenChangedListsSettingsChangedTwiceThisWeek() {
        val now = System.currentTimeMillis()
        val history =
            listOf(
                SettingChange("TEXT_SIZE", now - 3_600_000),
                SettingChange("TEXT_SIZE", now - 60_000),
                SettingChange("LOOP_BUTTON", now - 7_200_000),
                SettingChange("LOOP_BUTTON", now - 5_000_000),
                SettingChange("LOOP_BUTTON", now - 120_000),
                SettingChange("SKIP_ADS", now - 60_000),
            )
        usage.edit().putString("changes", encodeSettingHistory(history)).commit()
        showSettings()
        // Often changed shows under Search settings only while it is tapped (owner request 2026-11-06).
        assertTrue(composeRule.onAllNodesWithTag("often_changed_settings").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("settings_search").performClick()
        composeRule.onNodeWithTag("often_changed_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("often_changed_LOOP_BUTTON").assertIsDisplayed()
        composeRule.onNodeWithTag("often_changed_TEXT_SIZE").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("often_changed_SKIP_ADS").fetchSemanticsNodes().isEmpty())
        saveUiEvidence("settings-often-changed")

        composeRule.onNodeWithTag("often_changed_LOOP_BUTTON").performClick()
        composeRule.onNodeWithTag("loop_button_switch").assertIsDisplayed()
    }

    @Test
    fun changingASettingOnThePageIsCounted() {
        showSettings()
        composeRule.onNodeWithTag("settings_section_study").performScrollTo().performClick()
        composeRule.onNodeWithTag("loop_button_switch").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(preferences.getBoolean(LOOP_BUTTON_PREFERENCE, false))
            assertEquals(listOf("LOOP_BUTTON"), decodeSettingHistory(usage.getString("changes", null)).map { it.setting })
        }
        // One change is not enough yet: the search field says what will show there.
        composeRule.onNodeWithTag("settings_search").performScrollTo().performClick()
        composeRule.onNodeWithTag("often_changed_empty").assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(hasTestTag("often_changed_settings")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun languagesIsASectionAndSearchOpensIt() {
        showSettings()
        assertTrue(composeRule.onAllNodesWithTag("target_language_picker").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("settings_search").performTextInput("translate to")
        composeRule.onNodeWithTag("settings_search_result_TRANSLATE_TO").performClick()
        composeRule.onNodeWithTag("target_language_picker").assertIsDisplayed()
        saveUiEvidence("settings-languages-section")
    }

    @Test
    fun onlyThePlayingLineShowsTheLoopButton() {
        preferences.edit().putBoolean(LOOP_BUTTON_PREFERENCE, true).commit()
        val segments =
            (0 until 4).map { index ->
                SubtitleSegment(
                    id = index.toLong(),
                    startMs = index * 2_000L,
                    endMs = index * 2_000L + 1_900L,
                    originalText = "Original line $index",
                    translatedText = "Translated line $index",
                )
            }
        val loop = LineLoop()
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalLineLoop provides loop) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        SubtitleTimeline(DualSubUiState(segments = segments, currentIndex = 1, wordLearningEnabled = false), onReplay = {})
                    }
                }
            }
        }
        assertEquals(1, composeRule.onAllNodesWithTag("line_loop_button").fetchSemanticsNodes().size)
        saveUiEvidence("line-loop-off")
        composeRule.onNodeWithTag("line_loop_button").performClick()
        composeRule.runOnIdle { assertEquals(1L, loop.lineId) }
        saveUiEvidence("line-loop-on")
        composeRule.onNodeWithTag("line_loop_button").performClick()
        composeRule.runOnIdle { assertEquals(null, loop.lineId) }
    }

    @Test
    fun theLoopButtonIsOffByDefault() {
        val segments = listOf(SubtitleSegment(0, 0, 1_900, "Original line", "Translated line"))
        composeRule.setContent {
            DualSubTheme {
                CompositionLocalProvider(LocalLineLoop provides LineLoop()) {
                    Box(Modifier.fillMaxWidth().height(200.dp)) {
                        SubtitleTimeline(DualSubUiState(segments = segments, currentIndex = 0, wordLearningEnabled = false), onReplay = {})
                    }
                }
            }
        }
        assertTrue(composeRule.onAllNodesWithTag("line_loop_button").fetchSemanticsNodes().isEmpty())
    }
}
