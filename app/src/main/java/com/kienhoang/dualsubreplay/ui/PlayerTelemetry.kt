package com.kienhoang.dualsubreplay.ui

import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

data class PlayerTelemetry(
    val playbackSecond: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val playerLeft: Float,
    val playerTop: Float,
    val playerRight: Float,
    val playerBottom: Float,
    val videoWidth: Int,
    val videoHeight: Int,
    val readyState: Int,
    val networkState: Int,
    val decodedFrameCount: Long,
) {
    val playerBottomFraction: Float
        get() = (playerBottom / viewportHeight).coerceIn(0f, 1f)
}

internal object PlayerTelemetryParser {
    fun parse(rawValue: String): PlayerTelemetry? = runCatching {
        val decoded = JSONTokener(rawValue).nextValue()
        val json = when (decoded) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            else -> return null
        }

        PlayerTelemetry(
            playbackSecond = json.finiteFloat("playbackSecond"),
            viewportWidth = json.positiveFloat("viewportWidth"),
            viewportHeight = json.positiveFloat("viewportHeight"),
            playerLeft = json.finiteFloat("playerLeft"),
            playerTop = json.finiteFloat("playerTop"),
            playerRight = json.finiteFloat("playerRight"),
            playerBottom = json.finiteFloat("playerBottom"),
            videoWidth = json.optInt("videoWidth").coerceAtLeast(0),
            videoHeight = json.optInt("videoHeight").coerceAtLeast(0),
            readyState = json.getInt("readyState"),
            networkState = json.getInt("networkState"),
            decodedFrameCount = json.optLong("decodedFrameCount").coerceAtLeast(0L),
        ).takeIf {
            it.playbackSecond >= 0f &&
                it.playerRight > it.playerLeft &&
                it.playerBottom > it.playerTop &&
                it.readyState in 0..4 &&
                it.networkState in 0..3
        }
    }.getOrNull()

    private fun JSONObject.finiteFloat(name: String): Float =
        getDouble(name).toFloat().takeIf(Float::isFinite)
            ?: throw IllegalArgumentException("$name must be finite")

    private fun JSONObject.positiveFloat(name: String): Float =
        finiteFloat(name).takeIf { it > 0f }
            ?: throw IllegalArgumentException("$name must be positive")
}

internal fun resolvedPlayerBottomFraction(
    current: Float,
    measured: Float?,
    minimumChange: Float = 0.01f,
): Float {
    val validMeasurement = measured?.takeIf { it.isFinite() && it in 0.12f..0.80f } ?: return current
    return if (abs(validMeasurement - current) >= minimumChange) validMeasurement else current
}
