package com.kienhoang.dualsubreplay.data

import java.util.Locale

/*
 * Placing a song's free synced lyrics in a long video (owner pick 2026-11-24): a playlist video
 * often lists its songs without times, and covers run at their own length. YouTube's auto-generated
 * captions get the sung words wrong but their timing right, so the lyrics are matched against
 * them: pairs of words both share become anchors, the longest chain of anchors that keeps the song's
 * pace (within [MIN_PACE]..[MAX_PACE]) says where each lyric line is sung, and lines between anchors
 * are spread evenly. Everything runs on the phone; nothing is sent anywhere.
 */

/** A cover may be sung this much slower or faster than the recording the lyrics were timed on. */
private const val MIN_PACE = 0.75
private const val MAX_PACE = 1.35

/** Words of two anchors may drift this far from the pace (the captions' own timing is rough). */
private const val PACE_SLACK_MS = 4_000L

/** A word pair the captions repeat more often than this ("i love", "you know") says nothing about where a song is. */
private const val MAX_PAIR_REPEATS = 6

/** A placed song needs at least this many anchors on this many different lines... */
private const val MIN_ANCHORS = 6
private const val MIN_ANCHORED_LINES = 4

/** ...on at least this share of its lyric lines... */
private const val MIN_LINE_SHARE = 0.2

/** ...spread over at least this share of the song. */
private const val MIN_SPAN_SHARE = 0.35

/** At most this many anchors are chained; a song's lyrics give a few hundred at most. */
private const val MAX_ANCHORS = 4_000

/** The last line of a placed song lasts at least this long into the video. */
private const val SONG_TAIL_MS = 2_000L

/** Two placed songs may overlap this much where one fades into the next. */
private const val SONG_OVERLAP_MS = 5_000L

/** A word of a line and when it is sung: its line's time spread evenly over the line's words. */
private class TimedWord(
    val word: String,
    val atMs: Long,
    val line: Int,
)

/** One word pair the lyrics and the captions share: when the lyrics sing it, and when the video does. */
private class Anchor(
    val lyricMs: Long,
    val videoMs: Long,
    val line: Int,
)

/** Han characters and kana are words of their own: those languages write no spaces. */
private val NO_SPACE_SCRIPT = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]")

private val WORD = Regex("[\\p{L}\\p{N}]+")

/** The words of [text] as the matching compares them: lower case, without punctuation or apostrophes. */
internal fun matchWords(text: String): List<String> =
    WORD
        .findAll(text.replace("'", "").replace("’", "").lowercase(Locale.ROOT))
        .flatMap { match ->
            val word = match.value
            if (NO_SPACE_SCRIPT.containsMatchIn(word)) word.map(Char::toString).asSequence() else sequenceOf(word)
        }.toList()

private fun timedWords(cues: List<RawCaptionCue>): List<TimedWord> =
    cues.flatMapIndexed { line, cue ->
        val words = matchWords(cue.text)
        val length = (cue.endMs - cue.startMs).coerceAtLeast(0)
        words.mapIndexed { index, word -> TimedWord(word, cue.startMs + length * index / words.size.coerceAtLeast(1), line) }
    }

/**
 * [lyrics] (timed from the song's own start) placed in the video by [captions] (YouTube's
 * auto-generated lines of the whole video), looking only between [fromMs] and [toMs]. Null when
 * the captions don't sing enough of the song for its place to be sure.
 */
internal fun alignLyrics(
    lyrics: List<RawCaptionCue>,
    captions: List<RawCaptionCue>,
    fromMs: Long = 0L,
    toMs: Long = Long.MAX_VALUE,
): LyricChapter? {
    val sung = lyrics.filter { matchWords(it.text).isNotEmpty() }
    if (sung.isEmpty()) return null
    val chain = anchorChain(sung, captions.filter { it.startMs >= fromMs && it.startMs < toMs })
    if (!chainPlacesSong(chain, sung)) return null
    val placed =
        sung.map { cue ->
            val start = videoTime(chain, cue.startMs)
            val end = videoTime(chain, cue.endMs).coerceAtLeast(start + 1)
            cue.copy(startMs = start, endMs = end, words = emptyList())
        }
    val start = placed.first().startMs.coerceAtLeast(fromMs)
    val end = (placed.last().endMs + SONG_TAIL_MS).coerceAtMost(toMs)
    val cues = placed.filter { it.startMs in start until end }.map { it.copy(endMs = it.endMs.coerceAtMost(end)) }
    return if (cues.isEmpty()) null else LyricChapter(start, end, cues)
}

/** The longest run of anchors, in time order in both the lyrics and the video, that keeps the song's pace. */
private fun anchorChain(
    lyrics: List<RawCaptionCue>,
    captions: List<RawCaptionCue>,
): List<Anchor> {
    val videoWords = timedWords(captions)
    val pairs = HashMap<String, MutableList<Long>>()
    videoWords.zipWithNext().forEach { (a, b) -> pairs.getOrPut("${a.word} ${b.word}") { ArrayList() } += a.atMs }
    val anchors =
        timedWords(lyrics)
            .zipWithNext()
            .flatMap { (a, b) ->
                val times = pairs["${a.word} ${b.word}"].orEmpty()
                if (times.size > MAX_PAIR_REPEATS) emptyList() else times.map { Anchor(a.atMs, it, a.line) }
            }.sortedWith(compareBy({ it.lyricMs }, { it.videoMs }))
            .take(MAX_ANCHORS)
    if (anchors.isEmpty()) return emptyList()
    val length = IntArray(anchors.size) { 1 }
    val previous = IntArray(anchors.size) { -1 }
    for (j in anchors.indices) {
        for (i in 0 until j) {
            if (length[i] + 1 > length[j] && keepsPace(anchors[i], anchors[j])) {
                length[j] = length[i] + 1
                previous[j] = i
            }
        }
    }
    var at = length.indices.maxBy { length[it] }
    val chain = ArrayList<Anchor>()
    while (at >= 0) {
        chain += anchors[at]
        at = previous[at]
    }
    return chain.reversed()
}

private fun keepsPace(
    earlier: Anchor,
    later: Anchor,
): Boolean {
    val lyricGap = later.lyricMs - earlier.lyricMs
    val videoGap = later.videoMs - earlier.videoMs
    if (lyricGap <= 0 || videoGap <= 0) return false
    return videoGap >= lyricGap * MIN_PACE - PACE_SLACK_MS && videoGap <= lyricGap * MAX_PACE + PACE_SLACK_MS
}

private fun chainPlacesSong(
    chain: List<Anchor>,
    lyrics: List<RawCaptionCue>,
): Boolean {
    if (chain.size < MIN_ANCHORS) return false
    val lines = chain.map { it.line }.distinct().size
    if (lines < MIN_ANCHORED_LINES || lines < lyrics.size * MIN_LINE_SHARE) return false
    val songMs = (lyrics.last().endMs - lyrics.first().startMs).coerceAtLeast(1)
    return chain.last().lyricMs - chain.first().lyricMs >= songMs * MIN_SPAN_SHARE
}

/** Where the song's [lyricMs] is in the video: between two anchors in proportion, outside them at the song's own pace. */
private fun videoTime(
    chain: List<Anchor>,
    lyricMs: Long,
): Long {
    val after = chain.indexOfFirst { it.lyricMs >= lyricMs }
    return when {
        after == 0 -> chain.first().videoMs - (chain.first().lyricMs - lyricMs)
        after < 0 -> chain.last().videoMs + (lyricMs - chain.last().lyricMs)
        else -> {
            val a = chain[after - 1]
            val b = chain[after]
            val span = (b.lyricMs - a.lyricMs).coerceAtLeast(1)
            a.videoMs + (b.videoMs - a.videoMs) * (lyricMs - a.lyricMs) / span
        }
    }.coerceAtLeast(0L)
}

/**
 * The songs that fit together: when two placed songs overlap by more than a fade, the one with more
 * lyric lines in the video wins. In time order.
 */
internal fun withoutOverlaps(songs: List<LyricChapter>): List<LyricChapter> {
    val kept = ArrayList<LyricChapter>()
    for (song in songs.sortedByDescending { it.cues.size }) {
        val clash = kept.any { other -> minOf(song.endMs, other.endMs) - maxOf(song.startMs, other.startMs) > SONG_OVERLAP_MS }
        if (!clash) kept += song
    }
    return kept.sortedBy { it.startMs }
}
