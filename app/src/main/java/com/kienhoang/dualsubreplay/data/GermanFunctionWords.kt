package com.kienhoang.dualsubreplay.data

/** A case, gender and number a noun phrase can have; [gender] is null in the plural, which has none. */
internal data class GermanFeature(
    val case: GermanCase,
    val gender: GermanGender?,
    val plural: Boolean,
)

/** What kind of adjective endings follow: after der-words (weak), ein-words (mixed) or nothing (strong). */
internal enum class GermanDeclension { WEAK, MIXED, STRONG }

/** A determiner reading: the features it allows and the declension it triggers on adjectives after it. */
internal class GermanDeterminer(
    val features: Set<GermanFeature>,
    val declension: GermanDeclension,
)

private val M = GermanGender.MASCULINE
private val F = GermanGender.FEMININE
private val N = GermanGender.NEUTER
private val NOM = GermanCase.NOMINATIVE
private val GEN = GermanCase.GENITIVE
private val DAT = GermanCase.DATIVE
private val ACC = GermanCase.ACCUSATIVE

private fun features(vararg items: Triple<GermanCase, GermanGender?, Boolean>) =
    items
        .map {
            GermanFeature(it.first, it.second, it.third)
        }.toSet()

private fun sg(
    case: GermanCase,
    vararg genders: GermanGender,
) = genders.map { GermanFeature(case, it, false) }

private fun pl(vararg cases: GermanCase) = cases.map { GermanFeature(it, null, true) }

/** The features of the endings -er, -e, -es, -en, -em on a der-word (dieser, jeder, welcher, ...). */
private val DER_WORD_ENDINGS: Map<String, Set<GermanFeature>> =
    mapOf(
        "er" to (sg(NOM, M) + sg(DAT, F) + sg(GEN, F) + pl(GEN)).toSet(),
        "e" to (sg(NOM, F) + sg(ACC, F) + pl(NOM, ACC)).toSet(),
        "es" to (sg(NOM, N) + sg(ACC, N) + sg(GEN, M, N)).toSet(),
        "en" to (sg(ACC, M) + pl(DAT)).toSet(),
        "em" to sg(DAT, M, N).toSet(),
    )

/** The same endings on an ein-word; [plural] is true for kein-, mein- and the other words that have a plural. */
private fun einWordEndings(plural: Boolean): Map<String, Set<GermanFeature>> =
    mapOf(
        "" to (sg(NOM, M, N) + sg(ACC, N)).toSet(),
        "e" to (sg(NOM, F) + sg(ACC, F) + (if (plural) pl(NOM, ACC) else emptyList())).toSet(),
        "en" to (sg(ACC, M) + (if (plural) pl(DAT) else emptyList())).toSet(),
        "em" to sg(DAT, M, N).toSet(),
        "er" to (sg(DAT, F) + sg(GEN, F) + (if (plural) pl(GEN) else emptyList())).toSet(),
        "es" to sg(GEN, M, N).toSet(),
    )

private val DER_STEMS = listOf("dies", "jen", "jed", "welch", "manch", "solch")
private val EIN_STEMS = listOf("kein", "mein", "dein", "sein", "ihr", "unser", "euer", "ein")

private val DEFINITE: Map<String, Set<GermanFeature>> =
    mapOf(
        "der" to (sg(NOM, M) + sg(DAT, F) + sg(GEN, F) + pl(GEN)).toSet(),
        "die" to (sg(NOM, F) + sg(ACC, F) + pl(NOM, ACC)).toSet(),
        "das" to (sg(NOM, N) + sg(ACC, N)).toSet(),
        "den" to (sg(ACC, M) + pl(DAT)).toSet(),
        "dem" to sg(DAT, M, N).toSet(),
        "des" to sg(GEN, M, N).toSet(),
    )

/** Determiners by lowercase form, each with the readings it can have (`der` has five features but one determiner). */
internal val GERMAN_DETERMINERS: Map<String, List<GermanDeterminer>> =
    buildMap<String, MutableList<GermanDeterminer>> {
        fun put(
            form: String,
            determiner: GermanDeterminer,
        ) {
            getOrPut(form) { ArrayList(1) }.add(determiner)
        }
        DEFINITE.forEach { (form, f) -> put(form, GermanDeterminer(f, GermanDeclension.WEAK)) }
        for (stem in DER_STEMS) {
            for ((ending, f) in DER_WORD_ENDINGS) put(stem + ending, GermanDeterminer(f, GermanDeclension.WEAK))
        }
        for (stem in EIN_STEMS) {
            val plural = stem != "ein"
            for ((ending, f) in einWordEndings(plural)) {
                // unser/euer drop an e before an ending: unsre, eure
                put(stem + ending, GermanDeterminer(f, GermanDeclension.MIXED))
                if (stem == "euer" && ending.isNotEmpty()) put("eur$ending", GermanDeterminer(f, GermanDeclension.MIXED))
                if (stem == "unser" && ending.isNotEmpty()) put("unsr$ending", GermanDeterminer(f, GermanDeclension.MIXED))
            }
        }
    }

/** Which adjective endings are possible for each declension, and the features each ending marks. */
internal val GERMAN_ADJECTIVE_ENDINGS: Map<GermanDeclension, Map<String, Set<GermanFeature>>> =
    mapOf(
        GermanDeclension.WEAK to
            mapOf(
                "e" to (sg(NOM, M, F, N) + sg(ACC, F, N)).toSet(),
                "en" to (sg(ACC, M) + sg(GEN, M, F, N) + sg(DAT, M, F, N) + pl(NOM, GEN, DAT, ACC)).toSet(),
            ),
        GermanDeclension.MIXED to
            mapOf(
                "er" to sg(NOM, M).toSet(),
                "e" to (sg(NOM, F) + sg(ACC, F)).toSet(),
                "es" to (sg(NOM, N) + sg(ACC, N)).toSet(),
                "en" to (sg(ACC, M) + sg(GEN, M, F, N) + sg(DAT, M, F, N) + pl(NOM, GEN, DAT, ACC)).toSet(),
            ),
        GermanDeclension.STRONG to
            mapOf(
                "er" to (sg(NOM, M) + sg(GEN, F) + sg(DAT, F) + pl(GEN)).toSet(),
                "e" to (sg(NOM, F) + sg(ACC, F) + pl(NOM, ACC)).toSet(),
                "es" to (sg(NOM, N) + sg(ACC, N)).toSet(),
                "en" to (sg(ACC, M) + sg(GEN, M, N) + pl(DAT)).toSet(),
                "em" to sg(DAT, M, N).toSet(),
            ),
    )

/** What a preposition governs; a Wechselpräposition takes the accusative for a direction and the dative for a place. */
internal enum class GermanGovernment { ACCUSATIVE, DATIVE, GENITIVE, TWO_WAY }

internal val GERMAN_PREPOSITIONS: Map<String, GermanGovernment> =
    buildMap {
        listOf("durch", "für", "gegen", "ohne", "um", "bis", "entlang").forEach { put(it, GermanGovernment.ACCUSATIVE) }
        listOf("aus", "bei", "mit", "nach", "seit", "von", "zu", "gegenüber", "ab", "außer").forEach { put(it, GermanGovernment.DATIVE) }
        listOf("wegen", "trotz", "während", "statt", "anstatt", "innerhalb", "außerhalb", "aufgrund", "anlässlich").forEach {
            put(it, GermanGovernment.GENITIVE)
        }
        listOf("an", "auf", "hinter", "in", "neben", "über", "unter", "vor", "zwischen").forEach { put(it, GermanGovernment.TWO_WAY) }
    }

/** Contractions of a preposition and an article: the government and the article's features (im = in dem). */
internal val GERMAN_CONTRACTIONS: Map<String, Pair<String, Set<GermanFeature>>> =
    mapOf(
        "im" to ("in" to sg(DAT, M, N).toSet()),
        "am" to ("an" to sg(DAT, M, N).toSet()),
        "vom" to ("von" to sg(DAT, M, N).toSet()),
        "beim" to ("bei" to sg(DAT, M, N).toSet()),
        "zum" to ("zu" to sg(DAT, M, N).toSet()),
        "zur" to ("zu" to sg(DAT, F).toSet()),
        "ins" to ("in" to sg(ACC, N).toSet()),
        "ans" to ("an" to sg(ACC, N).toSet()),
        "aufs" to ("auf" to sg(ACC, N).toSet()),
        "ums" to ("um" to sg(ACC, N).toSet()),
        "fürs" to ("für" to sg(ACC, N).toSet()),
        "durchs" to ("durch" to sg(ACC, N).toSet()),
    )

internal val GERMAN_REFLEXIVE_PRONOUNS = setOf("mich", "dich", "sich", "uns", "euch", "mir", "dir")

/** Subordinating conjunctions and what they introduce (the tagger marks them SCONJ; this names the meaning). */
internal val GERMAN_SUBORDINATORS =
    setOf(
        "dass",
        "weil",
        "da",
        "wenn",
        "als",
        "ob",
        "obwohl",
        "obgleich",
        "damit",
        "bevor",
        "nachdem",
        "während",
        "falls",
        "sobald",
        "solange",
        "seitdem",
        "sodass",
        "indem",
        "bis",
        "seit",
        "ehe",
    )

internal val GERMAN_QUESTION_WORDS =
    setOf(
        "wer",
        "was",
        "wo",
        "wann",
        "warum",
        "wie",
        "wen",
        "wem",
        "wessen",
        "wohin",
        "woher",
        "welche",
        "welcher",
        "welches",
        "welchen",
        "welchem",
        "weshalb",
        "wieso",
        "wieviel",
    )

internal val GERMAN_RELATIVE_PRONOUNS =
    setOf("der", "die", "das", "den", "dem", "dessen", "deren", "denen", "welche", "welcher", "welches", "welchen", "welchem", "was", "wo")

internal val GERMAN_SUBJECT_PRONOUNS = setOf("ich", "du", "er", "sie", "es", "wir", "ihr", "man")

internal val GERMAN_THIRD_PERSON_SUBJECTS = setOf("er", "sie", "es", "man")

/** Degree words that may stand between a determiner and an adjective (ein ganz neues Kleid); they are not adjectives. */
internal val GERMAN_INTENSIFIERS =
    setOf(
        "sehr",
        "ganz",
        "so",
        "zu",
        "ziemlich",
        "besonders",
        "äußerst",
        "recht",
        "relativ",
        "wirklich",
        "total",
        "völlig",
        "richtig",
        "echt",
        "extrem",
        "unglaublich",
        "überaus",
        "höchst",
        "reichlich",
        "ausgesprochen",
        "fast",
        "etwas",
        "ungemein",
    )

internal val GERMAN_COORDINATORS = setOf("und", "aber", "oder", "denn", "sondern")

/**
 * Every word the closed classes (prepositions, articles and other determiners, pronouns, conjunctions,
 * question words) are known to contain. The tagger gives an unknown word one of these classes as a guess, so a
 * word with such a tag that is not in this set is not trusted to be a function word.
 */
internal val GERMAN_FUNCTION_WORDS: Set<String> =
    buildSet {
        addAll(GERMAN_PREPOSITIONS.keys)
        addAll(GERMAN_CONTRACTIONS.keys)
        addAll(GERMAN_DETERMINERS.keys)
        addAll(GERMAN_SUBORDINATORS)
        addAll(GERMAN_COORDINATORS)
        addAll(GERMAN_QUESTION_WORDS)
        addAll(GERMAN_RELATIVE_PRONOUNS)
        addAll(GERMAN_SUBJECT_PRONOUNS)
        addAll(GERMAN_REFLEXIVE_PRONOUNS)
        addAll(listOf("zu", "nicht", "nur", "auch", "noch", "schon", "sehr", "so", "wie", "als", "dann", "denn", "doch", "ja", "nein"))
    }
