package com.kienhoang.dualsubreplay.data

/*
 * Compilations (owner pick 2026-11-17): a long music video made of many songs lists them in its
 * description as timestamps ("03:25 Artist - Song"), which is also how YouTube makes its chapters.
 * Each song's synced lyrics are looked up on their own and moved to the song's start, so most
 * compilations need no Gemini at all.
 */

/** One song of a compilation: where it starts and ends in the video, and its name. */
internal data class SongChapter(
    val startMs: Long,
    val endMs: Long,
    val title: String,
)

/** A timestamp in a description line: 3:25, 03:25 or 1:03:25, alone or in brackets. */
private val CHAPTER_TIME = Regex("""(?<![\d:])[(\[]?(\d{1,2}(?::\d{2}){1,2})[)\]]?(?![\d:])""")

/** Numbering and separators around a chapter's name: "1.", "01)", "-", "|", "–". */
private val CHAPTER_EDGES = Regex("""^[\s\-–—|:.•]*(\d{1,3}[.)]\s+)?|[\s\-–—|:.•]+$""")

/** A compilation needs at least this many songs; YouTube needs 3 timestamps for chapters too. */
private const val MIN_CHAPTERS = 3

/**
 * The songs of a compilation from its [description], in time order: lines with one timestamp and a
 * name, the first at the start of the video; songs listed from [durationMs] on are left out. Empty
 * when the description does not list songs like that.
 */
internal fun descriptionChapters(
    description: String,
    durationMs: Long,
): List<SongChapter> {
    val starts =
        description
            .lineSequence()
            .mapNotNull { line ->
                val times = CHAPTER_TIME.findAll(line).toList()
                if (times.size != 1) return@mapNotNull null
                val time = times.single()
                val name =
                    line
                        .removeRange(time.range)
                        .replace(CHAPTER_EDGES, "")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                if (name.length < 2) return@mapNotNull null
                chapterTimeMs(time.groupValues[1])?.let { it to name }
            }.toList()
    if (starts.size < MIN_CHAPTERS || starts.first().first != 0L) return emptyList()
    if (starts.zipWithNext().any { (a, b) -> b.first <= a.first }) return emptyList()
    // A description often lists a song the video was cut short of (seen 2026-11-25): only those are left out.
    val inVideo = starts.filter { (start, _) -> start < durationMs }
    if (inVideo.size < MIN_CHAPTERS) return emptyList()
    return inVideo.mapIndexed { index, (start, name) ->
        SongChapter(start, inVideo.getOrNull(index + 1)?.first ?: durationMs, name)
    }
}

/**
 * A listed song: "Artist - Song" or "Song – Artist", often numbered ("01.", "1)", "#1"). A long dash
 * may lose a space ("Lose control —Teddy Swims"); a hyphen needs both, so "Hip-hop" stays one name.
 */
private val LISTED_SONG = Regex("""^\s*(?:#?\d{1,3}\s*[.)\-:]?\s+)?(.{2,80}?(?:\s*[–—]\s*|\s-\s).{2,80}?)\s*$""")

/** Lines that are links, hashtags or credits, never a song. */
private val NOT_A_SONG =
    Regex("""https?://|www\.|^\s*#[^\d\s]|@\w|(?i)\b(subscribe|instagram|facebook|tiktok\.com|spotify\.com|copyright|licensed)\b""")

/**
 * The songs a playlist video's [description] lists without times (owner pick 2026-11-24): at least
 * [MIN_CHAPTERS] lines like "01. Taylor Swift - The Fate of Ophelia". Their lyrics are then placed
 * by matching them against the auto-generated captions ([alignLyrics]). Empty when there is no such list.
 */
internal fun descriptionSongList(description: String): List<String> {
    val songs =
        description
            .lineSequence()
            .filterNot { NOT_A_SONG.containsMatchIn(it) || CHAPTER_TIME.containsMatchIn(it) }
            .mapNotNull { line ->
                LISTED_SONG
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
            }.distinctBy { it.lowercase() }
            .toList()
    return if (songs.size >= MIN_CHAPTERS) songs else emptyList()
}

private fun chapterTimeMs(text: String): Long? {
    val parts = text.split(':').map { it.toLongOrNull() ?: return null }
    if (parts.drop(1).any { it >= 60 }) return null
    return parts.fold(0L) { total, part -> total * 60 + part } * 1000
}

/** The chapters in the order to look them up: from the one playing, forward, then from the start. */
internal fun chaptersFromPlaying(
    chapters: List<SongChapter>,
    positionMs: Long,
): List<SongChapter> {
    val playing = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    return chapters.drop(playing) + chapters.take(playing)
}

/** A compilation song's synced lyrics, already moved to where the song starts in the video. */
internal data class LyricChapter(
    val startMs: Long,
    val endMs: Long,
    val cues: List<RawCaptionCue>,
)

/** [lyrics] of a song moved to [chapter]'s start; lines past the chapter's end are left out. */
internal fun lyricChapter(
    chapter: SongChapter,
    lyrics: SyncedLyrics,
): LyricChapter =
    LyricChapter(
        chapter.startMs,
        chapter.endMs,
        lyrics.cues
            .map { it.copy(startMs = it.startMs + chapter.startMs, endMs = it.endMs + chapter.startMs) }
            .filter { it.startMs < chapter.endMs }
            .map { it.copy(endMs = it.endMs.coerceAtMost(chapter.endMs)) },
    )

/**
 * Remembers the descriptions of the last few videos whose captions were looked up, so a
 * compilation's songs can be read from it after a tap without asking YouTube again. Memory only.
 */
internal object VideoDescriptions {
    private const val KEPT = 8
    private val descriptions = LinkedHashMap<String, String>()

    @Synchronized
    fun remember(
        videoId: String,
        description: String?,
    ) {
        if (videoId.isBlank() || description.isNullOrBlank()) return
        descriptions.remove(videoId)
        descriptions[videoId] = description.take(MAX_DESCRIPTION_CHARS)
        while (descriptions.size > KEPT) descriptions.remove(descriptions.keys.first())
    }

    @Synchronized
    fun of(videoId: String): String? = descriptions[videoId]

    /** YouTube allows 5,000 characters; anything longer is not a description. */
    private const val MAX_DESCRIPTION_CHARS = 10_000
}
