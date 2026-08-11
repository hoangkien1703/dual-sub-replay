package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTelemetryTest {
    @Test
    fun parsesStructuredPlayerTelemetryAndHealth() {
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
              "videoHeight": 1080,
              "readyState": 4,
              "networkState": 1,
              "decodedFrameCount": 240
            }
            """.trimIndent(),
        )

        requireNotNull(telemetry)
        assertEquals(12.5f, telemetry.playbackSecond, 0.001f)
        assertEquals(287.75f / 800f, telemetry.playerBottomFraction, 0.001f)
        assertEquals(4, telemetry.readyState)
        assertEquals(1, telemetry.networkState)
        assertEquals(240L, telemetry.decodedFrameCount)
    }

    @Test
    fun parsesJsonStringReturnedByEvaluateJavascript() {
        val telemetry = PlayerTelemetryParser.parse(
            "\"{\\\"playbackSecond\\\":1,\\\"viewportWidth\\\":360,\\\"viewportHeight\\\":720," +
                "\\\"playerLeft\\\":0,\\\"playerTop\\\":40,\\\"playerRight\\\":360," +
                "\\\"playerBottom\\\":242.5,\\\"videoWidth\\\":1080,\\\"videoHeight\\\":1920," +
                "\\\"readyState\\\":2,\\\"networkState\\\":2,\\\"decodedFrameCount\\\":0}\"",
        )

        requireNotNull(telemetry)
        assertEquals(2, telemetry.readyState)
        assertEquals(2, telemetry.networkState)
    }

    @Test
    fun rejectsInvalidMeasurementsAndMediaStates() {
        assertNull(
            PlayerTelemetryParser.parse(
                """{"playbackSecond":0,"viewportWidth":0,"viewportHeight":800,"playerLeft":0,"playerTop":0,"playerRight":1,"playerBottom":1,"videoWidth":0,"videoHeight":0,"readyState":0,"networkState":0}""",
            ),
        )
        assertNull(
            PlayerTelemetryParser.parse(
                """{"playbackSecond":0,"viewportWidth":400,"viewportHeight":800,"playerLeft":0,"playerTop":0,"playerRight":400,"playerBottom":225,"videoWidth":0,"videoHeight":0,"readyState":8,"networkState":0}""",
            ),
        )
        assertNull(PlayerTelemetryParser.parse("null"))
    }

    @Test
    fun playerBottomUsesFallbackAndIgnoresTinyChanges() {
        assertEquals(0.38f, resolvedPlayerBottomFraction(0.38f, null), 0.001f)
        assertEquals(0.38f, resolvedPlayerBottomFraction(0.38f, 0.385f), 0.001f)
        assertEquals(0.50f, resolvedPlayerBottomFraction(0.38f, 0.50f), 0.001f)
        assertEquals(0.38f, resolvedPlayerBottomFraction(0.38f, 0.95f), 0.001f)
    }

    @Test
    fun playerProbeCannotMutateYouTubeLayout() {
        listOf(
            "style.setProperty",
            "setAttribute(",
            "MutationObserver",
            "ResizeObserver",
            "requestAnimationFrame",
            "__dualSubReplayManager",
        ).forEach { forbidden ->
            assertFalse("Probe must not contain $forbidden", PLAYER_PROBE_SCRIPT.contains(forbidden))
        }
    }
}
