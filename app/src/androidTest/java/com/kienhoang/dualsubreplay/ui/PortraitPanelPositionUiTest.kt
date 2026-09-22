package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PortraitPanelPositionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun panelMovesAndResizesWithinMeasuredPortraitContent() {
        val offset = mutableFloatStateOf(DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF90CAF9))) {
                    Text("Video remains visible above the transcript", color = Color.Black)
                    PortraitSubtitlePanelLayout(
                        baselineHeightFraction = 0.60f,
                        offsetFraction = offset.floatValue,
                    ) { modifier ->
                        Column(modifier.background(Color(0xFF061719)).testTag("measured_portrait_panel")) {
                            Text("Japanese → English", color = Color.White)
                            Text("Scrollable dual-subtitle timeline", color = Color(0xFF9EDCE4))
                        }
                    }
                }
            }
        }

        val defaultBounds =
            composeRule
                .onNodeWithTag("measured_portrait_panel")
                .fetchSemanticsNode()
                .boundsInRoot
        saveUiEvidence("portrait-panel-default", composeRule.onRoot().captureToImage())
        composeRule.runOnIdle { offset.floatValue = -0.2f }
        val higherBounds =
            composeRule
                .onNodeWithTag("measured_portrait_panel")
                .fetchSemanticsNode()
                .boundsInRoot
        saveUiEvidence("portrait-panel-higher", composeRule.onRoot().captureToImage())
        composeRule.runOnIdle { offset.floatValue = 0.2f }
        val lowerBounds =
            composeRule
                .onNodeWithTag("measured_portrait_panel")
                .fetchSemanticsNode()
                .boundsInRoot
        saveUiEvidence("portrait-panel-lower", composeRule.onRoot().captureToImage())

        assertTrue(higherBounds.top < defaultBounds.top)
        assertTrue(higherBounds.height > defaultBounds.height)
        assertTrue(higherBounds.bottom < defaultBounds.bottom)
        assertTrue(lowerBounds.top > defaultBounds.top)
        assertTrue(lowerBounds.height < defaultBounds.height)
        assertEquals(defaultBounds.bottom, lowerBounds.bottom, 1f)
    }

    @Test fun lowestPanelPositionKeepsFinalLongSubtitleReachable() {
        var replayed = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 300.dp, height = 600.dp)) {
                    PortraitSubtitlePanelLayout(
                        baselineHeightFraction = 0.60f,
                        offsetFraction = 0.20f,
                    ) { modifier ->
                        Column(modifier.testTag("lowest_portrait_panel")) {
                            Box(Modifier.height(PORTRAIT_PANEL_HEADER_HEIGHT_DP.dp))
                            LazyColumn(Modifier.fillMaxSize().testTag("portrait_subtitle_list")) {
                                items((0 until 20).toList()) { index ->
                                    if (index == 19) {
                                        Text("Final original subtitle with enough words to wrap onto another line")
                                        Text("Final translated subtitle remains reachable at the lowest position")
                                        Button(onClick = { replayed = true }) { Text("Replay final subtitle") }
                                    } else {
                                        Text("Subtitle row $index")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("portrait_subtitle_list").performScrollToIndex(19)
        composeRule
            .onNodeWithText("Final translated subtitle remains reachable at the lowest position")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Replay final subtitle").performClick()
        composeRule.runOnIdle { assertTrue(replayed) }
    }
}
