package com.kienhoang.dualsubreplay.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerNavigatesDismissesAndKeepsWebViewAlive() {
        var created = 0
        var disposed = 0
        var practice = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                AppNavigation(onPractice = { practice++ }, onSettings = { settings++ }) { menu ->
                    Column {
                        menu()
                        AndroidView(factory = { context ->
                            created++
                            WebView(context).apply { loadData("<html>Offline video fixture</html>", "text/html", "UTF-8") }
                        }, modifier = Modifier.weight(1f))
                        DisposableEffect(Unit) { onDispose { disposed++ } }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Practice").assertIsDisplayed()
        compose.onNodeWithText("Open-source licenses").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_github_link").assertIsDisplayed()
        saveUiEvidence("navigation-drawer")
        compose.onNodeWithText("Open-source licenses").performClick()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.onNodeWithText("Practice").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Practice").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Practice").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, practice); assertEquals(1, created); assertEquals(0, disposed) }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(1, created); assertEquals(0, disposed) }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, height / 2f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Practice").assertIsNotDisplayed()
    }

    @Test fun livePanelShowsBothLanguagesAndRetriesWithoutReplay() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                LiveSubtitlePanel(DualSubUiState(liveFallback = true, liveOriginal = "Hello world",
                    liveTranslated = "Xin chào thế giới", resolvedSourceLanguage = "en",
                    wordHighlightEnabled = false, karaokeTimingMode = KaraokeTimingMode.TRANSCRIPT,
                    wordLearningEnabled = false), onRetry = { retries++ }, onWordClick = {})
            }
        }
        compose.onNodeWithText("Live subtitles").assertIsDisplayed()
        compose.onNodeWithText("Hello world").assertIsDisplayed()
        compose.onNodeWithText("Xin chào thế giới").assertIsDisplayed()
        compose.onNodeWithContentDescription("Replay this paragraph").assertDoesNotExist()
        saveUiEvidence("live-subtitle-fallback")
        compose.onNodeWithText("Retry full transcript").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("Hello world").assertIsDisplayed()
    }
}
