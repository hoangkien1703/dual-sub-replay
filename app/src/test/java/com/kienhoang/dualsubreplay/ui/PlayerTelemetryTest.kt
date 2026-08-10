package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTelemetryTest {
    @Test
    fun parsesStructuredPlayerTelemetry() {
        val telemetry = PlayerTelemetryParser.parse(
            """
            {
              "playbackSecond": 12.5,
              "viewportWidth": 412,
              "viewportHeight": 800,
              "playerLeft": 0,
              "playerTop": 56,
              "playerRight": 412,
              "playerBottom": 287.75,
              "videoWidth": 1920,
              "videoHeight": 1080
            }
            """.trimIndent(),
        )

        requireNotNull(telemetry)
        assertEquals(12.5f, telemetry.playbackSecond, 0.001f)
        assertEquals(287.75f / 800f, telemetry.playerBottomFraction, 0.001f)
        assertEquals(VideoOrientation.LANDSCAPE, telemetry.orientation)
    }

    @Test
    fun parsesJsonStringReturnedByEvaluateJavascript() {
        val telemetry = PlayerTelemetryParser.parse(
            "\"{\\\"playbackSecond\\\":1,\\\"viewportWidth\\\":360,\\\"viewportHeight\\\":720," +
                "\\\"playerLeft\\\":0,\\\"playerTop\\\":40,\\\"playerRight\\\":360," +
                "\\\"playerBottom\\\":242.5,\\\"videoWidth\\\":1080,\\\"videoHeight\\\":1920}\"",
        )

        requireNotNull(telemetry)
        assertEquals(VideoOrientation.PORTRAIT, telemetry.orientation)
    }

    @Test
    fun rejectsInvalidMeasurements() {
        assertNull(
            PlayerTelemetryParser.parse(
                """{"playbackSecond":0,"viewportWidth":0,"viewportHeight":800,"playerLeft":0,"playerTop":0,"playerRight":1,"playerBottom":1,"videoWidth":0,"videoHeight":0}""",
            ),
        )
        assertNull(PlayerTelemetryParser.parse("null"))
    }

    @Test
    fun choosesOrientationFromIntrinsicVideoDimensions() {
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.fromDimensions(1920, 1080))
        assertEquals(VideoOrientation.PORTRAIT, VideoOrientation.fromDimensions(1080, 1920))
        assertEquals(VideoOrientation.LANDSCAPE, VideoOrientation.fromDimensions(0, 0))
    }

    @Test
    fun focusPersistsAcrossWatchPagesButResetsWhenLeavingVideo() {
        assertEquals(VideoDisplayMode.FOCUS, VideoDisplayMode.FOCUS.forPage(hasVideo = true))
        assertEquals(VideoDisplayMode.LEARNING, VideoDisplayMode.FOCUS.forPage(hasVideo = false))
    }
}
