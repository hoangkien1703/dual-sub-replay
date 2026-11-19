package com.kienhoang.dualsubreplay.ui

import java.util.Locale

/** A roll-up line unchanged this long counts as finished even without a full stop. */
internal const val ROLL_UP_PAUSE_MS = 2_500L

/** A sentence this long without a full stop is shown anyway, so a fast speaker is not hidden. */
internal const val ROLL_UP_MAX_SENTENCE_WORDS = 30

/** Words of rolled-up text kept to join the next caption window onto. */
private const val ROLL_UP_KEPT_WORDS = 200

/** TV-style captions mark a new speaker with `>>` (and a new story with `>>>`). */
private val SPEAKER_MARK = Regex(">{2,}")
private val SENTENCE_END = Regex("[.?!…。？！][\"'”’)\\]]*$")
private val ENGLISH_I = Regex("\\bi(?=$|\\s|'|’)")

/**
 * Live streams with TV-style captions (ABC News Live, for example) send them inside the video, and
 * YouTube's player draws them as a roll-up window: two or three lines that scroll up word by word,
 * often in capitals, with no caption track the app could download. Translating that window on
 * every new word would flicker and send a translation request for each word. This joins the
 * windows into running text and hands on one finished sentence at a time, in sentence case.
 *
 * Samples that are not roll-up captions pass through unchanged.
 */
internal class RollUpCaptionAssembler {
    private var videoId: String? = null
    private val words = ArrayList<String>()

    /** Number of [words] already handed on as sentences. */
    private var handedOn = 0
    private var window: List<String> = emptyList()
    private var windowChangedAtMs = 0L
    private var shown: String? = null

    fun reset() {
        videoId = null
        words.clear()
        handedOn = 0
        window = emptyList()
        windowChangedAtMs = 0L
        shown = null
    }

    /**
     * The sample to show and translate: the latest finished sentence of a roll-up caption, else
     * [sample]. [nowMs] is the playback time now (the sample's own time only moves when its text does).
     */
    fun transform(
        sample: LiveCaptionSample?,
        nowMs: Long,
        seek: Boolean = false,
    ): LiveCaptionSample? {
        if (sample == null || !sample.rollUp) {
            if (videoId != null) reset()
            return sample
        }
        if (seek || sample.videoId != videoId) {
            reset()
            videoId = sample.videoId
        }
        val next = if (sample.present) sample.text.split(' ').filter(String::isNotBlank) else emptyList()
        if (next != window) {
            // The last word may still be typing ("QUICK W" becomes "QUICK WIT."), so it joins later.
            join(next.dropLast(1))
            window = next
            windowChangedAtMs = nowMs
        } else if (next.isNotEmpty() && nowMs - windowChangedAtMs >= ROLL_UP_PAUSE_MS) {
            // The speaker paused: the whole window is finished, full stop or not.
            join(next)
            handOn(words.size, sample.languageCode)
        }
        lastSentenceEnd()?.let { handOn(it, sample.languageCode) }
        if (words.size - handedOn >= ROLL_UP_MAX_SENTENCE_WORDS) handOn(words.size, sample.languageCode)
        val text = shown ?: return sample.copy(text = "", present = false)
        return sample.copy(text = text, present = true)
    }

    /** Adds the words of [next] that the running text does not end with already. */
    private fun join(next: List<String>) {
        if (next.isEmpty()) return
        val overlap =
            (minOf(words.size, next.size) downTo 1).firstOrNull { size ->
                (0 until size).all { i -> words[words.size - size + i] == next[i] }
            } ?: 0
        words.addAll(next.drop(overlap))
        if (words.size > ROLL_UP_KEPT_WORDS) {
            val drop = words.size - ROLL_UP_KEPT_WORDS
            words.subList(0, drop).clear()
            handedOn = (handedOn - drop).coerceAtLeast(0)
        }
    }

    /** One past the last word of the running text that ends a sentence, after what was handed on. */
    private fun lastSentenceEnd(): Int? {
        for (index in words.indices.reversed()) {
            if (index < handedOn) return null
            val word = words[index]
            if (SENTENCE_END.containsMatchIn(word)) return index + 1
            // A speaker mark ends the sentence before it.
            if (SPEAKER_MARK.matches(word) && index > handedOn) return index
        }
        return null
    }

    private fun handOn(
        end: Int,
        language: String?,
    ) {
        if (end <= handedOn) return
        val sentence = rollUpSentenceText(words.subList(handedOn, end).joinToString(" "), language)
        handedOn = end
        if (sentence.isNotEmpty()) shown = sentence
    }
}

/** Removes speaker marks and turns all-capital caption text into sentence case. */
internal fun rollUpSentenceText(
    text: String,
    language: String? = null,
): String {
    val plain = text.replace(SPEAKER_MARK, " ").replace(Regex("\\s+"), " ").trim()
    return if (mostlyCapitals(plain)) sentenceCase(plain, english = language?.substringBefore('-') == "en") else plain
}

private fun mostlyCapitals(text: String): Boolean {
    val cased = text.filter { it.isLetter() && (it.isUpperCase() || it.isLowerCase()) }
    return cased.length >= 4 && cased.count(Char::isUpperCase) >= cased.length * 0.8
}

private fun sentenceCase(
    text: String,
    english: Boolean,
): String {
    val lower = text.lowercase(Locale.ROOT).let { if (english) it.replace(ENGLISH_I, "I") else it }
    val result = StringBuilder(lower.length)
    var capitalize = true
    for (char in lower) {
        if (capitalize && char.isLetter()) {
            result.append(char.uppercaseChar())
            capitalize = false
        } else {
            result.append(char)
        }
        if (char == '.' || char == '?' || char == '!') capitalize = true
    }
    return result.toString()
}
