package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/*
 * Free synced lyrics for songs (owner pick 2026-11-17): before Gemini writes a song's transcript,
 * LRCLIB (lrclib.net, a free, open lyrics database with timed lines and no key) is asked for it.
 * Only the cleaned video title (and the artist of a "- Topic" or VEVO channel) is sent, and only
 * after the person taps. A song is taken only when its length matches the video's.
 */

internal const val LRCLIB_HOST = "lrclib.net"

/** A search answers up to 20 songs, each with its lyrics; usually well under 200 KiB. */
private const val MAX_LYRICS_REPLY_BYTES = 2 * 1024 * 1024

private const val LYRICS_TIMEOUT_MS = 10_000L

/** A song may be this much longer or shorter than the video (lyric videos add a second or two). */
internal const val LYRICS_LENGTH_SLACK_MS = 3_000L

/** A compilation's song may run a little longer or shorter than the song, where songs fade into each other. */
internal const val CHAPTER_LENGTH_SLACK_MS = 6_000L

/** A line with no next line, or before a long instrumental part, lasts at most this long. */
private const val LONGEST_LYRIC_LINE_MS = 8_000L

/** Words in a video title that are not part of the song's name. */
private val TITLE_NOISE =
    Regex(
        "\\b(official|music|video|lyrics?|audio|mv|m/v|hd|4k|karaoke|visuali[sz]er|vietsub|engsub|letra|paroles|testo)\\b|" +
            "歌詞|歌词|가사|lời bài hát",
        RegexOption.IGNORE_CASE,
    )

/** Bracketed parts of a title: (Lyrics), [Official Video], 【MV】 and the like. Japanese 「」 hold the song's name, so they stay. */
private val TITLE_BRACKETS = Regex("[(\\[【（][^)\\]】）]*[)\\]】）]")

private val FEATURING = Regex("\\s(ft\\.?|feat\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)

/**
 * What to search LRCLIB for: the video title without brackets, "lyrics" and similar words, and the
 * channel's artist when the title does not name one ("Artist - Topic" and VEVO channels). Null
 * when nothing is left.
 */
internal fun lyricsSearchQuery(
    title: String,
    channel: String,
): String? {
    val song =
        title
            .replace(TITLE_BRACKETS, " ")
            .replace('「', ' ')
            .replace('」', ' ')
            .substringBefore(" | ")
            .substringBefore(" // ")
            .replace(TITLE_NOISE, " ")
            .replace(FEATURING, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '–', '|', ':')
    if (song.isBlank()) return null
    val artist =
        when {
            channel.endsWith(" - Topic") -> channel.removeSuffix(" - Topic")
            channel.endsWith("VEVO", ignoreCase = true) -> channel.dropLast(4)
            else -> ""
        }.trim()
    val namesArtist = song.contains(" - ") || artist.isBlank() || song.contains(artist, ignoreCase = true)
    return if (namesArtist) song else "$artist $song"
}

/** The LRCLIB search address for [query]. */
internal fun lyricsSearchUrl(query: String): HttpUrl =
    HttpUrl
        .Builder()
        .scheme("https")
        .host(LRCLIB_HOST)
        .addPathSegments("api/search")
        .addQueryParameter("q", query)
        .build()

/** A song LRCLIB knows, with its timed lyrics. */
internal data class SyncedLyrics(
    val track: String,
    val artist: String,
    val durationMs: Long,
    val cues: List<RawCaptionCue>,
)

/**
 * The song in LRCLIB's search reply whose length is closest to the video's [videoDurationMs]
 * (within [LYRICS_LENGTH_SLACK_MS]), that has timed lyrics and whose name appears in [title].
 * Null when none fits: the wrong song's lyrics would be worse than none. With no length (a song
 * of a playlist, placed later by [alignLyrics]), the song that best names [title] wins.
 */
internal fun pickSyncedLyrics(
    reply: String,
    title: String,
    videoDurationMs: Long?,
    slackMs: Long = LYRICS_LENGTH_SLACK_MS,
): SyncedLyrics? {
    val songs =
        try {
            JSONArray(reply)
        } catch (_: JSONException) {
            return null
        }
    val lowerTitle = title.lowercase()
    return (0 until songs.length())
        .mapNotNull(songs::optJSONObject)
        .mapNotNull { song ->
            val synced = song.optString("syncedLyrics").takeIf { !song.isNull("syncedLyrics") && it.isNotBlank() } ?: return@mapNotNull null
            val seconds = song.optDouble("duration", Double.NaN).takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
            val durationMs = (seconds * 1000).toLong()
            val track = song.optString("trackName")
            if (song.optBoolean("instrumental")) return@mapNotNull null
            if (videoDurationMs != null && abs(durationMs - videoDurationMs) > slackMs) return@mapNotNull null
            if (!namesTheSong(lowerTitle, track)) return@mapNotNull null
            val cues = parseLrc(synced)
            if (cues.isEmpty()) null else SyncedLyrics(track, song.optString("artistName"), durationMs, cues)
        }.let { songs ->
            if (videoDurationMs != null) {
                songs.minByOrNull { abs(it.durationMs - videoDurationMs) }
            } else {
                // LRCLIB lists the best matches first; a song whose whole name and artist are in the title goes ahead.
                songs.maxByOrNull { titleScore(it, lowerTitle) }
            }
        }
}

/** 2 when the song's name (without an "Artist - " in front) is in the title, plus 1 when its artist is. */
private fun titleScore(
    song: SyncedLyrics,
    lowerTitle: String,
): Int {
    val artist = song.artist.lowercase()
    val name =
        song.track
            .lowercase()
            .removePrefix("$artist - ")
            .trim()
    return (if (name.isNotEmpty() && name in lowerTitle) 2 else 0) + (if (artist.isNotEmpty() && artist in lowerTitle) 1 else 0)
}

/** Words in brackets after a song's name that are not its artist: "(Remix)", "(Live)", "(feat. X)". */
private val NOT_AN_ARTIST =
    Regex(
        "\\b(remix|live|acoustic|version|edit|cover|sped|slowed|reverb|feat|ft|mix|instrumental|remaster(ed)?)\\b",
        RegexOption.IGNORE_CASE,
    )

private val SONG_THEN_BRACKETS = Regex("""^(.+?)\s*[(\[]([^)\]]{2,60})[)\]]\s*$""")

/**
 * A playlist chapter's name as LRCLIB finds it: "2002 (Anne-Marie)" names its artist in brackets,
 * which [lyricsSearchQuery] would drop, so it becomes "2002 - Anne-Marie". Other names stay as they are.
 */
internal fun chapterSongName(title: String): String {
    if (" - " in title) return title
    val match = SONG_THEN_BRACKETS.matchEntire(title.trim()) ?: return title
    val (song, inBrackets) = match.destructured
    if (NOT_AN_ARTIST.containsMatchIn(inBrackets) || TITLE_NOISE.containsMatchIn(inBrackets)) return title
    return "$song - $inBrackets"
}

/** True when a word of the song's name (or the whole name, for languages without spaces) is in the title. */
private fun namesTheSong(
    lowerTitle: String,
    track: String,
): Boolean {
    val words = track.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
    return words.any { it in lowerTitle }
}

private val LRC_TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

/**
 * Reads LRC lyrics ("[01:02.50] a line"; a line may carry several times). A line lasts until the
 * next one, at most [LONGEST_LYRIC_LINE_MS]; a time with no words only ends the line before it.
 */
internal fun parseLrc(text: String): List<RawCaptionCue> {
    val timed =
        text
            .lineSequence()
            .flatMap { line ->
                val times = LRC_TIME.findAll(line).toList()
                val words = line.replace(LRC_TIME, "").replace(Regex("\\s+"), " ").trim()
                times.map { match ->
                    val (minutes, seconds, fraction) = match.destructured
                    val millis = if (fraction.isEmpty()) 0L else (fraction + "00").take(3).toLong()
                    (minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis to words
                }
            }.sortedBy { it.first }
            .toList()
    return timed.mapIndexedNotNull { index, (start, words) ->
        if (words.isEmpty()) return@mapIndexedNotNull null
        val next = timed.getOrNull(index + 1)?.first
        val end = (next?.takeIf { it > start } ?: (start + LONGEST_LYRIC_LINE_MS)).coerceAtMost(start + LONGEST_LYRIC_LINE_MS)
        RawCaptionCue(start, end, words)
    }
}

/** Asks LRCLIB for a song's synced lyrics; null when it has none that fit or cannot be reached. */
internal class SyncedLyricsSource(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(LYRICS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(LYRICS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(LYRICS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build(),
) {
    suspend fun find(
        title: String,
        channel: String,
        videoDurationMs: Long?,
        slackMs: Long = LYRICS_LENGTH_SLACK_MS,
    ): SyncedLyrics? = search(title, channel)?.let { pickSyncedLyrics(it, title, videoDurationMs, slackMs) }

    /** LRCLIB's search reply for the song [title] names, for [pickSyncedLyrics]; null when it can't be reached. */
    suspend fun search(
        title: String,
        channel: String,
    ): String? {
        val query = lyricsSearchQuery(title, channel) ?: return null
        return withContext(Dispatchers.IO) { runCatching { download(query) }.getOrNull() }
    }

    private fun download(query: String): String? {
        val request =
            Request
                .Builder()
                .url(lyricsSearchUrl(query))
                .header("Accept", "application/json")
                // LRCLIB asks apps to name themselves.
                .header("User-Agent", "DualSub-Replay/${BuildConfig.VERSION_NAME} (https://github.com/hoangkien1703/dual-sub-replay)")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.byteStream().use { readUtf8WithLimit(it, MAX_LYRICS_REPLY_BYTES) }
        }
    }
}
