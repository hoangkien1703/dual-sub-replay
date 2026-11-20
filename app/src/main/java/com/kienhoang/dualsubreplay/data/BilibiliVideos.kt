package com.kienhoang.dualsubreplay.data

import java.net.URI

/**
 * Where a video is watched (Bilibili beta, owner pick 2026-11-17; spec
 * docs/specs/2026-11-17-bilibili-beta.md). YouTube videos keep their 11-character ids as keys; a
 * Bilibili video's key is file-name safe the same way, so every store that keys on a video id
 * (recent tracks, saved words, the Whole transcript) works unchanged.
 */
internal enum class VideoSite {
    YOUTUBE,

    /** bilibili.com (China, 哔哩哔哩). */
    BILIBILI,

    /** bilibili.tv (Bilibili International). */
    BILIBILI_TV,
}

/** A Bilibili video: China's `BV` id and page, or International's episode or user video. */
internal sealed interface BilibiliVideo {
    val key: String

    /** bilibili.com's [bvid] (`BV1…`), page [page] from 1. */
    data class China(
        val bvid: String,
        val page: Int = 1,
    ) : BilibiliVideo {
        override val key: String get() = BILIBILI_KEY_PREFIX + bvid + if (page > 1) "-p$page" else ""
    }

    /** A bilibili.tv anime or show episode; its page needs the [seasonId] too. */
    data class Episode(
        val seasonId: Long,
        val episodeId: Long,
    ) : BilibiliVideo {
        override val key: String get() = "${BILIBILI_TV_KEY_PREFIX}ss$seasonId-ep$episodeId"
    }

    /** A bilibili.tv season page without an episode in its address: it plays the season's first episode. */
    data class Season(
        val seasonId: Long,
    ) : BilibiliVideo {
        override val key: String get() = "${BILIBILI_TV_KEY_PREFIX}ss$seasonId"
    }

    /** A bilibili.tv user video (`/video/<aid>`). */
    data class Upload(
        val aid: Long,
    ) : BilibiliVideo {
        override val key: String get() = "${BILIBILI_TV_KEY_PREFIX}av$aid"
    }
}

internal const val BILIBILI_KEY_PREFIX = "bili-"
internal const val BILIBILI_TV_KEY_PREFIX = "bilitv-"

internal const val BILIBILI_HOME_URL = "https://m.bilibili.com/"
internal const val BILIBILI_TV_HOME_URL = "https://www.bilibili.tv/"

private val BVID_PATTERN = Regex("^BV[0-9A-Za-z]{10}$")
private val CHINA_KEY_PATTERN = Regex("^bili-(BV[0-9A-Za-z]{10})(?:-p(\\d{1,4}))?$")
private val TV_KEY_PATTERN = Regex("^bilitv-(?:ss(\\d{1,20})(?:-ep(\\d{1,20}))?|av(\\d{1,20}))$")
private val DIGITS = Regex("^\\d{1,20}$")
private val YOUTUBE_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

private val LOCALE_PART = Regex("^[a-z]{2}(?:-[A-Za-z]{2,4})?$")
private val AV_PATTERN = Regex("^av(\\d{1,16})$", RegexOption.IGNORE_CASE)

// Bilibili's own av to BV mapping (since 2024): one video, two names. The key keeps the BV name.
private const val BV_XOR = 23_442_827_791_579L
private const val BV_MAX_AID = 1L shl 51
private const val BV_BASE = 58
private const val BV_ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

/** The BV id of video [aid], or null outside the ids Bilibili gives. */
internal fun bvidOfAid(aid: Long): String? {
    if (aid <= 0 || aid >= BV_MAX_AID) return null
    val chars = "BV1000000000".toCharArray()
    var rest = (BV_MAX_AID or aid) xor BV_XOR
    var index = chars.size - 1
    while (rest > 0 && index >= 3) {
        chars[index--] = BV_ALPHABET[(rest % BV_BASE).toInt()]
        rest /= BV_BASE
    }
    chars[3] = chars[9].also { chars[9] = chars[3] }
    chars[4] = chars[7].also { chars[7] = chars[4] }
    return String(chars)
}

internal object VideoKeys {
    /** The site a [key] belongs to. */
    fun site(key: String): VideoSite =
        when {
            key.startsWith(BILIBILI_TV_KEY_PREFIX) -> VideoSite.BILIBILI_TV
            key.startsWith(BILIBILI_KEY_PREFIX) -> VideoSite.BILIBILI
            else -> VideoSite.YOUTUBE
        }

    fun isBilibili(key: String?): Boolean = key != null && site(key) != VideoSite.YOUTUBE

    /** True for a YouTube id or a Bilibili key, the only video ids saved words and backups keep. */
    fun isValid(key: String?): Boolean = key != null && (key.matches(YOUTUBE_ID_PATTERN) || bilibiliVideo(key) != null)

    /** The Bilibili site a page [host] belongs to (b23.tv is bilibili.com's short link), or null. */
    fun bilibiliSiteOfHost(host: String): VideoSite? =
        when {
            host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" -> VideoSite.BILIBILI
            host == "bilibili.tv" || host.endsWith(".bilibili.tv") -> VideoSite.BILIBILI_TV
            else -> null
        }

    /** The video [url] shows: a YouTube id, a Bilibili key, or null for any other page. */
    fun fromUrl(url: String): String? = YouTubeUrlParser.extractVideoId(url) ?: bilibiliVideoAt(url)?.key

    /** The Bilibili video a page [url] shows, or null. */
    fun bilibiliVideoAt(url: String): BilibiliVideo? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        val parts =
            uri.path
                .orEmpty()
                .split('/')
                .filter(String::isNotBlank)
        return when {
            host == "bilibili.com" || host.endsWith(".bilibili.com") -> chinaVideo(parts, uri.rawQuery)
            host == "bilibili.tv" || host.endsWith(".bilibili.tv") -> tvVideo(parts)
            else -> null
        }
    }

    /** The Bilibili video a [key] names, or null for a YouTube id. */
    fun bilibiliVideo(key: String): BilibiliVideo? =
        CHINA_KEY_PATTERN.find(key)?.let { match ->
            BilibiliVideo.China(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: 1)
        } ?: TV_KEY_PATTERN.find(key)?.let { match ->
            val (season, episode, aid) = match.destructured
            when {
                aid.isNotEmpty() -> aid.toLongOrNull()?.let(BilibiliVideo::Upload)
                episode.isNotEmpty() -> BilibiliVideo.Episode(season.toLongOrNull() ?: return null, episode.toLongOrNull() ?: return null)
                else -> season.toLongOrNull()?.let(BilibiliVideo::Season)
            }
        }

    /** The page that plays [key], at [seconds] when given, for saved words, cards and tabs. */
    fun watchUrl(
        key: String,
        seconds: Int? = null,
    ): String {
        val at = seconds?.takeIf { it > 0 }
        return when (val video = bilibiliVideo(key)) {
            is BilibiliVideo.China -> {
                val query = listOfNotNull(("p=${video.page}").takeIf { video.page > 1 }, at?.let { "t=$it" })
                "https://m.bilibili.com/video/${video.bvid}" + query.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
            }
            is BilibiliVideo.Episode ->
                "https://www.bilibili.tv/play/${video.seasonId}/${video.episodeId}" + (at?.let { "?start_progress=${it * 1_000}" } ?: "")
            is BilibiliVideo.Season -> "https://www.bilibili.tv/play/${video.seasonId}"
            is BilibiliVideo.Upload -> "https://www.bilibili.tv/video/${video.aid}" + (at?.let { "?start_progress=${it * 1_000}" } ?: "")
            null -> "https://www.youtube.com/watch?v=$key" + (at?.let { "&t=${it}s" } ?: "")
        }
    }

    /** `/video/BV…`, `/video/av<aid>` (cards' and old share links) and `/en/video/BV…` (the overseas desktop page). */
    private fun chinaVideo(
        parts: List<String>,
        rawQuery: String?,
    ): BilibiliVideo.China? {
        // Only a language may come first (`/en/video/…`), not a section such as `/space/…`.
        val start = if (parts.getOrNull(0)?.matches(LOCALE_PART) == true) 1 else 0
        if (parts.getOrNull(start) != "video") return null
        val id = parts.getOrNull(start + 1) ?: return null
        val bvid =
            id.takeIf(BVID_PATTERN::matches)
                ?: AV_PATTERN
                    .find(id)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
                    ?.let(::bvidOfAid)
                ?: return null
        val page =
            rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
                pair.takeIf { it.startsWith("p=") }?.removePrefix("p=")?.toIntOrNull()
            }
        return BilibiliVideo.China(bvid, page?.coerceIn(1, 9_999) ?: 1)
    }

    /** `/en/video/<aid>`, `/en/play/<season>/<episode>`, or `/en/play/<season>` (its first episode). */
    private fun tvVideo(parts: List<String>): BilibiliVideo? {
        val start = parts.indexOfFirst { it == "video" || it == "play" }
        if (start < 0) return null
        return when (parts[start]) {
            "video" ->
                parts
                    .getOrNull(start + 1)
                    ?.takeIf(DIGITS::matches)
                    ?.toLongOrNull()
                    ?.let(BilibiliVideo::Upload)
            else -> {
                val season = parts.getOrNull(start + 1)?.takeIf(DIGITS::matches)?.toLongOrNull() ?: return null
                val episode = parts.getOrNull(start + 2)?.takeIf(DIGITS::matches)?.toLongOrNull()
                if (episode != null) BilibiliVideo.Episode(season, episode) else BilibiliVideo.Season(season)
            }
        }
    }
}
