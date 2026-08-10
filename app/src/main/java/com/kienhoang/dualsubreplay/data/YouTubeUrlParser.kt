package com.kienhoang.dualsubreplay.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object YouTubeUrlParser {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val urlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

    fun extractVideoId(input: String): String? {
        val candidate = input.trim()
        if (videoIdPattern.matches(candidate)) return candidate

        val url = urlPattern.find(candidate)?.value
            ?.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
            ?: candidate

        val normalized = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val pathParts = uri.path.orEmpty().split('/').filter(String::isNotBlank)

        val id = when {
            host == "youtu.be" -> pathParts.firstOrNull()
            host == "youtube.com" || host == "m.youtube.com" || host.endsWith(".youtube.com") -> {
                when (pathParts.firstOrNull()) {
                    "watch" -> queryParameters(uri.rawQuery)["v"]
                    "shorts", "embed", "live" -> pathParts.getOrNull(1)
                    else -> queryParameters(uri.rawQuery)["v"]
                }
            }
            else -> null
        }
        return id?.takeIf(videoIdPattern::matches)
    }

    private fun queryParameters(query: String?): Map<String, String> = query.orEmpty()
        .split('&')
        .mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) null
            else decode(parts[0]) to decode(parts[1])
        }
        .toMap()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
