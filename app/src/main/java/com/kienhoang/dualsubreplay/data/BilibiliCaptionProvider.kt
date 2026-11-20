package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Most bilibili.com subtitles need the user's own sign-in (checked 2026-11-17: 16 of 20 popular videos). */
internal class BilibiliSignInNeededException :
    NoPublicCaptionsException("Bilibili gives this video's subtitles only to signed-in users")

/** [this] or one of its causes says Bilibili wants the viewer signed in for the subtitles. */
internal fun Throwable.meansBilibiliSignInNeeded(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is BilibiliSignInNeededException }

/** The Bilibili video has no subtitle track. */
internal class BilibiliNoSubtitlesException(
    message: String,
) : NoPublicCaptionsException(message)

private const val BILIBILI_TIMEOUT_MS = 15_000L
private const val WBI_KEYS_KEPT_MS = 60L * 60 * 1_000
private const val MAX_BILIBILI_RESPONSE_CHARS = 8 * 1024 * 1024
private const val BILIBILI_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

/** The order WBI mixes the two key halves in (Bilibili's web player, `getMixinKey`). */
private val WBI_MIXIN_ORDER =
    intArrayOf(
        46,
        47,
        18,
        2,
        53,
        8,
        23,
        32,
        15,
        50,
        10,
        31,
        58,
        3,
        45,
        35,
        27,
        43,
        5,
        49,
        33,
        9,
        42,
        19,
        29,
        28,
        14,
        39,
        12,
        38,
        41,
        13,
        37,
        48,
        7,
        16,
        24,
        55,
        40,
        61,
        26,
        17,
        0,
        1,
        60,
        51,
        30,
        4,
        22,
        25,
        54,
        21,
        56,
        59,
        6,
        63,
        57,
        62,
        11,
        36,
        20,
        34,
        44,
        52,
    )

/** A subtitle track a Bilibili video lists. */
internal data class BilibiliTrack(
    val code: String,
    val name: String,
    val url: String,
    val generated: Boolean,
) {
    val trackId: String get() = (if (generated) "a." else ".") + code
}

/**
 * Subtitles of Bilibili videos (spec docs/specs/2026-11-17-bilibili-beta.md), asked with the app's web
 * view cookies so a user signed in on Bilibili's own page gets the subtitles Bilibili gives them.
 * [get] returns a response body for a URL and the Referer to send; tests replace it.
 */
internal class BilibiliCaptionProvider(
    private val get: suspend (url: String, referer: String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) : CaptionProvider {
    @Volatile private var wbiKey: Pair<String, Long>? = null

    override suspend fun fetch(
        videoId: String,
        preferredLanguages: List<String>,
        learningLanguage: String?,
        preferredTrack: String?,
    ): CaptionTrackResult {
        val video = VideoKeys.bilibiliVideo(videoId) ?: throw CaptionUnavailableException("Not a Bilibili video: $videoId")
        val (tracks, referer) =
            when (video) {
                is BilibiliVideo.China -> chinaTracks(video) to "https://www.bilibili.com/"
                else -> tvTracks(video) to "https://www.bilibili.tv/"
            }
        val track =
            pickBilibiliTrack(tracks, preferredLanguages, learningLanguage, preferredTrack)
                ?: throw BilibiliNoSubtitlesException("This Bilibili video has no subtitles")
        val cues = parseBilibiliSubtitle(get(track.url, referer))
        if (cues.isEmpty()) throw BilibiliNoSubtitlesException("The Bilibili subtitle file was empty")
        return CaptionTrackResult(
            languageCode = track.code,
            isGenerated = track.generated,
            cues = cues,
            availableLanguages = tracks.map { CaptionLanguage(it.code, it.name, it.trackId, it.generated) },
            trackId = track.trackId,
        )
    }

    private suspend fun chinaTracks(video: BilibiliVideo.China): List<BilibiliTrack> {
        val referer = "https://www.bilibili.com/video/${video.bvid}"
        val view = data(get("https://api.bilibili.com/x/web-interface/view?bvid=${video.bvid}", referer))
        val aid = view.optLong("aid")
        val pages = view.optJSONArray("pages")
        val cid =
            pages?.optJSONObject(video.page - 1)?.optLong("cid")?.takeIf { it > 0 }
                ?: view.optLong("cid").takeIf { it > 0 }
                ?: throw CaptionUnavailableException("Bilibili named no part for ${video.key}")
        val query = signWbi(mapOf("aid" to aid.toString(), "cid" to cid.toString()), wbiMixinKey(referer), now() / 1_000)
        val player = data(get("https://api.bilibili.com/x/player/wbi/v2?$query", referer))
        val tracks = parseChinaTracks(player)
        if (tracks.isEmpty() && (player.optBoolean("need_login_subtitle") || player.optLong("login_mid") == 0L)) {
            throw BilibiliSignInNeededException()
        }
        return tracks
    }

    private suspend fun wbiMixinKey(referer: String): String {
        wbiKey?.takeIf { now() - it.second < WBI_KEYS_KEPT_MS }?.let { return it.first }
        // Signed out, nav answers -101 but still names the keys.
        val nav = JSONObject(get("https://api.bilibili.com/x/web-interface/nav", referer)).optJSONObject("data")
        val images = nav?.optJSONObject("wbi_img") ?: throw CaptionUnavailableException("Bilibili gave no signing keys")
        val key = wbiMixinKey(fileStem(images.optString("img_url")), fileStem(images.optString("sub_url")))
        wbiKey = key to now()
        return key
    }

    private suspend fun tvTracks(video: BilibiliVideo): List<BilibiliTrack> {
        val referer = "https://www.bilibili.tv/"
        val base = "https://api.bilibili.tv/intl/gateway/web"
        val target =
            when (video) {
                is BilibiliVideo.Episode -> "episode_id=${video.episodeId}"
                is BilibiliVideo.Upload -> "aid=${video.aid}"
                is BilibiliVideo.Season -> "episode_id=${firstEpisode(video.seasonId, referer)}"
                is BilibiliVideo.China -> error("not International")
            }
        return parseTvTracks(data(get("$base/v2/subtitle?s_locale=en_US&platform=web&$target", referer)))
    }

    private suspend fun firstEpisode(
        seasonId: Long,
        referer: String,
    ): Long {
        val sections =
            data(
                get(
                    "https://api.bilibili.tv/intl/gateway/web/v2/ogv/play/episodes?season_id=$seasonId&platform=web&s_locale=en_US",
                    referer,
                ),
            ).optJSONArray("sections")
        val episode = sections?.optJSONObject(0)?.optJSONArray("episodes")?.optJSONObject(0)
        return episode?.optString("episode_id")?.toLongOrNull() ?: throw BilibiliNoSubtitlesException("The season lists no episode")
    }

    companion object {
        /** The provider used by the app: OkHttp with the web view's Bilibili cookies. */
        fun withCookies(cookies: (String) -> String?): BilibiliCaptionProvider {
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(BILIBILI_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(BILIBILI_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
            return BilibiliCaptionProvider({ url, referer ->
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("User-Agent", BILIBILI_USER_AGENT)
                            .header("Referer", referer)
                            .apply { cookies(url)?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Bilibili answered HTTP ${response.code}")
                        val body = response.body.string()
                        if (body.length > MAX_BILIBILI_RESPONSE_CHARS) throw IOException("Bilibili's answer was too large")
                        body
                    }
                }
            })
        }
    }
}

/** The `data` of a Bilibili API answer, or an error naming its code. */
private fun data(body: String): JSONObject {
    val root = JSONObject(body)
    val code = root.optInt("code", -1)
    if (code != 0) throw CaptionUnavailableException("Bilibili answered code $code: ${root.optString("message")}")
    return root.optJSONObject("data") ?: JSONObject()
}

private fun fileStem(url: String): String = url.substringAfterLast('/').substringBefore('.')

/** WBI's mixin key: [WBI_MIXIN_ORDER] applied to both halves, first 32 characters. */
internal fun wbiMixinKey(
    imgKey: String,
    subKey: String,
): String {
    val raw = imgKey + subKey
    return WBI_MIXIN_ORDER
        .filter { it < raw.length }
        .map { raw[it] }
        .joinToString("")
        .take(32)
}

/** [params] with `wts` added, sorted, and `w_rid` = MD5 of the query and [mixinKey]. */
internal fun signWbi(
    params: Map<String, String>,
    mixinKey: String,
    epochSeconds: Long,
): String {
    val query =
        (params + ("wts" to epochSeconds.toString()))
            .toSortedMap()
            .entries
            .joinToString("&") { (key, value) -> "${wbiEncode(key)}=${wbiEncode(value.filterNot { it in "!'()*" })}" }
    val digest = MessageDigest.getInstance("MD5").digest((query + mixinKey).toByteArray(Charsets.UTF_8))
    return "$query&w_rid=" + digest.joinToString("") { "%02x".format(it) }
}

private fun wbiEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** bilibili.com's `subtitle.subtitles` list: `lan` such as `zh-CN` or `ai-zh`, `subtitle_url` without a scheme. */
internal fun parseChinaTracks(player: JSONObject): List<BilibiliTrack> {
    val list = player.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: return emptyList()
    return list.objects().mapNotNull { item ->
        val lan = item.optString("lan")
        val url = absoluteUrl(item.optString("subtitle_url")) ?: return@mapNotNull null
        val generated = lan.startsWith("ai-") || item.optInt("ai_type", 0) > 0 || item.optInt("type", 0) == 1
        BilibiliTrack(bilibiliLanguageCode(lan), item.optString("lan_doc").ifBlank { lan }, url, generated)
    }
}

/** bilibili.tv's `subtitles` (anime) and `video_subtitle` (user videos) lists. */
internal fun parseTvTracks(data: JSONObject): List<BilibiliTrack> {
    val shows =
        data.optJSONArray("subtitles")?.objects().orEmpty().mapNotNull { item ->
            val url = absoluteUrl(item.optString("url")) ?: return@mapNotNull null
            val code = item.optString("lang_key").ifBlank { item.optString("key") }
            BilibiliTrack(bilibiliLanguageCode(code), item.optString("lang").ifBlank { code }, url, generated = false)
        }
    val uploads =
        data.optJSONArray("video_subtitle")?.objects().orEmpty().mapNotNull { item ->
            val file = item.optJSONObject("text_subtitle") ?: item.optJSONObject("ass_subtitle")
            val url = absoluteUrl(file?.optString("url").orEmpty()) ?: return@mapNotNull null
            val code = item.optString("lang_key")
            BilibiliTrack(bilibiliLanguageCode(code), item.optString("lang").ifBlank { code }, url, generated = false)
        }
    return (shows + uploads).distinctBy { it.url }
}

/** Bilibili's language names as the app's codes: `ai-zh`, `zh-CN`, `zh-Hans` → `zh`; `zh-Hant`, `zh-HK` → `zh-TW`. */
internal fun bilibiliLanguageCode(raw: String): String {
    val code = raw.removePrefix("ai-").trim()
    val lower = code.lowercase(Locale.ROOT)
    return when {
        lower.isEmpty() -> "und"
        lower == "zh-hant" || lower == "zh-tw" || lower == "zh-hk" -> "zh-TW"
        lower.startsWith("zh") -> "zh"
        else -> lower.substringBefore('-')
    }
}

/** The picked track, else the first in the user's original language, the learning language, then the first listed. */
internal fun pickBilibiliTrack(
    tracks: List<BilibiliTrack>,
    preferredLanguages: List<String>,
    learningLanguage: String?,
    preferredTrack: String?,
): BilibiliTrack? {
    fun base(code: String) = code.substringBefore('-').lowercase(Locale.ROOT)
    preferredTrack?.let { picked -> tracks.firstOrNull { it.trackId == picked }?.let { return it } }
    preferredLanguages.forEach { wanted ->
        tracks.filter { base(it.code) == base(wanted) }.minByOrNull { it.generated }?.let { return it }
    }
    learningLanguage?.let { wanted -> tracks.filter { base(it.code) == base(wanted) }.minByOrNull { it.generated }?.let { return it } }
    return tracks.minByOrNull { it.generated }
}

/** A Bilibili subtitle file: JSON (`body: [{from, to, content}]`) or ASS. */
internal fun parseBilibiliSubtitle(text: String): List<RawCaptionCue> {
    val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    return if (trimmed.startsWith("{")) parseBilibiliJson(trimmed) else parseAss(trimmed)
}

private fun parseBilibiliJson(text: String): List<RawCaptionCue> =
    JSONObject(text)
        .optJSONArray("body")
        ?.objects()
        .orEmpty()
        .mapNotNull { item ->
            val content = item.optString("content").replace("\n", " ").trim()
            val start = (item.optDouble("from", Double.NaN) * 1_000).toLong()
            val end = (item.optDouble("to", Double.NaN) * 1_000).toLong()
            RawCaptionCue(start, maxOf(end, start), content).takeIf { content.isNotEmpty() && start >= 0 }
        }.sortedBy { it.startMs }

private val ASS_TIME = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})[.](\\d{1,3})$")
private val ASS_CODES = Regex("\\{[^}]*\\}")

/** ASS `Dialogue:` lines, in the order the `[Events]` `Format:` line names the fields. */
internal fun parseAss(text: String): List<RawCaptionCue> {
    var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    val cues = mutableListOf<RawCaptionCue>()
    var inEvents = false
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("[") -> inEvents = line.equals("[Events]", ignoreCase = true)
            !inEvents -> Unit
            line.startsWith("Format:", ignoreCase = true) ->
                fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
            line.startsWith("Dialogue:", ignoreCase = true) -> {
                val values = line.substringAfter(':').split(',', limit = fields.size)
                val get = { name: String -> values.getOrNull(fields.indexOf(name))?.trim() }
                val start = get("start")?.let(::assTimeMs) ?: return@forEach
                val end = get("end")?.let(::assTimeMs) ?: return@forEach
                val content =
                    get("text")
                        .orEmpty()
                        .replace(ASS_CODES, "")
                        .replace("\\N", " ")
                        .replace("\\n", " ")
                        .replace("\\h", " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                if (content.isNotEmpty()) cues += RawCaptionCue(start, maxOf(end, start), content)
            }
        }
    }
    return cues.sortedBy { it.startMs }
}

private fun assTimeMs(value: String): Long? {
    val match = ASS_TIME.find(value) ?: return null
    val (hours, minutes, seconds, fraction) = match.destructured
    val millis = fraction.padEnd(3, '0').take(3).toLong()
    return ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1_000 + millis
}

private fun absoluteUrl(url: String): String? =
    when {
        url.isBlank() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("https://") -> url
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> null
    }

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
