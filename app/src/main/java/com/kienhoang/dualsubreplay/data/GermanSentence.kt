package com.kienhoang.dualsubreplay.data

/** One token of a German line with its part of speech tag and every reading the lexicon gives it. */
internal class GermanWord(
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val tag: String,
    val punctuation: Boolean,
    val verbs: List<GermanVerbReading>,
    val nouns: List<GermanNounReading>,
    val adjectives: List<GermanAdjectiveReading>,
) {
    val lower: String = text.lowercase()

    fun isTag(vararg tags: String): Boolean = tag in tags

    /** Readings of this word as a finite verb (a person is set). */
    val finite: List<GermanVerbReading> get() = verbs.filter { it.cell.person > 0 }

    fun verbWith(
        tense: GermanTense,
        person: Int = 0,
    ): List<GermanVerbReading> = verbs.filter { it.cell.tense == tense && (person == 0 || it.cell.person == person) }

    fun isForm(
        lemma: String,
        tense: GermanTense? = null,
    ): Boolean = verbs.any { it.verb.base == lemma && (tense == null || it.cell.tense == tense) }
}

/** A line split into words and clauses; clauses end at a comma, semicolon, colon or sentence mark. */
internal class GermanSentence(
    val text: String,
    val words: List<GermanWord>,
) {
    /** The clause number of each word (punctuation belongs to the clause it ends). */
    val clauseOf: IntArray =
        IntArray(words.size).also { clauses ->
            var clause = 0
            words.forEachIndexed { i, word ->
                clauses[i] = clause
                if (word.punctuation && word.text in CLAUSE_MARKS) clause++
            }
        }

    fun clauseRange(index: Int): IntRange {
        val clause = clauseOf[index]
        val first = clauseOf.indexOfFirst { it == clause }
        val last = clauseOf.indexOfLast { it == clause }
        return first..last
    }

    /** The words of the clause around [index] that are not punctuation. */
    fun clauseWords(index: Int): List<GermanWord> = clauseRange(index).map { words[it] }.filter { !it.punctuation }

    /** The index of the next word after [index] in the same clause, or -1. */
    fun nextInClause(index: Int): Int =
        (index + 1).takeIf { it < words.size && clauseOf[it] == clauseOf[index] && !words[it].punctuation } ?: -1

    fun isSentenceStart(index: Int): Boolean = words.take(index).all { it.punctuation && it.text in "\"'„“«(" }

    /** The terminating mark of the sentence the word [index] is in: "?", "!", "." or "" when there is none. */
    fun endMark(index: Int): String {
        for (i in index until words.size) if (words[i].punctuation && words[i].text in ".!?") return words[i].text
        return ""
    }

    private companion object {
        const val CLAUSE_MARKS = ",;:.!?"
    }
}

private val NON_VERB_TAGS = setOf("NOUN", "PROPN", "PUNCT", "DET", "ADP", "CCONJ", "SCONJ", "NUM", "SYM", "PRON")
private val NOUN_TAGS = setOf("NOUN", "PROPN", "X", "ADJ")
private val ADJECTIVE_TAGS = setOf("ADJ", "ADV", "VERB")
private val NOUNISH_TAGS = setOf("PROPN", "NOUN", "X")
private val CLOSED_CLASS_TAGS = setOf("ADP", "CCONJ", "SCONJ", "DET", "PRON", "PART", "INTJ")

/**
 * Whether a word the tagger gave a noun tag, or a closed-class tag it is not a member of, is really a verb. German nouns
 * are capitalised, so a lowercase "noun" is a mis-tag (ruf mich an), unless the line has no capitals at all, where
 * spelling says nothing. A capitalised word
 * that opens a sentence is a verb when no noun reading exists, or when it has an imperative reading and an article
 * or object pronoun follows, which no noun could be followed by (Mach das Fenster zu!).
 */
private fun isMistaggedVerb(
    matches: List<MatchResult>,
    index: Int,
    nouns: List<GermanNounReading>,
    readings: List<GermanVerbReading>,
    lineHasCapitals: Boolean,
): Boolean {
    val word = matches[index].value
    if (!word.first().isUpperCase()) return lineHasCapitals
    val opens = index == 0 || matches[index - 1].value in setOf(".", "!", "?")
    if (!opens) return false
    if (nouns.isEmpty()) return true
    val next = matches.getOrNull(index + 1)?.value?.lowercase()
    val objectFollows = next != null && (next in GERMAN_DETERMINERS || next in GERMAN_PRONOUN_CASES)
    return objectFollows && readings.any { it.cell.tense == GermanTense.IMPERATIVE }
}

/** The words of [text] with the lexicon's readings, cut exactly as the tagger's tokens are. */
internal fun germanSentence(
    tagger: OpenNlpPosTagger,
    lexicon: GermanLexicon,
    text: String,
): GermanSentence {
    val matches = ENGLISH_TOKEN.findAll(text).toList()
    val tags = tagger.tag(matches.map { it.value.replace('’', '\'') })
    val anyCapital = matches.any { it.groups[1] != null && it.value.first().isUpperCase() }
    return GermanSentence(text, matches.indices.map { germanWord(matches, it, tags[it], anyCapital, lexicon) })
}

private fun germanWord(
    matches: List<MatchResult>,
    index: Int,
    tag: String,
    anyCapital: Boolean,
    lexicon: GermanLexicon,
): GermanWord {
    val match = matches[index]
    val word = match.value
    val start = match.range.first
    val end = match.range.last + 1
    if (match.groups[1] == null) return GermanWord(index, word, start, end, "PUNCT", true, emptyList(), emptyList(), emptyList())
    val capital = word.first().isUpperCase()
    val nouns = if (capital && tag in NOUN_TAGS) lexicon.nounReadings(word) else emptyList()
    val readings = lexicon.verbReadings(word)
    val unreliableTag = tag in NOUNISH_TAGS || (tag in CLOSED_CLASS_TAGS && word.lowercase() !in GERMAN_FUNCTION_WORDS)
    val verbForNoun = unreliableTag && readings.isNotEmpty() && isMistaggedVerb(matches, index, nouns, readings, anyCapital)
    var adjectives =
        if (tag in ADJECTIVE_TAGS ||
            isOpeningAdjective(matches, index, lexicon)
        ) {
            lexicon.adjectiveReadings(word)
        } else {
            emptyList()
        }
    if (adjectives.isEmpty() && tag == "ADJ") adjectives = guessedAdjectiveEnding(word)
    return GermanWord(
        index = index,
        text = word,
        start = start,
        end = end,
        tag = if (verbForNoun) "VERB" else tag,
        punctuation = false,
        verbs = if (tag in NON_VERB_TAGS && !verbForNoun) emptyList() else readings,
        nouns = nouns,
        adjectives = adjectives,
    )
}

/** A capitalised adjective that opens a sentence before a noun (Frische Milch) is often tagged as a noun. */
private fun isOpeningAdjective(
    matches: List<MatchResult>,
    index: Int,
    lexicon: GermanLexicon,
): Boolean {
    if (index != 0 || !matches[index].value.first().isUpperCase()) return false
    val next = matches.getOrNull(index + 1)?.value ?: return false
    return next.first().isUpperCase() && lexicon.nounReadings(next).isNotEmpty()
}

/** An adjective the lexicon does not list: its ending guessed from the spelling, so the noun phrase can still be read. */
private fun guessedAdjectiveEnding(word: String): List<GermanAdjectiveReading> {
    val lower = word.lowercase()
    val ending =
        listOf("en", "em", "er", "es", "e").firstOrNull { lower.endsWith(it) && lower.length > it.length + 1 } ?: return emptyList()
    val unknown = GermanAdjective(lower, lower, lower)
    return listOf(GermanAdjectiveReading(unknown, GermanDegree.POSITIVE, ending))
}

/** A noun with its determiner and attributive adjectives, and the features that all of them allow together. */
internal class GermanNounPhrase(
    val noun: Int,
    val determiner: Int?,
    val adjectives: List<Int>,
    val features: Set<GermanFeature>,
    val declension: GermanDeclension,
    /** False when the determiner or an adjective ending contradicted the noun, so only the noun's own readings are used. */
    val consistent: Boolean,
    /** Degree words between the determiner and the adjectives (sehr, ganz); part of the phrase's evidence. */
    val intensifiers: List<Int> = emptyList(),
) {
    val first: Int get() = determiner ?: intensifiers.firstOrNull() ?: adjectives.firstOrNull() ?: noun
    val cases: Set<GermanCase> get() = features.mapTo(HashSet()) { it.case }
    val indices: List<Int> get() = listOfNotNull(determiner) + intensifiers + adjectives + noun
}

/**
 * The noun phrase whose head is the noun at [noun], or null when that word is not a listed noun.
 * [implied] are the features of an article contracted with the preposition before it (im, zum).
 */
internal fun germanNounPhrase(
    sentence: GermanSentence,
    noun: Int,
    implied: Set<GermanFeature>? = null,
): GermanNounPhrase? {
    val head = sentence.words[noun]
    if (head.nouns.isEmpty()) return null
    var features =
        head.nouns
            .flatMap { reading ->
                if (reading.plural) {
                    reading.cases.map { GermanFeature(it, null, true) }
                } else {
                    reading.noun.genders.flatMap { g -> reading.cases.map { GermanFeature(it, g, false) } }
                }
            }.toSet()
    var consistent = true
    var i = noun - 1
    val adjectives = ArrayList<Int>()
    val intensifiers = ArrayList<Int>()
    while (i >= 0 && sentence.clauseOf[i] == sentence.clauseOf[noun] && !sentence.words[i].punctuation) {
        val word = sentence.words[i]
        // A degree word is skipped only when an adjective follows it (ein ganz neues Kleid), never directly before the noun.
        if (word.lower in GERMAN_INTENSIFIERS && adjectives.isNotEmpty() && sentence.words[adjectives.first()].adjectives.isNotEmpty()) {
            intensifiers.add(0, i)
            i--
            continue
        }
        if (word.lower in GERMAN_DETERMINERS || word.adjectives.none { it.ending.isNotEmpty() }) break
        adjectives.add(0, i)
        i--
    }
    val determinerIndex =
        if (i >= 0 && sentence.clauseOf[i] == sentence.clauseOf[noun] &&
            sentence.words[i].lower in GERMAN_DETERMINERS
        ) {
            i
        } else {
            null
        }
    var declension = GermanDeclension.STRONG
    if (determinerIndex == null && implied != null) {
        // A contraction (im = in dem) is the determiner: it narrows the features and makes the adjectives weak.
        val narrowed = features intersect implied
        if (narrowed.isEmpty()) consistent = false else features = narrowed
        declension = GermanDeclension.WEAK
    }
    if (determinerIndex != null) {
        val readings = GERMAN_DETERMINERS.getValue(sentence.words[determinerIndex].lower)
        val allowed = readings.flatMap { it.features }.toSet()
        val narrowed = features intersect allowed
        if (narrowed.isEmpty()) consistent = false else features = narrowed
        declension = readings.first().declension
    }
    val endings = GERMAN_ADJECTIVE_ENDINGS.getValue(declension)
    for (adjective in adjectives) {
        val allowed =
            sentence.words[adjective]
                .adjectives
                .flatMap { endings[it.ending].orEmpty() }
                .toSet()
        val narrowed = features intersect allowed
        if (narrowed.isEmpty()) consistent = false else features = narrowed
    }
    return GermanNounPhrase(noun, determinerIndex, adjectives, features, declension, consistent, intensifiers)
}

/** The cases a personal pronoun can stand in (mir is only dative, sie is nominative or accusative). */
internal val GERMAN_PRONOUN_CASES: Map<String, Set<GermanCase>> =
    mapOf(
        "ich" to setOf(GermanCase.NOMINATIVE),
        "du" to setOf(GermanCase.NOMINATIVE),
        "er" to setOf(GermanCase.NOMINATIVE),
        "wir" to setOf(GermanCase.NOMINATIVE),
        "mich" to setOf(GermanCase.ACCUSATIVE),
        "dich" to setOf(GermanCase.ACCUSATIVE),
        "ihn" to setOf(GermanCase.ACCUSATIVE),
        "mir" to setOf(GermanCase.DATIVE),
        "dir" to setOf(GermanCase.DATIVE),
        "ihm" to setOf(GermanCase.DATIVE),
        "ihnen" to setOf(GermanCase.DATIVE),
        "uns" to setOf(GermanCase.DATIVE, GermanCase.ACCUSATIVE),
        "euch" to setOf(GermanCase.DATIVE, GermanCase.ACCUSATIVE),
        "sie" to setOf(GermanCase.NOMINATIVE, GermanCase.ACCUSATIVE),
        "es" to setOf(GermanCase.NOMINATIVE, GermanCase.ACCUSATIVE),
    )
