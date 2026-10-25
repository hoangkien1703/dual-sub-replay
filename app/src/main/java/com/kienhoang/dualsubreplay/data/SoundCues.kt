package com.kienhoang.dualsubreplay.data

/**
 * Sound descriptions in captions, such as [Music], [Applause] or (laughs), and the ♪ marks around
 * song lyrics. Settings → Translation → Hide sound descriptions (on by default, owner decision
 * 2026-10-25, issue #152) removes them before lines are merged and translated: a cue left with no
 * words disappears, lyrics stay without their marks.
 */
internal object SoundCues {
    // Square brackets never hold speech in captions.
    private val bracketed = Regex("[\\[［][^\\[\\]［］]{0,80}[\\]］]")
    private val parenthesized = Regex("[(（]([^()（）]{1,40})[)）]")
    private val musicNotes = Regex("[♪♫♬♩]+")
    private val spaceBeforePunctuation = Regex("\\s+([.,!?;:。、！？])")
    private val whitespace = Regex("\\s+")

    // Brackets that are more often speech, like "(I think)", count only when they name a sound.
    private val soundWords =
        Regex(
            "^(?:[\\p{L}'’-]+\\s+){0,3}?(?:laugh|chuckl|giggl|applau|cheer|clap|music|sigh|gasp|cough|sniff|scream|groan|" +
                "grunt|sobs|sobbing|crying|cries|inaudible|indistinct|crosstalk|silence|noise|whistl|humm|sings|singing|" +
                "beep|bleep|static|risas|aplauso|música|musica|tiếng cười|vỗ tay|拍手|笑|音楽|歓声|掌声|音乐|欢呼)",
            RegexOption.IGNORE_CASE,
        )

    /** [text] without its sound descriptions; empty when nothing spoken is left. Other lines come back unchanged. */
    fun strip(text: String): String {
        val removed =
            text
                .replace(bracketed, " ")
                .replace(parenthesized) { if (isSoundDescription(it.groupValues[1])) " " else it.value }
                .replace(musicNotes, " ")
        if (removed == text) return text
        val tidy =
            removed
                .replace(whitespace, " ")
                .replace(spaceBeforePunctuation, "$1")
                .trim()
                .trimStart(':', ',', ' ')
        return if (tidy.any(Char::isLetterOrDigit)) tidy else ""
    }

    /** [cues] without sound descriptions, dropping cues that held nothing else. */
    fun filter(cues: List<RawCaptionCue>): List<RawCaptionCue> =
        cues.mapNotNull { cue ->
            val text = strip(cue.text)
            when {
                text.isEmpty() -> null
                text == cue.text -> cue
                else -> cue.copy(text = text, words = spokenWords(cue.words))
            }
        }

    // "(audience laughs)" must name a sound; "(LAUGHTER)" in capitals is a description too.
    private fun isSoundDescription(inside: String): Boolean {
        val content = inside.trim()
        val letters = content.filter(Char::isLetter)
        val shouting = letters.length >= 3 && letters.none(Char::isLowerCase) && letters.any(Char::isUpperCase)
        return shouting || soundWords.containsMatchIn(content)
    }

    // Timed words come one by one, so "[Bird" and "chirping]" are dropped by following the brackets.
    private fun spokenWords(words: List<SubtitleWord>): List<SubtitleWord> {
        var inside = false
        return words.mapNotNull { word ->
            val kept = StringBuilder()
            word.text.forEach { char ->
                when (char) {
                    '[', '［' -> inside = true
                    ']', '］' -> inside = false
                    else -> if (!inside) kept.append(char)
                }
            }
            val text = kept.toString().replace(musicNotes, "")
            when {
                text == word.text -> word
                text.none(Char::isLetterOrDigit) -> null
                else -> word.copy(text = text)
            }
        }
    }
}
