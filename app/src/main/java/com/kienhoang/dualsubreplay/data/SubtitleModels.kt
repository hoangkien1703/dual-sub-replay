package com.kienhoang.dualsubreplay.data

data class RawCaptionCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class SubtitleSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String? = null,
)

data class CaptionTrackResult(
    val languageCode: String,
    val isGenerated: Boolean,
    val cues: List<RawCaptionCue>,
)
