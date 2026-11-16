package com.kienhoang.dualsubreplay.data

/**
 * What a card's back reads aloud (Flashcards & Anki → Read aloud on the back): the meanings in the
 * order the Meaning field shows them, and the sentence in the meaning's language. All in
 * [SavedWord.meaningLanguage].
 */
internal data class ReadAloudText(
    val meanings: List<String> = emptyList(),
    val translation: String? = null,
)

/**
 * The parts of [word] that [readAloud] asks for. The saved meaning is "in sentence (dictionary)",
 * or "dictionary (in sentence)" when [contextFirst] is false; a meaning without that shape (the
 * dictionary's alone, or one the learner typed) counts as both. A part that is blank, repeats an
 * earlier one, or is only the word itself is left out.
 */
internal fun readAloudTexts(
    word: SavedWord,
    readAloud: CardReadAloud,
    contextFirst: Boolean = true,
): ReadAloudText {
    val (first, second) = splitSavedMeaning(word.meaning)
    val wanted =
        if (second == null) {
            listOf(first to (readAloud.meaningInSentence || readAloud.dictionaryMeaning))
        } else {
            val firstIsContext = contextFirst
            listOf(
                first to if (firstIsContext) readAloud.meaningInSentence else readAloud.dictionaryMeaning,
                second to if (firstIsContext) readAloud.dictionaryMeaning else readAloud.meaningInSentence,
            )
        }
    val kept = mutableListOf<String>()

    fun usable(text: String?): String? =
        text
            ?.trim()
            ?.takeIf { part ->
                part.isNotEmpty() &&
                    !part.equals(word.word.trim(), ignoreCase = true) &&
                    kept.none { it.equals(part, ignoreCase = true) }
            }
    wanted.forEach { (text, on) -> if (on) usable(text)?.let(kept::add) }
    // A word tapped in the translation line has its meaning in the original line's language.
    val sentence = if (word.translated) word.sentence else word.translatedSentence
    val translation = if (readAloud.translation) usable(sentence) else null
    return ReadAloudText(kept.toList(), translation)
}

/** "gedirəm (getmək)" as ("gedirəm", "getmək"); a meaning without a final "(…)" as (meaning, null). */
internal fun splitSavedMeaning(meaning: String): Pair<String, String?> {
    val match = SAVED_MEANING_PATTERN.matchEntire(meaning.trim()) ?: return meaning.trim() to null
    return match.groupValues[1].trim() to match.groupValues[2].trim()
}

private val SAVED_MEANING_PATTERN = Regex("""(.*\S) \(([^()]+)\)""")
