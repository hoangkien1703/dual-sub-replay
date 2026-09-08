package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment

const val KARAOKE_TIMING_MODE_PREFERENCE = "karaoke_timing_mode"
internal const val LIVE_CAPTION_STALE_MS = 2_000L
internal const val LIVE_CAPTION_BACKWARD_SEEK_RESET_MS = 450L

internal data class LiveCaptionSample(
    val text: String,
    val revision: Long,
    val mediaTimeMs: Long,
    val present: Boolean,
    val videoId: String? = null,
    val languageCode: String? = null,
    val activeWordIndex: Int? = null,
)

internal data class KaraokePosition(
    val segmentIndex: Int,
    val wordIndex: Int,
) : Comparable<KaraokePosition> {
    override fun compareTo(other: KaraokePosition): Int =
        compareValuesBy(this, other, KaraokePosition::segmentIndex, KaraokePosition::wordIndex)
}

private data class TranscriptWordRef(
    val segmentIndex: Int,
    val wordIndex: Int,
    val token: String,
)

private val karaokeTokenRegex = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")

internal fun karaokeTokens(text: String): List<String> =
    karaokeTokenRegex
        .findAll(text)
        .map { it.value.lowercase() }
        .toList()

/**
 * Maps the active word in YouTube's rolling caption onto nearby transcript words.
 * The flattened window deliberately crosses segment boundaries so sentence splitting
 * and merged transcript lines do not break the live karaoke position.
 */
internal fun mapLiveCaptionWord(
    segments: List<SubtitleSegment>,
    referenceSegmentIndex: Int,
    liveTokens: List<String>,
    liveActiveWordIndex: Int,
    previousPosition: KaraokePosition? = null,
): KaraokePosition? {
    if (segments.isEmpty() || liveActiveWordIndex !in liveTokens.indices) return null
    val safeReference = referenceSegmentIndex.coerceIn(0, segments.lastIndex)
    val firstSegment = (safeReference - LIVE_MAPPING_SEGMENT_RADIUS).coerceAtLeast(0)
    val lastSegment = (safeReference + LIVE_MAPPING_SEGMENT_RADIUS).coerceAtMost(segments.lastIndex)
    val refs =
        buildList {
            for (segmentIndex in firstSegment..lastSegment) {
                segments[segmentIndex].words.forEachIndexed { wordIndex, word ->
                    karaokeTokens(word.text).forEach { token ->
                        add(TranscriptWordRef(segmentIndex, wordIndex, token))
                    }
                }
            }
        }
    if (refs.isEmpty()) return null

    val expectedRefIndex =
        refs
            .indexOfFirst { ref ->
                previousPosition?.let {
                    ref.segmentIndex == it.segmentIndex && ref.wordIndex == it.wordIndex
                } ?: (ref.segmentIndex == safeReference)
            }.takeIf { it >= 0 } ?: 0

    data class Candidate(
        val matchedCount: Int,
        val activeRefIndex: Int,
        val position: KaraokePosition,
        val distance: Int,
        val regresses: Boolean,
    )

    val candidates = mutableListOf<Candidate>()
    for (liveStart in liveTokens.indices) {
        for (refStart in refs.indices) {
            var count = 0
            while (
                liveStart + count < liveTokens.size &&
                refStart + count < refs.size &&
                liveTokens[liveStart + count] == refs[refStart + count].token
            ) {
                count += 1
            }
            val activeOffset = liveActiveWordIndex - liveStart
            if (count <= 0 || activeOffset !in 0 until count) continue
            val activeRefIndex = refStart + activeOffset
            val activeRef = refs[activeRefIndex]
            val position = KaraokePosition(activeRef.segmentIndex, activeRef.wordIndex)
            val closeSingleToken =
                count >= MIN_LIVE_MAPPING_CONTEXT_TOKENS ||
                    liveTokens.size == 1 ||
                    kotlin.math.abs(activeRefIndex - expectedRefIndex) <= MAX_SINGLE_TOKEN_MAPPING_DISTANCE
            if (!closeSingleToken) continue
            candidates +=
                Candidate(
                    matchedCount = count,
                    activeRefIndex = activeRefIndex,
                    position = position,
                    distance = kotlin.math.abs(activeRefIndex - expectedRefIndex),
                    regresses = previousPosition != null && position < previousPosition,
                )
        }
    }

    return candidates
        .sortedWith(
            compareBy<Candidate> { it.regresses }
                .thenByDescending { it.matchedCount }
                .thenBy { it.distance },
        ).firstOrNull()
        ?.position
}

private const val LIVE_MAPPING_SEGMENT_RADIUS = 2
private const val MIN_LIVE_MAPPING_CONTEXT_TOKENS = 2
private const val MAX_SINGLE_TOKEN_MAPPING_DISTANCE = 4
