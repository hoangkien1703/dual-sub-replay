package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.translation.TranslationLanguages

/** Languages written without spaces between words, so joined caption pieces get none either. */
private val UNSPACED_LANGUAGES = setOf("ja", "zh", "th", "lo", "km", "my")

/**
 * Pairs every text that translating [rows] asks for (see [rowTranslationTexts]) with YouTube's own
 * translated captions [cues]. YouTube translates its caption track cue by cue with the original
 * timing, while the app's rows are cut differently (whole sentences), so each cue goes to the row it
 * overlaps most, and a sentence's translation (and each of its row prefixes) joins the cues of its
 * rows. Texts whose rows got no cue are left out, so the caller can translate them another way. The
 * first occurrence of a repeated text wins.
 */
internal fun youTubeRowTranslations(
    rows: List<SubtitleSegment>,
    cues: List<RawCaptionCue>,
    targetLanguage: String,
): Map<String, String> {
    val separator = if (TranslationLanguages.normalize(targetLanguage).substringBefore('-') in UNSPACED_LANGUAGES) "" else " "
    val pieces = assignCuesToRows(rows, cues)
    val translations = linkedMapOf<String, String>()
    var index = 0
    while (index < rows.size) {
        val sentence = rows[index].sentence
        // The rows of one sentence follow each other; a lone row is a sentence of its own.
        var end = index + 1
        if (sentence != null) {
            while (end < rows.size && rows[end].sentence?.let { it.text == sentence.text && it.cuts == sentence.cuts } == true) end++
        }
        val parts = (index until end).map { pieces[it] }
        if (parts.any { it.isNotEmpty() }) {
            val join = { count: Int -> parts.take(count).flatten().joinToString(separator) }
            if (sentence == null) {
                translations.putIfAbsent(rows[index].originalText, join(1))
            } else {
                translations.putIfAbsent(sentence.text, join(parts.size))
                sentence.cuts.forEachIndexed { cut, offset ->
                    translations.putIfAbsent(sentence.text.substring(0, offset).trim(), join(cut + 1))
                }
            }
        }
        index = end
    }
    return translations
}

/** Each cue's text, given to the row it overlaps most (cues and rows both in time order). */
private fun assignCuesToRows(
    rows: List<SubtitleSegment>,
    cues: List<RawCaptionCue>,
): List<List<String>> {
    val pieces = List(rows.size) { mutableListOf<String>() }
    var first = 0
    for (cue in cues.sortedBy(RawCaptionCue::startMs)) {
        val text = cue.text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) continue
        while (first < rows.size && rows[first].endMs <= cue.startMs) first++
        var best = -1
        var bestOverlap = 0L
        var row = first
        while (row < rows.size && rows[row].startMs < cue.endMs) {
            val overlap = minOf(rows[row].endMs, cue.endMs) - maxOf(rows[row].startMs, cue.startMs)
            if (overlap > bestOverlap) {
                best = row
                bestOverlap = overlap
            }
            row++
        }
        if (best >= 0) pieces[best] += text
    }
    return pieces
}
