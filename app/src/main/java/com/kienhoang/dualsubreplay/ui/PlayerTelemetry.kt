package com.kienhoang.dualsubreplay.ui

import org.json.JSONObject
import org.json.JSONTokener

enum class VideoDisplayMode { LEARNING, FOCUS }

enum class VideoOrientation {
    LANDSCAPE,
    PORTRAIT;

    companion object {
        fun fromDimensions(width: Int, height: Int): VideoOrientation =
            if (width > 0 && height > width) PORTRAIT else LANDSCAPE
    }
}

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
) {
    val playerBottomFraction: Float
        get() = (playerBottom / viewportHeight).coerceIn(0f, 1f)

    val orientation: VideoOrientation
        get() = VideoOrientation.fromDimensions(videoWidth, videoHeight)
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
        ).takeIf {
            it.playbackSecond >= 0f &&
                it.playerRight > it.playerLeft &&
                it.playerBottom > it.playerTop
        }
    }.getOrNull()

    private fun JSONObject.finiteFloat(name: String): Float =
        getDouble(name).toFloat().takeIf(Float::isFinite)
            ?: throw IllegalArgumentException("$name must be finite")

    private fun JSONObject.positiveFloat(name: String): Float =
        finiteFloat(name).takeIf { it > 0f }
            ?: throw IllegalArgumentException("$name must be positive")
}

internal fun VideoDisplayMode.forPage(hasVideo: Boolean): VideoDisplayMode =
    if (hasVideo) this else VideoDisplayMode.LEARNING
