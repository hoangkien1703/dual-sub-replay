package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LanguageDownloadsDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val downloads = mutableListOf<String>()
    private val removals = mutableListOf<String>()
    private var retries = 0

    private fun show(initial: LanguageDownloadsState) {
        var state by mutableStateOf(initial)
        compose.setContent {
            DualSubTheme {
                LanguageDownloadsScreen(
                    state = state,
                    onDownload = { code ->
                        downloads += code
                        state = state.copy(downloading = state.downloading + code)
                    },
                    onRemove = { removals += it },
                    onRetry = { retries++ },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun showsBothGroupsAndDownloadsAndRemovesLanguages() {
        show(LanguageDownloadsState(available = listOf("ja", "vi", "fr"), downloaded = setOf("vi")))

        compose.onNodeWithText("On this device").assertIsDisplayed()
        compose.onNodeWithText("Built in").assertIsDisplayed()
        compose.onNodeWithTag("remove_language_vi").assertIsDisplayed()
        compose.onNodeWithTag("language_downloads_list").performScrollToNode(hasTestTag("download_language_ja"))
        compose.onNodeWithText("Available to download").assertIsDisplayed()
        compose.onNodeWithText("Includes the 13 MB dictionary for tapping Japanese words.").assertIsDisplayed()
        saveUiEvidence("language-downloads")

        compose.onNodeWithTag("download_language_ja").performClick()
        compose.onNodeWithTag("language_busy_ja").assertIsDisplayed()
        compose.onNodeWithTag("remove_language_vi").performClick()

        compose.runOnIdle {
            assertEquals(listOf("ja"), downloads)
            assertEquals(listOf("vi"), removals)
        }
    }

    @Test
    fun aFailedListLoadOffersRetry() {
        show(LanguageDownloadsState(loadFailed = true))

        compose.onNodeWithText("The language list could not be loaded. Check your connection.").assertIsDisplayed()
        compose.onNodeWithTag("retry_language_downloads").performClick()

        compose.runOnIdle { assertEquals(1, retries) }
    }
}
