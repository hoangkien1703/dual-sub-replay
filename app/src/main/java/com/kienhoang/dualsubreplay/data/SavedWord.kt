package com.kienhoang.dualsubreplay.data

import java.security.MessageDigest
import java.util.Locale

data class WordTap(val token: AnalyzedToken, val segment: SubtitleSegment?, val translated: Boolean)

data class LearningWordSelection(
    val token: AnalyzedToken,
    val wordLanguage: String,
    val meaningLanguage: String,
    val videoId: String?,
    val segment: SubtitleSegment?,
    val translated: Boolean,
)

data class SavedWord(
    val id: String,
    val word: String,
    val reading: String?,
    val wordLanguage: String,
    val meaningLanguage: String,
    val meaning: String,
    val sentence: String,
    val translatedSentence: String?,
    val videoId: String?,
    val startMs: Long,
    val endMs: Long,
    val translated: Boolean,
    val online: Boolean = true,
    val offline: Boolean = false,
    val clipStatus: String = "none",
    val clipError: String? = null,
    val clipGeneration: Long = 0,
    val dueAt: Long = 0,
    val intervalMs: Long = 0,
)

enum class ReviewRating { AGAIN, HARD, GOOD, EASY }
internal const val DAY_MS = 86_400_000L

internal fun reviewInterval(previous: Long, rating: ReviewRating): Long = when (rating) {
    ReviewRating.AGAIN -> 600_000L
    ReviewRating.HARD -> if (previous < DAY_MS) DAY_MS else (previous * 1.2).toLong()
    ReviewRating.GOOD -> if (previous < DAY_MS) 3 * DAY_MS else previous * 2
    ReviewRating.EASY -> if (previous < DAY_MS) 7 * DAY_MS else previous * 3
}.coerceIn(600_000L, 3650 * DAY_MS)

internal fun reviewWord(word: SavedWord, rating: ReviewRating, now: Long): SavedWord {
    val interval = reviewInterval(word.intervalMs, rating)
    return word.copy(dueAt = now + interval, intervalMs = interval)
}

internal fun validClipRange(videoId: String?, start: Long, end: Long): Boolean =
    videoId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true && start >= 0 && end > start

internal fun savedWordFrom(selection: LearningWordSelection, meaning: String, online: Boolean, offline: Boolean): SavedWord {
    val segment = selection.segment
    val word = selection.token.text.trim()
    val key = listOf(word.lowercase(Locale.ROOT), selection.wordLanguage, selection.meaningLanguage,
        selection.videoId.orEmpty(), segment?.startMs.toString(), segment?.endMs.toString()).joinToString("\u0000")
    val id = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val hasClip = validClipRange(selection.videoId, segment?.startMs ?: -1, segment?.endMs ?: -1)
    return SavedWord(id, word, selection.token.reading, selection.wordLanguage, selection.meaningLanguage,
        meaning.trim(), segment?.originalText.orEmpty(), segment?.translatedText, selection.videoId,
        segment?.startMs ?: 0, segment?.endMs ?: 0, selection.translated,
        online = online && hasClip, offline = offline && hasClip)
}
