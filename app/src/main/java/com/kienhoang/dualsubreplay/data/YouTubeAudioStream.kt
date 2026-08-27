package com.kienhoang.dualsubreplay.data

/**
 * A short-lived, signed YouTube adaptive-audio URL observed from the already
 * loaded player page. The URL is validated natively before it is accepted.
 */
data class YouTubeAudioStream(
    val videoId: String,
    val url: String,
    val mimeType: String?,
    val userAgent: String?,
)
