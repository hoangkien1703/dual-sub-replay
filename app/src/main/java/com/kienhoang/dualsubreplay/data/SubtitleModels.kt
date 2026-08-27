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
)

enum class SubtitleTimingSource {
    YOUTUBE_EXACT,
    ACOUSTIC_ALIGNED,
    YOUTUBE_DOM_OBSERVED,
    ESTIMATED,
}

/** A single spoken word/chunk with its absolute timing inside the video. */
data class SubtitleWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val timingSource: SubtitleTimingSource = SubtitleTimingSource.ESTIMATED,
)

data class CaptionTrackResult(
    val languageCode: String,
    val isGenerated: Boolean,
    val cues: List<RawCaptionCue>,
    val availableLanguages: List<CaptionLanguage> = emptyList(),
)

data class CaptionLanguage(
    val code: String,
    val name: String,
)