package com.kienhoang.dualsubreplay.data

import java.net.URI

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

internal fun trustedYouTubeAudioStreamUrl(rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return false
    val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
    if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return false
    return uri.path?.endsWith("/videoplayback") == true
}
