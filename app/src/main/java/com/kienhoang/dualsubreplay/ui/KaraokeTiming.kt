package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment

const val KARAOKE_TIMING_MODE_PREFERENCE = "karaoke_timing_mode"
internal const val LIVE_CAPTION_STALE_MS = 2_000L
internal const val LIVE_CAPTION_BACKWARD_SEEK_RESET_MS = 450L

enum class KaraokeTimingMode(val storageValue: String) {
    ADAPTIVE("adaptive"),
    YOUTUBE_LIVE("youtube_live"),
    TRANSCRIPT("transcript"),
}

internal fun storedKaraokeTimingMode(raw: String?): KaraokeTimingMode =
    KaraokeTimingMode.entries.firstOrNull { it.storageValue == raw }
        ?: KaraokeTimingMode.ADAPTIVE

internal fun shouldCaptureLiveCaptions(
    mode: KaraokeTimingMode,
    generatedCaptions: Boolean,
    wordHighlightEnabled: Boolean,
): Boolean = generatedCaptions &&
    wordHighlightEnabled &&
    mode != KaraokeTimingMode.TRANSCRIPT

internal data class LiveCaptionSample(
    val text: String,
    val revision: Long,
    val mediaTimeMs: Long,
    val present: Boolean,
)

internal data class KaraokePosition(
    val segmentIndex: Int,
    val wordIndex: Int,
) : Comparable<KaraokePosition> {
    override fun compareTo(other: KaraokePosition): Int =
        compareValuesBy(this, other, KaraokePosition::segmentIndex, KaraokePosition::wordIndex)
}

internal fun effectiveKaraokePosition(
    mode: KaraokeTimingMode,
    generatedCaptions: Boolean,
    wordHighlightEnabled: Boolean,
    timedPosition: KaraokePosition?,
    livePosition: KaraokePosition?,
): KaraokePosition? = when {
    !wordHighlightEnabled -> null
    !generatedCaptions -> timedPosition
    mode == KaraokeTimingMode.TRANSCRIPT -> timedPosition
    livePosition != null -> livePosition
    mode == KaraokeTimingMode.YOUTUBE_LIVE -> null
    else -> timedPosition
}

internal data class LiveCaptionProgress(
    val text: String,
    val tokens: List<String>,
    val activeWordIndex: Int,
    val revision: Long,
)

private data class TranscriptWordRef(
    val segmentIndex: Int,
    val wordIndex: Int,
    val token: String,
)

private val karaokeTokenRegex = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""")

internal fun karaokeTokens(text: String): List<String> = karaokeTokenRegex
    .findAll(text)
    .map { it.value.lowercase() }
    .toList()

internal fun longestSuffixPrefixOverlap(
    previousTokens: List<String>,
    currentTokens: List<String>,
): Int {
    val limit = minOf(previousTokens.size, currentTokens.size)
    for (size in limit downTo 1) {
        if (previousTokens.takeLast(size) == currentTokens.take(size)) return size
    }
    return 0
}

internal fun reconcileLiveCaptionProgress(
    previous: LiveCaptionProgress?,
    sample: LiveCaptionSample,
): LiveCaptionProgress? {
    if (!sample.present) return null
    val cleanText = sample.text.replace(Regex("\\s+"), " ").trim()
    val currentTokens = karaokeTokens(cleanText)
    if (cleanText.isBlank() || currentTokens.isEmpty()) return null
    if (previous == null) {
        return LiveCaptionProgress(cleanText, currentTokens, 0, sample.revision)
    }
    if (sample.revision == previous.revision) return previous

    if (currentTokens == previous.tokens) {
        return previous.copy(text = cleanText, revision = sample.revision)
    }

    val prefixGrowth = currentTokens.size > previous.tokens.size &&
        previous.tokens.indices.all { index -> previous.tokens[index] == currentTokens[index] }
    if (prefixGrowth) {
        return LiveCaptionProgress(cleanText, currentTokens, currentTokens.lastIndex, sample.revision)
    }

    val overlap = longestSuffixPrefixOverlap(previous.tokens, currentTokens)
    if (overlap > 0) {
        val dropped = previous.tokens.size - overlap
        val mappedOldIndex = (previous.activeWordIndex - dropped).coerceAtLeast(0)
        val appendedCount = currentTokens.size - overlap
        val activeIndex = if (appendedCount > 0) {
            currentTokens.lastIndex
        } else {
            mappedOldIndex.coerceAtMost(currentTokens.lastIndex)
        }
        return LiveCaptionProgress(cleanText, currentTokens, activeIndex, sample.revision)
    }

    val previousActiveToken = previous.tokens.getOrNull(previous.activeWordIndex)
    val remappedIndex = previousActiveToken?.let { currentTokens.indexOfLast { token -> token == it } }
        ?.takeIf { it >= 0 }
    return LiveCaptionProgress(
        text = cleanText,
        tokens = currentTokens,
        activeWordIndex = remappedIndex ?: 0,
        revision = sample.revision,
    )
}

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
    val refs = buildList {
        for (segmentIndex in firstSegment..lastSegment) {
            segments[segmentIndex].words.forEachIndexed { wordIndex, word ->
                karaokeTokens(word.text).forEach { token ->
                    add(TranscriptWordRef(segmentIndex, wordIndex, token))
                }
            }
        }
    }
    if (refs.isEmpty()) return null

    val expectedRefIndex = refs.indexOfFirst { ref ->
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
            val closeSingleToken = count >= MIN_LIVE_MAPPING_CONTEXT_TOKENS ||
                liveTokens.size == 1 ||
                kotlin.math.abs(activeRefIndex - expectedRefIndex) <= MAX_SINGLE_TOKEN_MAPPING_DISTANCE
            if (!closeSingleToken) continue
            candidates += Candidate(
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
        )
        .firstOrNull()
        ?.position
}

/** Stateful arbitration kept outside the ViewModel so rolling-caption transitions are testable. */
internal class LiveCaptionTracker {
    private var progress: LiveCaptionProgress? = null
    private var lastProcessedRevision: Long = Long.MIN_VALUE
    private var coherentRevisionCount = 0
    private var lastPosition: KaraokePosition? = null
    private var lastMappedMediaTimeMs: Long = Long.MIN_VALUE

    fun reset() {
        progress = null
        lastProcessedRevision = Long.MIN_VALUE
        coherentRevisionCount = 0
        lastPosition = null
        lastMappedMediaTimeMs = Long.MIN_VALUE
    }

    fun resolve(
        sample: LiveCaptionSample?,
        segments: List<SubtitleSegment>,
        referenceSegmentIndex: Int,
        playbackTimeMs: Long,
        strict: Boolean,
    ): KaraokePosition? {
        if (sample == null || !sample.present) {
            reset()
            return null
        }
        if (sample.revision != lastProcessedRevision) {
            lastProcessedRevision = sample.revision
            progress = reconcileLiveCaptionProgress(progress, sample)
            val currentProgress = progress
            val mapped = currentProgress?.let {
                mapLiveCaptionWord(
                    segments = segments,
                    referenceSegmentIndex = referenceSegmentIndex,
                    liveTokens = it.tokens,
                    liveActiveWordIndex = it.activeWordIndex,
                    previousPosition = lastPosition,
                )
            }
            if (mapped == null) {
                coherentRevisionCount = 0
                lastPosition = null
            } else {
                coherentRevisionCount += 1
                lastPosition = mapped
                lastMappedMediaTimeMs = sample.mediaTimeMs
            }
        }

        val mapped = lastPosition ?: return null
        val stale = playbackTimeMs - lastMappedMediaTimeMs > LIVE_CAPTION_STALE_MS
        if (stale) {
            reset()
            return null
        }
        return if (strict || coherentRevisionCount >= MIN_ADAPTIVE_COHERENT_REVISIONS) mapped else null
    }
}

private const val LIVE_MAPPING_SEGMENT_RADIUS = 2
private const val MIN_LIVE_MAPPING_CONTEXT_TOKENS = 2
private const val MAX_SINGLE_TOKEN_MAPPING_DISTANCE = 4
private const val MIN_ADAPTIVE_COHERENT_REVISIONS = 2
