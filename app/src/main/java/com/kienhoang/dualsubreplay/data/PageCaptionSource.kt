package com.kienhoang.dualsubreplay.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Caption details the YouTube page in the app already received. YouTube sometimes refuses the
 * app's own player requests with a bot check ("Sign in to confirm you're not a bot") while the
 * page, with its cookies and browser checks, plays the same video. The page hands over its
 * caption track list, and the parameters its own caption requests carried (`pot` and the
 * client fields), so the provider can download the full transcript without asking YouTube's
 * player again. Nothing here starts a request or changes the page.
 */
class PageCaptionSource {
    private data class PageTracks(
        val videoId: String,
        val playerResponse: String,
    )

    @Volatile private var tracks: PageTracks? = null

    @Volatile private var timedTextParameters: Pair<String, Map<String, String>>? = null

    private val transcripts = ArrayDeque<PageTranscript>()

    /** Called on the main thread when the page hands over a caption document for a video. */
    @Volatile var onTranscript: (videoId: String) -> Unit = {}

    /** The page's user agent, so downloads look like the page that is playing the video. */
    @Volatile var userAgent: String? = null

    /** Cookies the page holds for a YouTube address; only ever sent back to YouTube. */
    @Volatile var cookies: (HttpUrl) -> String? = { null }

    /** Records the page's trimmed player response (see `WEB_PAGE_CAPTION_TRACKS_SCRIPT`). */
    fun recordPlayerResponse(json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val videoId =
            root
                .optJSONObject("videoDetails")
                ?.optString("videoId")
                ?.takeIf(PAGE_VIDEO_ID::matches)
                ?: return
        tracks = PageTracks(videoId, json)
    }

    /** Records the parameters of a caption request the page's own player made. */
    fun recordTimedTextRequest(url: String) {
        val parameters = pageTimedTextParameters(url) ?: return
        timedTextParameters = parameters
    }

    /** Records a caption document the page's own player downloaded; the newest 4 are kept. */
    fun recordTranscript(transcript: PageTranscript) {
        if (!PAGE_VIDEO_ID.matches(transcript.videoId) || transcript.text.length > MAX_PAGE_TRANSCRIPT_LENGTH) return
        synchronized(transcripts) {
            transcripts.removeAll { it.id == transcript.id }
            transcripts.addLast(transcript)
            while (transcripts.size > MAX_PAGE_TRANSCRIPTS) transcripts.removeFirst()
        }
        onTranscript(transcript.videoId)
    }

    fun transcripts(videoId: String): List<PageTranscript> = synchronized(transcripts) { transcripts.filter { it.videoId == videoId } }

    fun hasTracks(videoId: String): Boolean = tracks?.videoId == videoId

    fun playerResponse(videoId: String): JSONObject? =
        tracks
            ?.takeIf { it.videoId == videoId }
            ?.let { runCatching { JSONObject(it.playerResponse) }.getOrNull() }

    fun timedTextParameters(videoId: String): Map<String, String> = timedTextParameters?.takeIf { it.first == videoId }?.second.orEmpty()

    companion object {
        /** The one app-wide page: there is exactly one YouTube WebView. */
        val Shared = PageCaptionSource()
    }
}

private val PAGE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private const val MAX_PAGE_TRANSCRIPTS = 4

/** The same 8 MiB limit as the provider's own downloads, counted in characters. */
internal const val MAX_PAGE_TRANSCRIPT_LENGTH = 8 * 1024 * 1024

/** A caption document (`json3`, `srv3` or XML) the page's player downloaded for one track. */
data class PageTranscript(
    val videoId: String,
    val languageCode: String,
    val kind: String,
    val text: String,
) {
    val id: String get() = "$videoId|$languageCode|$kind"
    val generated: Boolean get() = kind == "asr"
}

/**
 * The page document for [track] (same language and same generated or creator kind), or, with no
 * [track], the newest document. Null when the page has none that fits.
 */
internal fun pageTranscriptFor(
    transcripts: List<PageTranscript>,
    track: JSONObject?,
): PageTranscript? {
    if (track == null) return transcripts.lastOrNull()
    val language = track.optString("languageCode")
    val generated = isGeneratedTrack(track)
    return transcripts.lastOrNull { it.languageCode.equals(language, ignoreCase = true) && it.generated == generated }
}

/** Fields the page's player adds to its caption requests and YouTube may check. */
internal val PAGE_TIMED_TEXT_PARAMETERS =
    listOf("pot", "potc", "c", "cver", "cplayer", "cbr", "cbrver", "cos", "cosver", "cplatform", "xorb", "xobt", "xovt")

/** The video id and [PAGE_TIMED_TEXT_PARAMETERS] of a trusted `/api/timedtext` address, or null. */
internal fun pageTimedTextParameters(url: String): Pair<String, Map<String, String>>? {
    val parsed = trustedYouTubeCaptionUrl(url)?.takeIf { it.encodedPath == "/api/timedtext" } ?: return null
    val videoId = parsed.queryParameter("v")?.takeIf(PAGE_VIDEO_ID::matches) ?: return null
    val parameters =
        PAGE_TIMED_TEXT_PARAMETERS
            .mapNotNull { name -> parsed.queryParameter(name)?.let { name to it } }
            .toMap()
    return (videoId to parameters).takeIf { "pot" in parameters }
}

/** [baseUrl] with the page's own caption parameters added where it lacks them. */
internal fun withPageTimedTextParameters(
    baseUrl: String,
    parameters: Map<String, String>,
): String {
    val parsed = baseUrl.toHttpUrlOrNull() ?: return baseUrl
    if (parameters.isEmpty()) return baseUrl
    val builder = parsed.newBuilder()
    parameters.forEach { (name, value) -> if (parsed.queryParameter(name) == null) builder.addQueryParameter(name, value) }
    return builder.build().toString()
}

/**
 * A result from the page's caption documents alone (no track list): the user's chosen language
 * when the page has it, otherwise the newest document. Null when none parses.
 */
internal fun pageTranscriptResult(
    transcripts: List<PageTranscript>,
    languages: CaptionLanguageChoice,
): CaptionTrackResult? {
    val preferred = languages.preferred.map { it.substringBefore('-').lowercase() }
    val ordered =
        transcripts.sortedByDescending { transcript ->
            val index = preferred.indexOf(transcript.languageCode.substringBefore('-').lowercase())
            if (index >= 0) preferred.size - index else 0
        }
    for (transcript in ordered) {
        val cues = CaptionDocumentParser.parse(transcript.text)
        if (cues.isEmpty()) continue
        return CaptionTrackResult(
            languageCode = transcript.languageCode.ifBlank { "en" },
            isGenerated = transcript.generated,
            cues = cues,
            availableLanguages =
                transcripts
                    .map { it.languageCode }
                    .filter(String::isNotBlank)
                    .distinctBy { it.substringBefore('-').lowercase() }
                    .map { CaptionLanguage(it, it.uppercase()) },
        )
    }
    return null
}
