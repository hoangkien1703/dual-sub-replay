package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AiSubtitleTrack
import com.kienhoang.dualsubreplay.data.CHAPTER_LENGTH_SLACK_MS
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.LyricChapter
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SyncedLyrics
import com.kienhoang.dualsubreplay.data.alignLyrics
import com.kienhoang.dualsubreplay.data.chapterSongName
import com.kienhoang.dualsubreplay.data.chaptersFromPlaying
import com.kienhoang.dualsubreplay.data.descriptionChapters
import com.kienhoang.dualsubreplay.data.descriptionSongList
import com.kienhoang.dualsubreplay.data.lyricChapter
import com.kienhoang.dualsubreplay.data.pickSyncedLyrics
import com.kienhoang.dualsubreplay.data.withoutOverlaps
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** LRCLIB is asked about this many songs of a playlist at once. */
private const val PARALLEL_SONG_LOOKUPS = 2

/** A pause after each song's lookup, as LRCLIB asks of apps that look up many songs (2026-11-25). */
private const val SONG_LOOKUP_GAP_MS = 250L

/** A playlist chapter's song may be sung a little before or after the time the description gives. */
private const val CHAPTER_SEARCH_SLACK_MS = 15_000L

/** A single song this long or shorter is matched against its captions when its length fits no LRCLIB song. */
private const val LONGEST_SINGLE_SONG_MS = 20 * 60_000L

/** A slowed or sped-up edit plays the whole song at another speed ("slowed + reverb", "sped up", nightcore). */
private val SPEED_CHANGED = Regex("\\b(slowed|sped[ -]?up|speed[ -]?up|nightcore|daycore)\\b", RegexOption.IGNORE_CASE)

/** A speed edit's song is this much slower or faster at most. */
private const val SLOWEST_EDIT = 0.7
private const val FASTEST_EDIT = 1.45

/**
 * [lyrics] timed for a slowed or sped-up edit of [videoMs]: every time stretched by the same share
 * (owner's song 2026-11-25, "double take" slowed to 3:06 from 2:50). Null when the lengths are too far apart.
 */
internal fun stretched(
    lyrics: SyncedLyrics,
    videoMs: Long,
): List<RawCaptionCue>? {
    val share = videoMs.toDouble() / lyrics.durationMs.coerceAtLeast(1)
    if (share !in SLOWEST_EDIT..FASTEST_EDIT) return null
    return lyrics.cues.map { it.copy(startMs = (it.startMs * share).toLong(), endMs = (it.endMs * share).toLong(), words = emptyList()) }
}

/** How far a better transcript goes (owner pick 2026-11-24: AI only when the person taps for it). */
internal enum class TranscriptAsk {
    /** The song card's "Get real lyrics": free synced lyrics only. */
    FREE_LYRICS,

    /** "AI transcript (Gemini)" or "Make subtitles with AI": free lyrics for a song first, then Gemini. */
    LYRICS_THEN_AI,

    /** "Write lyrics with AI" after no free lyrics fit: Gemini at once. */
    AI_NOW,
}

/** The open video, as the free lyrics lookup needs it. */
internal data class VideoForLyrics(
    val title: String,
    val channel: String,
    val durationMs: Long,
    val description: String,
    /** YouTube's captions of the whole video (usually auto-generated), to place lyrics by; null when none show. */
    val captions: List<RawCaptionCue>?,
    val playingMs: Long,
)

/** What the free lyrics lookup found. */
internal sealed interface FreeLyrics {
    /** One song's lyrics, in video time. */
    data class Song(
        val cues: List<RawCaptionCue>,
    ) : FreeLyrics

    /** A playlist: the songs whose lyrics were found and placed, of [total] it lists. */
    data class Playlist(
        val songs: List<LyricChapter>,
        val total: Int,
    ) : FreeLyrics

    data object None : FreeLyrics
}

/**
 * What was found, as the track saved for [videoId]: a song's lines, or a playlist's placed songs,
 * complete unless [aiForGaps] lets Gemini write the parts between them. Null when nothing was found.
 */
internal fun FreeLyrics.track(
    videoId: String,
    language: String,
    durationMs: Long,
    youtubeTracks: List<CaptionLanguage>,
    aiForGaps: Boolean,
): AiSubtitleTrack? =
    when (this) {
        FreeLyrics.None -> null
        is FreeLyrics.Song ->
            AiSubtitleTrack(
                videoId,
                language,
                mapOf(0L to cues),
                endMs = durationMs,
                complete = true,
                youtubeTracks = youtubeTracks,
                lyrics = true,
            )
        is FreeLyrics.Playlist ->
            songs.takeIf { it.isNotEmpty() }?.let {
                val track =
                    AiSubtitleTrack(videoId, language, emptyMap(), endMs = durationMs, youtubeTracks = youtubeTracks, lyricChapters = it)
                track.copy(complete = !(aiForGaps && track.hasGaps()))
            }
    }

/** For the problem log: how much was found. */
internal fun FreeLyrics.summary(): String =
    when (this) {
        FreeLyrics.None -> "none"
        is FreeLyrics.Song -> "${cues.size} lines"
        is FreeLyrics.Playlist -> "${songs.size} of $total songs"
    }

/**
 * Free synced lyrics for a song or a playlist (owner picks 2026-11-17 and 2026-11-24), never AI:
 * - a playlist whose description gives each song's start: each song's lyrics of the same length,
 *   else of any length placed by its captions near that start;
 * - a playlist whose description lists songs without times: each song's lyrics placed by matching
 *   them against the captions ([alignLyrics]);
 * - otherwise one song: lyrics of the video's length, else (up to 20 minutes) placed by its captions.
 * [search] asks LRCLIB for a title and gives its raw reply.
 */
internal class FreeLyricsLookup(
    private val search: suspend (title: String, channel: String) -> String?,
) {
    suspend fun find(
        video: VideoForLyrics,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): FreeLyrics {
        val chapters = descriptionChapters(video.description, video.durationMs)
        if (chapters.isNotEmpty()) {
            val songs =
                eachSong(chaptersFromPlaying(chapters, video.playingMs), onProgress) { chapter ->
                    val name = chapterSongName(chapter.title)
                    val reply = search(name, "") ?: return@eachSong null
                    val length = chapter.endMs - chapter.startMs
                    pickSyncedLyrics(
                        reply,
                        name,
                        length,
                        CHAPTER_LENGTH_SLACK_MS,
                    )?.let { return@eachSong lyricChapter(chapter, it) }
                    val captions = video.captions ?: return@eachSong null
                    pickSyncedLyrics(reply, name, null)?.let { lyrics ->
                        alignLyrics(
                            lyrics.cues,
                            captions,
                            chapter.startMs - CHAPTER_SEARCH_SLACK_MS,
                            chapter.endMs + CHAPTER_SEARCH_SLACK_MS,
                        )
                    }
                }
            return FreeLyrics.Playlist(withoutOverlaps(songs), chapters.size)
        }
        val listed = descriptionSongList(video.description)
        if (listed.isNotEmpty() && video.captions != null) {
            val songs =
                eachSong(listed, onProgress) { title ->
                    search(title, "")?.let { pickSyncedLyrics(it, title, null) }?.let { alignLyrics(it.cues, video.captions) }
                }
            return FreeLyrics.Playlist(withoutOverlaps(songs), listed.size)
        }
        val reply = search(video.title, video.channel) ?: return FreeLyrics.None
        pickSyncedLyrics(reply, video.title, video.durationMs)?.let { return FreeLyrics.Song(it.cues) }
        if (SPEED_CHANGED.containsMatchIn(video.title)) {
            pickSyncedLyrics(reply, video.title, null)?.let { stretched(it, video.durationMs) }?.let { return FreeLyrics.Song(it) }
        }
        if (video.captions == null || video.durationMs > LONGEST_SINGLE_SONG_MS) return FreeLyrics.None
        val placed = pickSyncedLyrics(reply, video.title, null)?.let { alignLyrics(it.cues, video.captions) }
        return placed?.let { FreeLyrics.Song(it.cues) } ?: FreeLyrics.None
    }

    /** [look] for every song, a few at a time, counting them for [onProgress]; the songs found. */
    private suspend fun <T> eachSong(
        items: List<T>,
        onProgress: (checked: Int, total: Int) -> Unit,
        look: suspend (T) -> LyricChapter?,
    ): List<LyricChapter> =
        coroutineScope {
            val permits = Semaphore(PARALLEL_SONG_LOOKUPS)
            val checked = AtomicInteger()
            onProgress(0, items.size)
            items
                .map { item ->
                    async {
                        permits
                            .withPermit { look(item).also { delay(SONG_LOOKUP_GAP_MS) } }
                            .also { onProgress(checked.incrementAndGet(), items.size) }
                    }
                }.awaitAll()
                .filterNotNull()
        }
}
