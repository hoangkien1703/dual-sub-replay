package com.kienhoang.dualsubreplay.data

/** What a tapped word is doing in its sentence, apart from any grammar point the catalogue finds. */
internal enum class GermanRole {
    PERSONAL_PRONOUN,
    REFLEXIVE_PRONOUN,
    RELATIVE_PRONOUN,
    DEMONSTRATIVE_PRONOUN,
    INDEFINITE_PRONOUN,
    INTERROGATIVE,
    DEFINITE_ARTICLE,
    INDEFINITE_ARTICLE,
    NEGATIVE_ARTICLE,
    POSSESSIVE,
    DEMONSTRATIVE_DETERMINER,
    OTHER_DETERMINER,
    PREPOSITION,
    COORDINATOR,
    SUBORDINATOR,
    ADVERB,
    DEGREE_WORD,
    ADJECTIVE_ATTRIBUTIVE,
    ADJECTIVE_PREDICATIVE,
    ADJECTIVE_ADVERBIAL,
    INFINITIVE_ZU,
}

internal enum class GermanAdverbKind { TIME, PLACE, MANNER, ATTITUDE, CONNECTIVE, NEGATION }

/** The job a pronoun does in its clause. */
internal enum class GermanSyntaxRole { SUBJECT, DIRECT_OBJECT, DATIVE_OBJECT, AFTER_PREPOSITION }

/**
 * The analysis of one word in context. Every field is only set when the sentence decides it: [cases] has several entries (and
 * [tentative] is true) when the line leaves the case open, [person] is 0 when the form could be more than one person.
 */
internal class GermanWordRole(
    val role: GermanRole,
    val person: Int = 0,
    val plural: Boolean? = null,
    val genders: Set<GermanGender> = emptySet(),
    val cases: Set<GermanCase> = emptySet(),
    val function: GermanSyntaxRole? = null,
    /** The word the role depends on: the verb (lemma), the preposition, or the noun a relative pronoun points back to. */
    val governor: String? = null,
    val declension: GermanDeclension? = null,
    val ending: String = "",
    /** The noun, verb or word this one belongs to or modifies (as written in the line). */
    val target: String? = null,
    val adverbKind: GermanAdverbKind? = null,
    val comparable: Boolean? = null,
    val government: GermanGovernment? = null,
    /** The preposition is fixed by a verb or an adjective (warten auf), so it is not read as a two-way preposition. */
    val fixedWith: String? = null,
    /** The verb goes to the end of this word's clause, and the clause really ends in a finite verb. */
    val verbLast: Boolean = false,
    /** The word opens its clause and a finite verb follows directly (the verb is second). */
    val verbSecond: Boolean = false,
    val contractedWith: String? = null,
    val tentative: Boolean = false,
)

private class PronounReading(
    val person: Int,
    val plural: Boolean,
    val gender: GermanGender?,
    val case: GermanCase,
)

private val NOM = GermanCase.NOMINATIVE
private val ACC = GermanCase.ACCUSATIVE
private val DAT = GermanCase.DATIVE
private val M = GermanGender.MASCULINE
private val F = GermanGender.FEMININE
private val N = GermanGender.NEUTER

private val PERSONAL_PRONOUNS: Map<String, List<PronounReading>> =
    mapOf(
        "ich" to listOf(PronounReading(1, false, null, NOM)),
        "mich" to listOf(PronounReading(1, false, null, ACC)),
        "mir" to listOf(PronounReading(1, false, null, DAT)),
        "du" to listOf(PronounReading(2, false, null, NOM)),
        "dich" to listOf(PronounReading(2, false, null, ACC)),
        "dir" to listOf(PronounReading(2, false, null, DAT)),
        "er" to listOf(PronounReading(3, false, M, NOM)),
        "ihn" to listOf(PronounReading(3, false, M, ACC)),
        "ihm" to listOf(PronounReading(3, false, M, DAT), PronounReading(3, false, N, DAT)),
        "es" to listOf(PronounReading(3, false, N, NOM), PronounReading(3, false, N, ACC)),
        "sie" to
            listOf(
                PronounReading(3, false, F, NOM),
                PronounReading(3, false, F, ACC),
                PronounReading(3, true, null, NOM),
                PronounReading(3, true, null, ACC),
            ),
        "ihr" to listOf(PronounReading(2, true, null, NOM), PronounReading(3, false, F, DAT)),
        "ihnen" to listOf(PronounReading(3, true, null, DAT)),
        "wir" to listOf(PronounReading(1, true, null, NOM)),
        "uns" to listOf(PronounReading(1, true, null, ACC), PronounReading(1, true, null, DAT)),
        "euch" to listOf(PronounReading(2, true, null, ACC), PronounReading(2, true, null, DAT)),
    )

private val INDEFINITE_PRONOUNS = setOf("man", "jemand", "niemand", "etwas", "nichts", "alles", "jemanden", "jemandem")

/** The subject pronoun a reflexive pronoun repeats: mich with ich, uns with wir, and so on. */
private val REFLEXIVE_SUBJECT =
    mapOf("mich" to "ich", "mir" to "ich", "dich" to "du", "dir" to "du", "uns" to "wir", "euch" to "ihr")

private val COPULAS = setOf("sein", "werden", "bleiben", "scheinen")

internal val ADVERB_KINDS: Map<String, GermanAdverbKind> =
    buildMap {
        listOf(
            "heute",
            "gestern",
            "morgen",
            "jetzt",
            "bald",
            "immer",
            "nie",
            "niemals",
            "oft",
            "manchmal",
            "selten",
            "schon",
            "noch",
            "dann",
            "danach",
            "vorher",
            "nachher",
            "später",
            "früher",
            "damals",
            "gleich",
            "sofort",
            "endlich",
            "plötzlich",
            "zuerst",
            "inzwischen",
            "lange",
        ).forEach { put(it, GermanAdverbKind.TIME) }
        listOf(
            "hier",
            "dort",
            "da",
            "drüben",
            "oben",
            "unten",
            "links",
            "rechts",
            "überall",
            "draußen",
            "drinnen",
            "vorne",
            "hinten",
            "dorthin",
            "hierher",
            "irgendwo",
            "nirgends",
        ).forEach { put(it, GermanAdverbKind.PLACE) }
        listOf("gern", "gerne", "zusammen", "genauso", "ebenfalls", "kaum", "fast", "so").forEach { put(it, GermanAdverbKind.MANNER) }
        listOf(
            "leider",
            "vielleicht",
            "hoffentlich",
            "wahrscheinlich",
            "eigentlich",
            "bestimmt",
            "sicherlich",
            "natürlich",
            "übrigens",
            "ja",
            "doch",
            "eben",
            "halt",
            "mal",
            "wohl",
            "nur",
            "auch",
        ).forEach { put(it, GermanAdverbKind.ATTITUDE) }
        listOf("deshalb", "deswegen", "darum", "daher", "trotzdem", "außerdem", "allerdings", "jedoch", "also", "sonst", "dennoch", "zudem")
            .forEach { put(it, GermanAdverbKind.CONNECTIVE) }
        listOf("nicht", "nie", "niemals", "nirgends", "nirgendwo").forEach { put(it, GermanAdverbKind.NEGATION) }
    }

internal val DEGREE_WORDS = setOf("sehr", "ganz", "ziemlich", "besonders", "extrem", "völlig", "total", "äußerst", "recht", "so", "zu")

/** Adverbs that have comparative forms (gern, lieber, am liebsten). Every other listed adverb is marked not comparable. */
private val COMPARABLE_ADVERBS = setOf("gern", "gerne", "oft", "bald", "lange", "sehr", "wohl")

private val POSSESSIVE_STEMS = setOf("mein", "dein", "sein", "ihr", "unser", "euer")

/** The analysis of the word at [start, end) in [sentence], or null when the sentence does not say what it is doing. */
internal fun germanWordRole(
    sentence: GermanSentence,
    start: Int,
    end: Int,
): GermanWordRole? {
    val word = sentence.words.firstOrNull { !it.punctuation && it.start < end && it.end > start } ?: return null
    return determinerRole(sentence, word)
        ?: relativeRole(sentence, word)
        ?: interrogativeRole(sentence, word)
        ?: pronounRole(sentence, word)
        ?: conjunctionRole(sentence, word)
        ?: prepositionRole(sentence, word)
        ?: adjectiveRole(sentence, word)
        ?: adverbRole(sentence, word)
}

private fun finiteVerbIn(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWord? =
    sentence
        .clauseRange(word.index)
        .map {
            sentence.words[it]
        }.firstOrNull { it.index != word.index && it.finite.isNotEmpty() }

private fun lemmaOf(word: GermanWord?): String? =
    word
        ?.finite
        ?.firstOrNull()
        ?.verb
        ?.infinitive

/** True when the clause of [word] ends in a finite verb (the verb-final order a subordinate clause has). */
private fun endsInFiniteVerb(
    sentence: GermanSentence,
    word: GermanWord,
): Boolean =
    sentence
        .clauseWords(word.index)
        .lastOrNull()
        ?.finite
        ?.isNotEmpty() == true

private fun opensClause(
    sentence: GermanSentence,
    word: GermanWord,
): Boolean = sentence.clauseWords(word.index).firstOrNull()?.index == word.index

private fun previous(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWord? = sentence.words.getOrNull(word.index - 1)

private fun determinerRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    val readings = GERMAN_DETERMINERS[word.lower] ?: return null
    val phrase = nounPhraseOf(sentence, word)?.takeIf { it.determiner == word.index } ?: return null
    val features = phrase.features
    val role =
        when {
            word.lower in DEFINITE_FORMS -> GermanRole.DEFINITE_ARTICLE
            word.lower.startsWith("kein") -> GermanRole.NEGATIVE_ARTICLE
            word.lower.startsWith("ein") -> GermanRole.INDEFINITE_ARTICLE
            POSSESSIVE_STEMS.any { word.lower.startsWith(it) } -> GermanRole.POSSESSIVE
            word.lower.startsWith("dies") || word.lower.startsWith("jen") -> GermanRole.DEMONSTRATIVE_DETERMINER
            else -> GermanRole.OTHER_DETERMINER
        }
    return GermanWordRole(
        role = role,
        genders = features.mapNotNullTo(HashSet()) { it.gender },
        plural = features.map { it.plural }.distinct().singleOrNull(),
        cases = phrase.cases,
        declension = readings.first().declension,
        target = sentence.words[phrase.noun].text,
        tentative = !phrase.consistent || phrase.cases.size > 1,
    )
}

private val DEFINITE_FORMS = setOf("der", "die", "das", "den", "dem", "des")

private fun relativeRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    if (word.lower !in GERMAN_RELATIVE_PRONOUNS || word.lower == "was" || word.lower == "wo") return null
    val before = previous(sentence, word) ?: return null
    // The pronoun opens a clause after a comma, perhaps after a preposition (in dem ich wohne).
    val preposition = before.takeIf { it.lower in GERMAN_PREPOSITIONS }
    val comma = if (preposition != null) previous(sentence, before) else before
    if (comma == null || comma.text != ",") return null
    val next = sentence.words.getOrNull(word.index + 1)
    if (next != null && next.nouns.isNotEmpty() && next.lower !in GERMAN_SUBJECT_PRONOUNS) return null
    if (!endsInFiniteVerb(sentence, word)) return null
    val antecedent =
        sentence.words
            .take(comma.index)
            .lastOrNull { it.nouns.isNotEmpty() && it.text.first().isUpperCase() }
    val forms = GERMAN_DETERMINERS[word.lower].orEmpty().flatMap { it.features }
    val matching =
        antecedent
            ?.let { noun ->
                val plural = noun.nouns.any { it.plural }
                val genders = noun.nouns.flatMap { it.noun.genders }.toSet()
                forms.filter { if (it.plural) plural else !plural && it.gender in genders }
            }.orEmpty()
    val usable = matching.ifEmpty { forms }
    return GermanWordRole(
        role = GermanRole.RELATIVE_PRONOUN,
        genders = usable.mapNotNullTo(HashSet()) { it.gender },
        plural = usable.map { it.plural }.distinct().singleOrNull(),
        cases = usable.mapTo(HashSet()) { it.case },
        governor = antecedent?.text,
        target = lemmaOf(sentence.clauseWords(word.index).lastOrNull()),
        government = null,
        fixedWith = preposition?.lower,
        verbLast = true,
        tentative = matching.isEmpty() || usable.map { it.case }.distinct().size > 1,
    )
}

private fun interrogativeRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    if (word.lower !in GERMAN_QUESTION_WORDS || word.nouns.isNotEmpty() && !opensClause(sentence, word)) return null
    if (!opensClause(sentence, word) && previous(sentence, word)?.text != ",") return null
    val indirect = previous(sentence, word)?.text == ","
    return GermanWordRole(
        role = GermanRole.INTERROGATIVE,
        verbLast = indirect && endsInFiniteVerb(sentence, word),
        verbSecond =
            !indirect && sentence.words
                .getOrNull(sentence.nextInClause(word.index))
                ?.finite
                ?.isNotEmpty() == true,
    )
}

private fun pronounRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    if (word.lower in INDEFINITE_PRONOUNS) return GermanWordRole(GermanRole.INDEFINITE_PRONOUN, person = 3, plural = false)
    val readings = PERSONAL_PRONOUNS[word.lower]
    val clause = sentence.clauseWords(word.index)
    val verbWord = finiteVerbIn(sentence, word)
    val verbLemma = lemmaOf(verbWord)
    if (word.lower == "sich" || readings != null && isReflexive(word, clause)) {
        return reflexiveRole(word, readings, verbLemma)
    }
    if (readings == null) return demonstrativeRole(sentence, word)
    val before = previous(sentence, word)
    val preposition = before?.takeIf { it.lower in GERMAN_PREPOSITIONS }
    val government = preposition?.let { GERMAN_PREPOSITIONS[it.lower] }
    val usable = narrowPronoun(readings, word, government, verbLemma, verbWord)
    val persons = usable.map { it.person }.distinct()
    val numbers = usable.map { it.plural }.distinct()
    val cases = usable.map { it.case }.toSet()
    val function =
        when {
            preposition != null && government != null -> GermanSyntaxRole.AFTER_PREPOSITION
            cases == setOf(NOM) -> GermanSyntaxRole.SUBJECT
            cases == setOf(ACC) -> GermanSyntaxRole.DIRECT_OBJECT
            cases == setOf(DAT) -> GermanSyntaxRole.DATIVE_OBJECT
            else -> null
        }
    return GermanWordRole(
        role = GermanRole.PERSONAL_PRONOUN,
        person = persons.singleOrNull() ?: 0,
        plural = numbers.singleOrNull(),
        genders = usable.mapNotNullTo(HashSet()) { it.gender },
        cases = cases,
        function = function,
        governor = if (function == GermanSyntaxRole.AFTER_PREPOSITION) preposition?.lower else verbLemma,
        tentative = cases.size > 1 || persons.size > 1 || numbers.size > 1,
    )
}

/** The readings of a personal pronoun that the preposition before it, a dative verb or the verb's number leave possible. */
private fun narrowPronoun(
    readings: List<PronounReading>,
    word: GermanWord,
    government: GermanGovernment?,
    verbLemma: String?,
    verbWord: GermanWord?,
): List<PronounReading> {
    var usable = readings
    if (government != null && government != GermanGovernment.TWO_WAY) {
        val required =
            when (government) {
                GermanGovernment.DATIVE -> DAT
                GermanGovernment.ACCUSATIVE -> ACC
                else -> null
            }
        usable = usable.filter { it.case == required }.ifEmpty { usable }
    } else if (verbLemma in DATIVE_VERBS && usable.any { it.case == DAT } && usable.all { it.case != NOM }) {
        usable = usable.filter { it.case == DAT }
    }
    if (word.lower == "sie" || word.lower == "es") usable = narrowByVerbNumber(usable, verbWord)
    return usable
}

/** mich, dich, uns, euch, mir, dir repeat a subject of the same person; sich always repeats the subject. */
private fun isReflexive(
    word: GermanWord,
    clause: List<GermanWord>,
): Boolean {
    val subject = REFLEXIVE_SUBJECT[word.lower] ?: return false
    return clause.any { it.lower == subject }
}

private fun reflexiveRole(
    word: GermanWord,
    readings: List<PronounReading>?,
    verbLemma: String?,
): GermanWordRole {
    val cases = readings?.map { it.case }?.toSet() ?: emptySet()
    return GermanWordRole(
        role = GermanRole.REFLEXIVE_PRONOUN,
        person = readings?.map { it.person }?.distinct()?.singleOrNull() ?: 0,
        plural = readings?.map { it.plural }?.distinct()?.singleOrNull(),
        cases = cases,
        governor = verbLemma,
        tentative = word.lower == "sich" && verbLemma == null || cases.size > 1,
    )
}

private fun narrowByVerbNumber(
    readings: List<PronounReading>,
    verb: GermanWord?,
): List<PronounReading> {
    val numbers =
        verb
            ?.finite
            ?.map { it.cell.plural }
            ?.distinct()
            ?.singleOrNull() ?: return readings
    return readings.filter { it.plural == numbers || it.gender == N }.ifEmpty { readings }
}

private fun demonstrativeRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    if (word.lower !in DEFINITE_FORMS && !word.lower.startsWith("dies")) return null
    // No noun follows, so the word stands on its own (Das ist gut), but a single reading is not claimed.
    val next = sentence.words.getOrNull(word.index + 1)
    if (next != null && (next.nouns.isNotEmpty() || next.adjectives.any { it.ending.isNotEmpty() })) return null
    val features = GERMAN_DETERMINERS[word.lower].orEmpty().flatMap { it.features }
    return GermanWordRole(
        role = GermanRole.DEMONSTRATIVE_PRONOUN,
        cases = features.mapTo(HashSet()) { it.case },
        genders = features.mapNotNullTo(HashSet()) { it.gender },
        tentative = true,
    )
}

private fun conjunctionRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    if (word.lower in GERMAN_COORDINATORS && word.nouns.isEmpty()) return GermanWordRole(GermanRole.COORDINATOR)
    if (word.lower !in GERMAN_SUBORDINATORS) return null
    // da, als and wie are also adverbs and comparisons; only a clause that really ends in its verb is called subordinate.
    if (!endsInFiniteVerb(sentence, word)) return null
    return GermanWordRole(GermanRole.SUBORDINATOR, verbLast = true, target = lemmaOf(sentence.clauseWords(word.index).lastOrNull()))
}

private fun prepositionRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    val contraction = GERMAN_CONTRACTIONS[word.lower]
    val government = GERMAN_PREPOSITIONS[word.lower] ?: contraction?.let { GERMAN_PREPOSITIONS[it.first] } ?: return null
    val next = sentence.words.getOrNull(word.index + 1)
    val governedPhrase = governedPhraseAfter(sentence, word)
    val pronoun = next?.takeIf { it.lower in GERMAN_PRONOUN_CASES }
    // Without a phrase or pronoun after it the word is not read as a preposition (zu before an infinitive, um zu, bis).
    if (governedPhrase == null && pronoun == null && contraction == null) return null
    val verbEntry =
        lemmaOf(finiteVerbIn(sentence, word))?.let { lemma ->
            VERB_PREPOSITIONS.firstOrNull { it.first == lemma && it.second == word.lower }
        }
    val adjectiveEntry =
        previous(sentence, word)
            ?.takeIf { it.adjectives.isNotEmpty() }
            ?.let { adj -> ADJECTIVE_PREPOSITIONS.firstOrNull { it.first == adj.lower && it.second == word.lower } }
    val fixedCase = verbEntry?.third ?: adjectiveEntry?.third
    val cases = prepositionCases(fixedCase, government, governedPhrase)
    return GermanWordRole(
        role = GermanRole.PREPOSITION,
        cases = cases,
        government = if (fixedCase != null) null else government,
        fixedWith = verbEntry?.first ?: adjectiveEntry?.first,
        target = governedPhrase?.let { sentence.words[it.noun].text } ?: next?.takeIf { pronoun != null }?.text,
        contractedWith = contraction?.let { it.second.firstOrNull()?.let { feature -> articleFor(feature) } },
        tentative = cases.size != 1,
    )
}

/** The noun phrase right after a preposition; a German noun is capitalised, so a lowercase noun reading (zu sehen) does not count. */
private fun governedPhraseAfter(
    sentence: GermanSentence,
    word: GermanWord,
): GermanNounPhrase? {
    val next = sentence.words.getOrNull(word.index + 1) ?: return null
    val phrase = nounPhraseOf(sentence, next) ?: return null
    return phrase.takeIf {
        it.first == word.index + 1 &&
            sentence.words[it.noun]
                .text
                .first()
                .isUpperCase()
    }
}

private fun prepositionCases(
    fixedCase: GermanCase?,
    government: GermanGovernment,
    governedPhrase: GermanNounPhrase?,
): Set<GermanCase> =
    when {
        fixedCase != null -> setOf(fixedCase)
        government == GermanGovernment.ACCUSATIVE -> setOf(ACC)
        government == GermanGovernment.DATIVE -> setOf(DAT)
        government == GermanGovernment.GENITIVE -> setOf(GermanCase.GENITIVE)
        else ->
            governedPhrase
                ?.cases
                ?.filter { it == ACC || it == DAT }
                ?.toSet()
                .orEmpty()
    }

/** The article a contraction like im or zum stands for (in dem), named by its features. */
private fun articleFor(feature: GermanFeature): String? =
    when (feature.case) {
        DAT -> "dem"
        ACC -> "das"
        else -> null
    }

private fun adjectiveRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    val reading = word.adjectives.firstOrNull() ?: return null
    if (word.lower in ADVERB_KINDS || word.lower in DEGREE_WORDS || word.nouns.isNotEmpty() && word.text.first().isUpperCase()) return null
    val phrase = nounPhraseOf(sentence, word)
    if (phrase != null && word.index in phrase.adjectives) {
        val attributive = word.adjectives.firstOrNull { it.ending.isNotEmpty() } ?: reading
        return GermanWordRole(
            role = GermanRole.ADJECTIVE_ATTRIBUTIVE,
            genders = phrase.features.mapNotNullTo(HashSet()) { it.gender },
            plural =
                phrase.features
                    .map { it.plural }
                    .distinct()
                    .singleOrNull(),
            cases = phrase.cases,
            declension = phrase.declension,
            ending = attributive.ending,
            target = sentence.words[phrase.noun].text,
            tentative = !phrase.consistent || phrase.cases.size > 1,
        )
    }
    if (reading.ending.isNotEmpty() && word.adjectives.none { it.ending.isEmpty() }) return null
    val clause = sentence.clauseRange(word.index).map { sentence.words[it] }
    val copula = clause.firstOrNull { w -> w.finite.any { it.verb.infinitive in COPULAS } }
    val verb = clause.firstOrNull { it.finite.isNotEmpty() }
    return when {
        copula != null -> GermanWordRole(GermanRole.ADJECTIVE_PREDICATIVE, target = copula.text)
        verb != null -> GermanWordRole(GermanRole.ADJECTIVE_ADVERBIAL, target = verb.text, comparable = true)
        else -> null
    }
}

private fun adverbRole(
    sentence: GermanSentence,
    word: GermanWord,
): GermanWordRole? {
    if (word.nouns.isNotEmpty() && word.text.first().isUpperCase() && !sentence.isSentenceStart(word.index)) return null
    val verb = finiteVerbIn(sentence, word)
    val next = sentence.words.getOrNull(sentence.nextInClause(word.index))
    // The tagger marks the infinitive particle (PART); an infinitive must follow, else zu is a preposition or a degree word.
    if (word.lower == "zu" && word.isTag("PART") && next != null && next.verbs.any { it.verb.infinitive == next.lower }) {
        return GermanWordRole(GermanRole.INFINITIVE_ZU, target = next.text)
    }
    // A form like schnell can also be a verb reading (schnellen); the tagger's ADJ or ADV tag settles that it is the adjective here.
    val modifiable =
        next != null && next.verbs.none { it.verb.infinitive == next.lower } &&
            (next.adjectives.isNotEmpty() && (next.finite.isEmpty() || next.isTag("ADJ", "ADV")) || next.lower in ADVERB_KINDS)
    if (word.lower in DEGREE_WORDS && next != null && modifiable) {
        return GermanWordRole(GermanRole.DEGREE_WORD, target = next.text, comparable = word.lower in COMPARABLE_ADVERBS)
    }
    val kind = ADVERB_KINDS[word.lower] ?: return if (word.lower in DEGREE_WORDS && word.lower != "zu") degreeOfVerb(word, verb) else null
    return GermanWordRole(
        role = GermanRole.ADVERB,
        adverbKind = kind,
        target = if (kind == GermanAdverbKind.ATTITUDE || kind == GermanAdverbKind.CONNECTIVE) null else verb?.text,
        comparable = word.lower in COMPARABLE_ADVERBS,
        verbSecond = opensClause(sentence, word) && next?.finite?.isNotEmpty() == true,
    )
}

private fun degreeOfVerb(
    word: GermanWord,
    verb: GermanWord?,
): GermanWordRole? = verb?.let { GermanWordRole(GermanRole.DEGREE_WORD, target = it.text, comparable = word.lower in COMPARABLE_ADVERBS) }
