package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.AnkiDroidNotes
import com.kienhoang.dualsubreplay.data.KnownWordsRepository
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.VideoComprehension
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class StudyToolsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)

    @After
    fun forgetTheSettings() {
        preferences
            .edit()
            .apply { (STUDY_SETTING_KEYS + APP_COLOR_THEME_PREFERENCE).forEach(::remove) }
            .commit()
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
        composeRule.onNodeWithTag("settings_section_study").performScrollTo().performClick()
    }

    @Test
    fun theStudyToolsSectionTurnsEachToolOnAndOff() {
        showSettings()
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo().performClick()
        composeRule.onNodeWithTag("study_repeat_3").performScrollTo().performClick()
        composeRule.onNodeWithTag("skip_silence_2x").performScrollTo().performClick()
        composeRule.onNodeWithTag("blur_box_switch").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("repeat_line", preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null))
            assertEquals(3, preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, 0))
            assertEquals("2x", preferences.getString(SKIP_SILENCE_PREFERENCE, null))
            assertTrue(preferences.getBoolean(BLUR_BOX_PREFERENCE, false))
        }
        composeRule.onNodeWithTag("study_play_mode_repeat_line").performScrollTo()
        saveUiEvidence("study-tools-settings")

        // Mark new words belongs to known words, so it goes with them.
        composeRule.onNodeWithTag("mark_new_words_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("known_words_switch").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("mark_new_words_switch")).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("page_token_switch").performScrollTo()
        saveUiEvidence("study-tools-settings-lower")
    }

    @Test
    fun theStudyToolsSectionFollowsTheLightTheme() {
        preferences.edit().putString(APP_COLOR_THEME_PREFERENCE, AppColorThemeOption.LIGHT.key).commit()
        showSettings()
        composeRule.onNodeWithTag("study_play_mode_normal").performScrollTo()
        composeRule.waitForIdle()
        saveUiEvidence("study-tools-settings-light")
    }

    @Test
    fun theScoreChipAndNewWordMarksShowForAVideo() {
        val segment = SubtitleSegment(1, 0, 2_000, "I like cats", "Tôi thích mèo")
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val tools =
                remember {
                    StudyTools(KnownWordsRepository(File(context.cacheDir, "known-words-ui-test")), AnkiDroidNotes(context), scope).apply {
                        settings = StudySettings(markNewWords = true)
                        language = "en"
                        known = setOf("i", "like")
                        comprehension = VideoComprehension(3, 2, 0, setOf("cats"), setOf(0))
                    }
                }
            CompositionLocalProvider(LocalStudyTools provides tools) {
                DualSubTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(12.dp)) {
                            ComprehensionChip()
                            CompactSubtitleCard(segment, true, 1f, {}, resolvedSourceLanguage = "en")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Known 66%").assertIsDisplayed()
        saveUiEvidence("study-known-words-chip", composeRule.onRoot().captureToImage())
        composeRule.onNodeWithTag("comprehension_chip").performClick()
        composeRule.onNodeWithTag("mark_all_known").performClick()
        composeRule.onNodeWithTag("confirm_mark_all_known").assertIsDisplayed()
        saveUiEvidence("study-mark-all-known")
    }
}
