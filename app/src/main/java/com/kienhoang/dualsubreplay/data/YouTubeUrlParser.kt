package com.kienhoang.dualsubreplay.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object YouTubeUrlParser {
    internal const val MAX_SHARED_TEXT_LENGTH = 8_192
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val urlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

    fun extractVideoId(input: String): String? {
        if (input.length > MAX_SHARED_TEXT_LENGTH) return null
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
                val parameters = queryParameters(uri.rawQuery) ?: return null
                when (pathParts.firstOrNull()) {
                    "watch" -> parameters["v"]
                    "shorts", "embed", "live" -> pathParts.getOrNull(1)
                    else -> parameters["v"]
                }
            }
            else -> null
        }
        return id?.takeIf(videoIdPattern::matches)
    }

    /** The start time a watch link asks for ("t=83", "t=83s", "t=1m23s"), or null when it has none. */
    fun extractStartSeconds(input: String): Int? {
        if (input.length > MAX_SHARED_TEXT_LENGTH || extractVideoId(input) == null) return null
        val url = urlPattern.find(input.trim())?.value ?: input.trim()
        val raw = Regex("[?&#]t=([0-9hms]{1,12})(?:&|$)").find(url)?.groupValues?.get(1) ?: return null
        val parts = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$").find(raw) ?: return null
        val (hours, minutes, seconds) = parts.destructured
        val total = (hours.toIntOrNull() ?: 0) * 3_600 + (minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    private fun queryParameters(query: String?): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        query.orEmpty().split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val key = decode(parts[0]) ?: return null
            val value = decode(parts[1]) ?: return null
            result[key] = value
        }
        return result
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
