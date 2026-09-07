package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleWord
import org.junit.Rule
import org.junit.Test

class CaptionPresentationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pauseVisibilityUpdatesBothPresentationsWithoutReopening() {
        val state =
            mutableStateOf(
                DualSubUiState(
                    activeVideoId = "dQw4w9WgXcQ",
                    currentIndex = 0,
                    originalVisibility = CaptionVisibility.ALWAYS,
                    translatedVisibility = CaptionVisibility.PAUSED,
                    segments =
                        listOf(
                            SubtitleSegment(
                                0,
                                0,
                                2000,
                                "Hello world",
                                "Xin chào",
                                listOf(SubtitleWord("Hello", 0, 1000), SubtitleWord("world", 1000, 2000)),
                            ),
                        ),
                ),
            )
        compose.setContent {
            MaterialTheme {
                Column(Modifier.systemBarsPadding()) {
                    val current = state.value
                    CompactSubtitleCard(
                        current.segments.single(),
                        true,
                        1f,
                        {},
                        activeWordIndex = current.activeWordIndex,
                        showOriginal = current.showOriginal(),
                        showTranslation = current.showTranslation(),
                    )
                    LearningSubtitleOverlay(
                        learningOverlayContent(current)!!,
                        1f,
                        0f,
                        orientation = Configuration.ORIENTATION_LANDSCAPE,
                        onPositionChange = {},
                        onSettings = {},
                        onClose = {},
                    )
                }
            }
        }
        compose.onAllNodesWithText("Xin chào").assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(playbackPaused = true, activeWordIndex = 1) }
        compose.onAllNodesWithText("Xin chào").assertCountEquals(2)
        // Capture the synchronized Compose frame, rather than a possibly older
        // compositor frame from UiAutomation immediately after state changes.
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "caption-pause-both-presentations.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { state.value = state.value.copy(playbackPaused = false) }
        compose.onAllNodesWithText("Xin chào").assertCountEquals(0)
    }
}
