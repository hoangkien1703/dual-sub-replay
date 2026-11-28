package com.kienhoang.dualsubreplay.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kienhoang.dualsubreplay.data.SubtitleSegment

/** The shortest a picked line plays, so a line with a broken end time still plays. */
private const val PRACTICE_LINE_MIN_MS = 500L

/** A line picked in Whole transcript to practice: its row id and where it plays. */
internal data class PracticeLine(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
)

internal fun SubtitleSegment.toPracticeLine(): PracticeLine = PracticeLine(id, startMs, endMs.coerceAtLeast(startMs + PRACTICE_LINE_MIN_MS))

/** The picked lines of [videoId] being practiced, in video order; a new [token] starts them again. */
internal data class LinePracticeList(
    val videoId: String,
    val lines: List<PracticeLine>,
    val token: Long,
)

/**
 * Practice picked lines (ansidalia-lansi, Discussion #257, 2026-11-27): in Whole transcript the
 * learner picks lines, and Practice plays only those, one after another, each with Study tools'
 * repeat count and pause between repeats (Forever goes round the picked lines again and again).
 * A tapped line, a seek or another video ends it; after the last line the video pauses.
 */
internal class LinePractice {
    private var tokens = 0L

    /** The lines playing now, or null. */
    var list by mutableStateOf<LinePracticeList?>(null)

    /** The video [picked] belongs to. */
    var pickedVideo by mutableStateOf<String?>(null)
        private set

    /** The lines picked in Whole transcript by row id, kept while the app runs. */
    val picked = mutableStateMapOf<Long, PracticeLine>()

    /** Picks [line] of [videoId], or unpicks it; picks of another video are dropped first. */
    fun toggle(
        videoId: String,
        line: PracticeLine,
    ) {
        if (pickedVideo != videoId) {
            picked.clear()
            pickedVideo = videoId
        }
        if (picked.remove(line.id) == null) picked[line.id] = line
    }

    fun pickedLines(videoId: String?): Map<Long, PracticeLine> = if (videoId != null && videoId == pickedVideo) picked else emptyMap()

    fun clearPicks() {
        picked.clear()
    }

    /** Plays [videoId]'s picked lines. */
    fun start(videoId: String) {
        val lines = pickedLines(videoId).values.sortedBy { it.startMs }
        if (lines.isEmpty()) return
        list = LinePracticeList(videoId, lines, ++tokens)
    }

    /** The driver ended the list with [token] (the last line played, or the learner moved). */
    fun ended(token: Long) {
        if (list?.token == token) list = null
    }
}

internal val LocalLinePractice = staticCompositionLocalOf<LinePractice?> { null }
