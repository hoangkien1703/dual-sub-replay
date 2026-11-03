package com.kienhoang.dualsubreplay.data

import java.security.MessageDigest
import java.util.Locale

/** A tapped word or phrase. [parts] lists every word of a phrase; a single word is its own only part. */
data class WordTap(
    val token: AnalyzedToken,
    val segment: SubtitleSegment?,
    val translated: Boolean,
    val parts: List<AnalyzedToken> = listOf(token),
)

data class LearningWordSelection(
    val token: AnalyzedToken,
    val wordLanguage: String,
    val meaningLanguage: String,
    val videoId: String?,
    val segment: SubtitleSegment?,
    val translated: Boolean,
    val parts: List<AnalyzedToken> = listOf(token),
    /** The line's third subtitle line (Settings → Layout & format) and its language; null while that line is off or not translated yet. */
    val thirdText: String? = null,
    val thirdLanguage: String? = null,
) {
    val isPhrase: Boolean get() = parts.size > 1
}

internal fun learningSelection(
    tap: WordTap,
    source: String,
    target: String,
    videoId: String?,
): LearningWordSelection =
    LearningWordSelection(
        token = tap.token,
        wordLanguage = if (tap.translated) target else source,
        meaningLanguage = if (tap.translated) source else target,
        videoId = videoId,
        segment = tap.segment,
        translated = tap.translated,
        parts = tap.parts,
    )

/** The language of original subtitle lines, falling back to English until one is known. */
internal fun learningSourceLanguage(
    resolved: String?,
    preference: String,
): String = resolved ?: preference.takeUnless { it == "auto" } ?: "en"

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
    val dueAt: Long = 0,
    val intervalMs: Long = 0,
    /** The deck the learner put the word in; null keeps it in its language's automatic deck. */
    val deck: String? = null,
    /** The video's title when the word was saved (shown and filtered in Practice); null for older words. Never part of the id. */
    val sourceTitle: String? = null,
    /** The learner's own notes, typed in Practice; never filled in automatically. */
    val notes: String = "",
    /**
     * The line's third subtitle line when the word was saved, and its language; null when that line was off
     * (or for older words). Shown in Practice, kept in backups and Export, and sent to Anki with the translation.
     * Never part of the id.
     */
    val thirdSentence: String? = null,
    val thirdLanguage: String? = null,
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

/** The same word from the same line gets the same id, so saving it again updates it instead of adding a copy. */
internal fun savedWordId(
    word: String,
    wordLanguage: String,
    meaningLanguage: String,
    videoId: String?,
    segment: SubtitleSegment?,
): String {
    val key = listOf(word.lowercase(Locale.ROOT), wordLanguage, meaningLanguage,
        videoId.orEmpty(), segment?.startMs.toString(), segment?.endMs.toString()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** The longest video title kept with a word; YouTube's own limit is 100 characters. */
internal const val MAX_SOURCE_TITLE_LENGTH = 200

/** The longest notes kept with one word. */
internal const val MAX_WORD_NOTES_LENGTH = 5_000

/** A title on one line, trimmed and cut to [MAX_SOURCE_TITLE_LENGTH]; null when blank. */
internal fun cleanSourceTitle(title: String?): String? =
    title
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_SOURCE_TITLE_LENGTH)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Saving a word again (same word, same line) keeps its review schedule, and the notes and video title it
 * already has when the new copy has none.
 */
internal fun mergeResaved(
    old: SavedWord,
    new: SavedWord,
): SavedWord =
    new.withThirdLineOf(old).copy(
        dueAt = old.dueAt,
        intervalMs = old.intervalMs,
        sourceTitle = new.sourceTitle ?: old.sourceTitle,
        notes = new.notes.ifBlank { old.notes },
    )

/** [this] with [old]'s third line when it has none of its own (an older file, or the line off while saving again). */
internal fun SavedWord.withThirdLineOf(old: SavedWord): SavedWord =
    if (thirdSentence != null) this else copy(thirdSentence = old.thirdSentence, thirdLanguage = old.thirdLanguage)

/** A third line's text, or null when blank. */
internal fun cleanThirdSentence(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

/** A third line's language when it looks like a language code, else null. */
internal fun cleanThirdLanguage(language: String?): String? =
    language?.trim()?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) }

internal fun savedWordFrom(
    selection: LearningWordSelection,
    meaning: String,
    online: Boolean,
    sourceTitle: String? = null,
): SavedWord {
    val segment = selection.segment
    val word = selection.token.text.trim()
    val id = savedWordId(word, selection.wordLanguage, selection.meaningLanguage, selection.videoId, segment)
    val hasClip = validClipRange(selection.videoId, segment?.startMs ?: -1, segment?.endMs ?: -1)
    val saved = SavedWord(id, word, selection.token.reading, selection.wordLanguage, selection.meaningLanguage,
        meaning.trim(), segment?.originalText.orEmpty(), segment?.translatedText, selection.videoId,
        segment?.startMs ?: 0, segment?.endMs ?: 0, selection.translated,
        online = online && hasClip, sourceTitle = cleanSourceTitle(sourceTitle))
    return saved.withThirdLine(selection.thirdText, selection.thirdLanguage)
}

/** [this] with a third line, or with none when [text] is blank. */
internal fun SavedWord.withThirdLine(
    text: String?,
    language: String?,
): SavedWord {
    val third = cleanThirdSentence(text)
    return copy(thirdSentence = third, thirdLanguage = third?.let { cleanThirdLanguage(language) })
}
