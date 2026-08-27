package com.kienhoang.dualsubreplay.alignment

internal const val CTC_BLANK_ID = 0
internal const val CTC_WORD_DELIMITER_ID = 4
internal const val CTC_VOCAB_SIZE = 32

internal data class CtcTargetLabel(
    val tokenId: Int,
    val wordIndex: Int,
)

internal data class CtcTarget(
    val labels: List<CtcTargetLabel>,
    val normalizedText: String,
)

internal data class CtcFrameSpan(
    val firstFrame: Int,
    val lastFrame: Int,
)

private val ctcCharacterToId = mapOf(
    'E' to 5,
    'T' to 6,
    'A' to 7,
    'O' to 8,
    'N' to 9,
    'I' to 10,
    'H' to 11,
    'S' to 12,
    'R' to 13,
    'D' to 14,
    'L' to 15,
    'U' to 16,
    'M' to 17,
    'W' to 18,
    'C' to 19,
    'F' to 20,
    'G' to 21,
    'Y' to 22,
    'P' to 23,
    'B' to 24,
    'V' to 25,
    'K' to 26,
    '\'' to 27,
    'X' to 28,
    'J' to 29,
    'Q' to 30,
    'Z' to 31,
)

private val ctcIdToCharacter = ctcCharacterToId.entries.associate { (character, id) -> id to character }

/**
 * Converts visible caption words into the 32-token `facebook/wav2vec2-base-960h`
 * CTC alphabet. Numbers and unsupported symbols are skipped rather than guessed;
 * those visible words keep their existing fallback timing.
 */
internal fun ctcTargetForWords(words: List<String>): CtcTarget? {
    val normalizedWords = words.mapIndexedNotNull { index, word ->
        val normalized = buildString {
            word.uppercase().forEach { character ->
                when {
                    character in 'A'..'Z' -> append(character)
                    character == '\'' || character == '’' -> append('\'')
                }
            }
        }
        normalized.takeIf(String::isNotEmpty)?.let { index to it }
    }
    if (normalizedWords.isEmpty()) return null

    val labels = mutableListOf<CtcTargetLabel>()
    normalizedWords.forEachIndexed { position, (wordIndex, normalized) ->
        if (position > 0) {
            labels += CtcTargetLabel(CTC_WORD_DELIMITER_ID, wordIndex = -1)
        }
        normalized.forEach { character ->
            labels += CtcTargetLabel(
                tokenId = requireNotNull(ctcCharacterToId[character]),
                wordIndex = wordIndex,
            )
        }
    }
    return CtcTarget(
        labels = labels,
        normalizedText = normalizedWords.joinToString(" ") { it.second },
    )
}

/** Greedy CTC decoding used only as a conservative quality gate before forced alignment. */
internal fun greedyCtcText(
    logits: FloatArray,
    frameCount: Int,
    vocabSize: Int,
): String {
    if (frameCount <= 0 || vocabSize <= 0 || logits.size < frameCount * vocabSize) return ""
    val output = StringBuilder()
    var previousId = -1

    for (frame in 0 until frameCount) {
        val base = frame * vocabSize
        var bestId = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (id in 0 until vocabSize) {
            val score = logits[base + id]
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        if (bestId != previousId && bestId != CTC_BLANK_ID) {
            when (bestId) {
                CTC_WORD_DELIMITER_ID -> {
                    if (output.isNotEmpty() && output.last() != ' ') output.append(' ')
                }
                else -> ctcIdToCharacter[bestId]?.let(output::append)
            }
        }
        previousId = bestId
    }
    return output.toString().trim().replace(Regex("\\s+"), " ")
}

/**
 * Returns how much of [target] can be found in order inside [observed].
 * The observed ASR window intentionally contains context before/after a cue,
 * so ordinary edit distance would unfairly punish those extra spoken words.
 */
internal fun orderedTextCoverage(target: String, observed: String): Float {
    val left = target.filter { it in 'A'..'Z' || it == '\'' || it == ' ' }
    val right = observed.filter { it in 'A'..'Z' || it == '\'' || it == ' ' }
    if (left.isEmpty() || right.isEmpty()) return 0f

    val previous = IntArray(right.length + 1)
    val current = IntArray(right.length + 1)
    for (i in left.indices) {
        for (j in right.indices) {
            current[j + 1] = if (left[i] == right[j]) {
                previous[j] + 1
            } else {
                maxOf(current[j], previous[j + 1])
            }
        }
        current.copyInto(previous)
        current.fill(0)
    }
    return previous[right.length].toFloat() / left.length
}

/**
 * Word-level companion to [orderedTextCoverage]. Character coverage alone can
 * look high when a noisy window contains repeated short words in the wrong
 * places. Requiring ordered whole-word matches makes enhanced alignment fail
 * closed instead of forcing a plausible-looking but incorrect CTC path.
 */
internal fun orderedWordCoverage(target: String, observed: String): Float {
    fun tokens(value: String): List<String> = Regex("[A-Z]+(?:'[A-Z]+)?")
        .findAll(value.uppercase())
        .map { it.value }
        .toList()

    val left = tokens(target)
    val right = tokens(observed)
    if (left.isEmpty() || right.isEmpty()) return 0f

    val previous = IntArray(right.size + 1)
    val current = IntArray(right.size + 1)
    for (i in left.indices) {
        for (j in right.indices) {
            current[j + 1] = if (left[i] == right[j]) {
                previous[j] + 1
            } else {
                maxOf(current[j], previous[j + 1])
            }
        }
        current.copyInto(previous)
        current.fill(0)
    }
    return previous[right.size].toFloat() / left.size
}

/**
 * Standard CTC Viterbi trellis. The expanded state sequence inserts a blank
 * between every transcript token, which correctly handles repeated letters.
 */
internal fun viterbiCtcAlignment(
    logits: FloatArray,
    frameCount: Int,
    vocabSize: Int,
    target: CtcTarget,
): List<CtcFrameSpan>? {
    if (target.labels.isEmpty() || frameCount <= 0 || vocabSize <= 0) return null
    if (logits.size < frameCount * vocabSize) return null
    if (target.labels.any { it.tokenId !in 0 until vocabSize }) return null

    val stateCount = target.labels.size * 2 + 1
    val previous = FloatArray(stateCount) { Float.NEGATIVE_INFINITY }
    val current = FloatArray(stateCount) { Float.NEGATIVE_INFINITY }
    val predecessorSteps = ByteArray(frameCount * stateCount) { (-1).toByte() }

    fun symbolForState(state: Int): Int =
        if (state % 2 == 0) CTC_BLANK_ID else target.labels[(state - 1) / 2].tokenId

    fun emission(frame: Int, state: Int): Float =
        logits[frame * vocabSize + symbolForState(state)]

    previous[0] = emission(0, 0)
    predecessorSteps[0] = 0
    if (stateCount > 1) {
        previous[1] = emission(0, 1)
        predecessorSteps[1] = 0
    }

    for (frame in 1 until frameCount) {
        current.fill(Float.NEGATIVE_INFINITY)
        for (state in 0 until stateCount) {
            var bestScore = previous[state]
            var bestStep = 0

            if (state >= 1 && previous[state - 1] > bestScore) {
                bestScore = previous[state - 1]
                bestStep = 1
            }
            if (
                state >= 2 &&
                state % 2 == 1 &&
                symbolForState(state) != symbolForState(state - 2) &&
                previous[state - 2] > bestScore
            ) {
                bestScore = previous[state - 2]
                bestStep = 2
            }
            if (bestScore.isFinite()) {
                current[state] = bestScore + emission(frame, state)
                predecessorSteps[frame * stateCount + state] = bestStep.toByte()
            }
        }
        current.copyInto(previous)
    }

    val lastBlankState = stateCount - 1
    val lastLabelState = stateCount - 2
    var state = if (previous[lastBlankState] >= previous[lastLabelState]) {
        lastBlankState
    } else {
        lastLabelState
    }
    if (!previous[state].isFinite()) return null

    val firstFrames = IntArray(target.labels.size) { Int.MAX_VALUE }
    val lastFrames = IntArray(target.labels.size) { -1 }
    for (frame in frameCount - 1 downTo 0) {
        if (state % 2 == 1) {
            val labelIndex = (state - 1) / 2
            firstFrames[labelIndex] = minOf(firstFrames[labelIndex], frame)
            lastFrames[labelIndex] = maxOf(lastFrames[labelIndex], frame)
        }
        if (frame > 0) {
            val step = predecessorSteps[frame * stateCount + state].toInt()
            if (step !in 0..2) return null
            state -= step
            if (state < 0) return null
        }
    }

    return target.labels.indices.map { index ->
        val first = firstFrames[index]
        val last = lastFrames[index]
        if (first == Int.MAX_VALUE || last < first) return null
        CtcFrameSpan(firstFrame = first, lastFrame = last)
    }
}

internal fun ctcWordFrameSpans(
    target: CtcTarget,
    labelSpans: List<CtcFrameSpan>,
): Map<Int, CtcFrameSpan> {
    if (target.labels.size != labelSpans.size) return emptyMap()
    val result = linkedMapOf<Int, CtcFrameSpan>()
    target.labels.forEachIndexed { index, label ->
        if (label.wordIndex < 0) return@forEachIndexed
        val span = labelSpans[index]
        val existing = result[label.wordIndex]
        result[label.wordIndex] = if (existing == null) {
            span
        } else {
            CtcFrameSpan(
                firstFrame = minOf(existing.firstFrame, span.firstFrame),
                lastFrame = maxOf(existing.lastFrame, span.lastFrame),
            )
        }
    }
    return result
}
