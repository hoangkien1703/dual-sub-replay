package com.kienhoang.dualsubreplay.data

/**
 * What the word card says about a tapped German word, read from its whole line: the verb with its
 * likeliest readings, the noun with its gender and plural, the adjective with its degree, and the case,
 * number and gender the word has in its noun phrase (at most [MAX_FEATURES]; more than one when the
 * sentence does not decide, as for der in der Frau).
 */
internal data class GermanWordDetails(
    val verb: GermanVerb?,
    val cells: List<GermanCell>,
    val noun: GermanNoun?,
    val adjective: GermanAdjective?,
    val degree: GermanDegree?,
    val features: List<GermanFeature>,
)

internal fun germanWordDetails(
    sentence: GermanSentence,
    start: Int,
    end: Int,
): GermanWordDetails? {
    val word = sentence.words.firstOrNull { !it.punctuation && it.start < end && it.end > start } ?: return null
    val verb = likeliestVerb(sentence, word)
    val cells =
        verb
            ?.let { v ->
                agreeingWithSubject(
                    sentence,
                    word,
                    word.verbs
                        .filter { it.verb == v }
                        .map { it.cell }
                        .distinct(),
                ).take(MAX_FEATURES)
            }.orEmpty()
    val noun = word.nouns.firstOrNull()?.noun
    val adjective = word.adjectives.firstOrNull()
    val phrase = nounPhraseOf(sentence, word)
    val features =
        phrase
            ?.features
            ?.sortedWith(
                compareBy({
                    it.case.ordinal
                }, { it.plural }, { it.gender?.ordinal ?: -1 }),
            )?.take(MAX_FEATURES)
            .orEmpty()
    if (verb == null && noun == null && adjective == null && features.isEmpty()) return null
    return GermanWordDetails(verb, cells, noun, adjective?.adjective, adjective?.degree, features)
}

/**
 * Puts first, and keeps alone, the readings whose person and number agree with a subject pronoun beside the verb (Ich gehe is
 * first person, not Konjunktiv I third person; gehen wir is first person plural, not an infinitive). Without such a pronoun, or if
 * none of the readings agree, every reading stays: the sentence does not decide.
 */
private fun agreeingWithSubject(
    sentence: GermanSentence,
    word: GermanWord,
    cells: List<GermanCell>,
): List<GermanCell> {
    val subject = subjectAgreement(sentence.words.getOrNull(word.index - 1)) ?: subjectAgreement(sentence.words.getOrNull(word.index + 1))
    val agreeing = subject?.let { (person, plural) -> cells.filter { it.person == person && it.plural == plural } }.orEmpty()
    return agreeing.ifEmpty { cells }
}

/** The person and number a pronoun sets on its verb, or null when it does not decide (sie is 3rd singular or plural). */
private fun subjectAgreement(word: GermanWord?): Pair<Int, Boolean>? =
    when {
        word == null || word.punctuation -> null
        word.text == "Sie" && word.index > 0 -> 3 to true
        else ->
            when (word.lower) {
                "ich" -> 1 to false
                "du" -> 2 to false
                "er", "es", "man" -> 3 to false
                "wir" -> 1 to true
                "ihr" -> 2 to true
                else -> null
            }
    }

/** The verb a word is most likely: a separable verb whose particle ends the clause, else a verb without prefix, else the first. */
private fun likeliestVerb(
    sentence: GermanSentence,
    word: GermanWord,
): GermanVerb? {
    val verbs = word.verbs.map { it.verb }.distinct()
    if (verbs.isEmpty()) return null
    val clause = sentence.clauseWords(word.index)
    val particle = clause.lastOrNull()?.takeIf { it.index > word.index }?.lower
    return verbs.firstOrNull { it.separable && it.prefix == particle } ?: verbs.firstOrNull { !it.separable } ?: verbs.first()
}

/** The noun phrase the word is the noun, the determiner or an adjective of. */
private fun nounPhraseOf(
    sentence: GermanSentence,
    word: GermanWord,
): GermanNounPhrase? {
    for (k in word.index until minOf(sentence.words.size, word.index + PHRASE_REACH)) {
        if (sentence.clauseOf[k] != sentence.clauseOf[word.index] || sentence.words[k].punctuation) break
        val phrase = germanNounPhrase(sentence, k) ?: continue
        if (word.index in phrase.indices) return phrase
    }
    return null
}

internal const val MAX_FEATURES = 3
private const val PHRASE_REACH = 6
