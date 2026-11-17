package com.kienhoang.dualsubreplay.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The Whole transcript sheet (25kiuo, Discussion #81, 2026-11-17). */
class WholeTranscriptUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.cacheDir, "whole-transcript-test").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** A 2-hour talk with a line every 6 seconds; one line in the middle says "kimchi". */
    private fun transcript(): WholeTranscript =
        runBlocking {
            val rows =
                List(1_200) { index ->
                    val text = if (index == 600) "Let's make kimchi together." else "This is line ${index + 1} of the talk."
                    SubtitleSegment(index.toLong(), index * 6_000L, index * 6_000L + 5_000, text)
                }
            WholeTranscript(SubtitleStore.create(folder, rows), { text -> "Dịch: $text" }, null)
        }

    @Test
    fun theSheetOpensAtThePlayingLineTranslatesItAndJumpsWhereTapped() {
        val whole = transcript()
        var jumped: SubtitleSegment? = null
        var closed = false
        composeRule.setContent {
            DualSubTheme {
                WholeTranscriptSheet(
                    transcript = whole,
                    currentStartMs = 60_000,
                    exporter = TranscriptExporter(scope, context.contentResolver),
                    title = "Kimchi talk",
                    onJump = { jumped = it },
                    onDismiss = { closed = true },
                )
            }
        }
        composeRule.onNodeWithTag("whole_transcript").assertIsDisplayed()
        // Opens at the playing line (row 10 starts at 1:00), not at the start.
        composeRule.onNodeWithTag("whole_transcript_row_10").assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("whole_transcript_translation_10").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Dịch: This is line 11 of the talk.").assertIsDisplayed()
        saveUiEvidence("whole-transcript")
        composeRule.onNodeWithTag("whole_transcript_search").performTextInput("kimchi")
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("whole_transcript_row_600").fetchSemanticsNodes().isNotEmpty() }
        saveUiEvidence("whole-transcript-search")
        composeRule.onNodeWithTag("whole_transcript_row_600").performClick()
        composeRule.runOnIdle {
            assertEquals(3_600_000L, jumped?.startMs)
            assertTrue(closed)
        }
    }

    @Test
    fun exportAsksForTheFormatAndTheTranslation() {
        val whole = transcript()
        composeRule.setContent {
            DualSubTheme {
                WholeTranscriptSheet(whole, null, TranscriptExporter(scope, context.contentResolver), "Kimchi talk", {}, {})
            }
        }
        composeRule.onNodeWithTag("whole_transcript_export").performClick()
        composeRule.onNodeWithTag("whole_transcript_format_srt").assertIsDisplayed()
        composeRule.onNodeWithTag("whole_transcript_with_translation").assertIsDisplayed()
        Thread.sleep(500)
        saveUiEvidence("whole-transcript-export")
    }

    @Test
    fun theGearPopupOpensTheWholeTranscript() {
        var opened = false
        composeRule.setContent {
            DualSubTheme {
                QuickLanguageSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = listOf(CaptionLanguage("en", "English")),
                    onSourceChange = {},
                    onTargetChange = {},
                    onOpenAllSettings = {},
                    onOpenWholeTranscript = { opened = true },
                    onDismiss = {},
                )
            }
        }
        Thread.sleep(700)
        saveUiEvidence("gear-popup-whole-transcript")
        composeRule.onNodeWithTag("open_whole_transcript").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }
}
