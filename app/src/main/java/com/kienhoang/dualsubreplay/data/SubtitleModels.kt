package com.kienhoang.dualsubreplay.data

data class RawCaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<SubtitleWord> = emptyList(),
)

data class SubtitleSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String? = null,
    val words: List<SubtitleWord> = emptyList(),
    val sentence: SentenceSlice? = null,
)

/**
 * The complete sentence a short display row belongs to. [cuts] are the character offsets where the
 * sentence's rows start (after the first) and [index] is this row. The sentence is translated once
 * so ML Kit sees full context; every row derives the same split of that translation.
 */
data class SentenceSlice(
    val text: String,
    val cuts: List<Int>,
    val index: Int,
)

/** A single spoken word/chunk with its absolute timing inside the video. */
data class SubtitleWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class CaptionTrackResult(
    val languageCode: String,
    val isGenerated: Boolean,
    val cues: List<RawCaptionCue>,
    val availableLanguages: List<CaptionLanguage> = emptyList(),
    /** YouTube's id of the track the cues came from (`vssId`, such as `a.ja`), or blank when unknown. */
    val trackId: String = "",
)

/** One caption track of a video, as the "Original caption language" list offers it. */
data class CaptionLanguage(
    val code: String,
    val name: String,
    /** YouTube's id of this track (`vssId`); blank for a language without a known track. */
    val trackId: String = "",
    val generated: Boolean = false,
)
