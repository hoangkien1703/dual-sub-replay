package com.kienhoang.dualsubreplay.data

import java.io.InputStream

internal enum class GermanCase { NOMINATIVE, GENITIVE, DATIVE, ACCUSATIVE }

internal enum class GermanGender { MASCULINE, FEMININE, NEUTER }

/** A noun of `de-words.txt` with the forms the rules cannot derive: genitive singular and plural. */
internal class GermanNoun(
    val lemma: String,
    val genders: List<GermanGender>,
    val genitives: List<String>,
    val plurals: List<String>,
    val weak: Boolean,
) {
    /** Dative plural: the plural with -n, unless it already ends in -n or -s. */
    fun dativePlural(plural: String): String = if (plural.endsWith("n") || plural.endsWith("s")) plural else plural + "n"
}

/** One analysis of a noun form: its cases (more than one when the form is shared) and number. */
internal data class GermanNounReading(
    val noun: GermanNoun,
    val cases: Set<GermanCase>,
    val plural: Boolean,
)

internal enum class GermanDegree { POSITIVE, COMPARATIVE, SUPERLATIVE }

/** An adjective with its comparative and superlative stem (e.g. alt, älter, ält-est). */
internal class GermanAdjective(
    val lemma: String,
    val comparative: String,
    val superlative: String,
)

/** One analysis of an adjective form: [ending] is what follows the degree stem ("" when undeclined). */
internal data class GermanAdjectiveReading(
    val adjective: GermanAdjective,
    val degree: GermanDegree,
    val ending: String,
)

/** The German verbs, nouns and adjectives of `de-verbs.txt` and `de-words.txt`, indexed for analysis. */
internal class GermanLexicon(
    verbs: List<GermanVerb>,
    nouns: List<GermanNoun>,
    adjectives: List<GermanAdjective>,
) {
    val verbs: Map<String, GermanVerb> = verbs.associateBy { it.infinitive }
    private val byBase: Map<String, List<GermanVerb>> = verbs.groupBy { it.base }
    private val byStem: Map<String, List<GermanVerb>>
    private val byParticiple: Map<String, List<GermanVerb>> =
        verbs
            .flatMap { v ->
                v.participles.map { it to v }
            }.groupBy({ it.first }, { it.second })
    private val prefixes: List<String> =
        verbs
            .map { it.prefix }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
    val nouns: Map<String, GermanNoun> = nouns.associateBy { it.lemma }
    private val nounForms: Map<String, List<GermanNounReading>>
    private val adjectives: Map<String, GermanAdjective> = adjectives.associateBy { it.lemma }
    private val comparatives: Map<String, GermanAdjective> = adjectives.associateBy { it.comparative }
    private val superlatives: Map<String, GermanAdjective> = adjectives.associateBy { it.superlative }

    init {
        val stems = HashMap<String, MutableList<GermanVerb>>()
        for (verb in verbs) for (stem in verb.irregularStems) stems.getOrPut(stem) { ArrayList(1) }.add(verb)
        byStem = stems
        val forms = HashMap<String, MutableList<GermanNounReading>>()
        val allCases = GermanCase.entries.toSet()
        val nominativeOnly = setOf(GermanCase.NOMINATIVE)
        val genitiveOnly = setOf(GermanCase.GENITIVE)
        val dativeOnly = setOf(GermanCase.DATIVE)
        val notGenitive = setOf(GermanCase.NOMINATIVE, GermanCase.DATIVE, GermanCase.ACCUSATIVE)
        val notDative = setOf(GermanCase.NOMINATIVE, GermanCase.GENITIVE, GermanCase.ACCUSATIVE)
        val weakOblique = setOf(GermanCase.GENITIVE, GermanCase.DATIVE, GermanCase.ACCUSATIVE)

        fun put(
            form: String,
            reading: GermanNounReading,
        ) {
            forms.getOrPut(form) { ArrayList(2) }.add(reading)
        }
        for (noun in nouns) {
            // Frau lists the genitives Frau and Frauen: the lemma itself is a genitive, so it has all four cases.
            val same = noun.lemma in noun.genitives
            val singular =
                when {
                    noun.weak -> nominativeOnly
                    same -> allCases
                    else -> notGenitive
                }
            put(noun.lemma, GermanNounReading(noun, singular, false))
            for (genitive in noun.genitives.filter { it != noun.lemma }) {
                val cases = if (noun.weak) weakOblique else genitiveOnly
                put(genitive, GermanNounReading(noun, cases, false))
            }
            // The old dative -e (dem Hause), still used in set phrases, for masculine and neuter nouns.
            val dativeE = noun.lemma + "e"
            if (!noun.weak && GermanGender.FEMININE !in noun.genders && !noun.lemma.endsWith("e")) {
                put(dativeE, GermanNounReading(noun, dativeOnly, false))
            }
            // A plural equal to the lemma next to a real plural is a collective use (zehn Mann), not a reading to offer.
            val realPlurals = noun.plurals.filter { it != noun.lemma }.ifEmpty { noun.plurals }
            for (plural in realPlurals) {
                val dative = noun.dativePlural(plural)
                val shared = if (plural == dative) allCases else notDative
                put(plural, GermanNounReading(noun, shared, true))
                if (dative != plural) put(dative, GermanNounReading(noun, dativeOnly, true))
            }
        }
        nounForms = forms
    }

    /** Every reading of the verb form [surface] (any capitalisation), likeliest verbs first by lexicon order. */
    fun verbReadings(surface: String): List<GermanVerbReading> {
        val word = surface.lowercase()
        val candidates = LinkedHashSet<GermanVerb>()
        collect(word, candidates)
        for (prefix in prefixes) {
            if (word.length > prefix.length + 2 && word.startsWith(prefix)) collect(word.removePrefix(prefix), candidates)
        }
        byParticiple[word]?.let(candidates::addAll)
        val out = ArrayList<GermanVerbReading>()
        for (verb in candidates) {
            for ((cell, forms) in verb.forms) {
                val attached = verb.attached.getValue(cell)
                if (word in forms) out += GermanVerbReading(verb, cell, attached = false)
                if (verb.separable && cell.person > 0 && word in attached) out += GermanVerbReading(verb, cell, attached = true)
            }
        }
        return out.distinct()
    }

    private fun collect(
        word: String,
        into: MutableSet<GermanVerb>,
    ) {
        byBase[word]?.let(into::addAll)
        // The bare stem is the imperative of a regular verb (geh, komm, wander).
        byBase[word + "en"]?.let(into::addAll)
        byBase[word + "n"]?.let(into::addAll)
        byStem[word]?.let(into::addAll)
        for (ending in VERB_ENDINGS) {
            if (!word.endsWith(ending) || word.length <= ending.length) continue
            val stem = word.dropLast(ending.length)
            byBase[stem]?.let(into::addAll) // a present participle is the infinitive plus -d (liegend)
            byBase[stem + "en"]?.let(into::addAll)
            byBase[stem + "n"]?.let(into::addAll)
            if (stem.endsWith("l") || stem.endsWith("r")) byBase[stem.dropLast(1) + "e" + stem.last() + "n"]?.let(into::addAll)
            byStem[stem]?.let(into::addAll)
        }
    }

    /** The noun readings of [surface] as it is written (nouns are capitalised); empty when it is not a listed noun form. */
    fun nounReadings(surface: String): List<GermanNounReading> = nounForms[surface].orEmpty()

    fun adjectiveReadings(surface: String): List<GermanAdjectiveReading> {
        val word = surface.lowercase()
        val out = ArrayList<GermanAdjectiveReading>()
        for (ending in ADJECTIVE_ENDINGS) {
            if (!word.endsWith(ending) || word.length - ending.length < 2) continue
            val stem = word.dropLast(ending.length)
            val positives = LinkedHashSet<String>()
            positives += stem
            positives += stem + "e"
            if (stem.length > 2) positives += stem.dropLast(1) + "e" + stem.last() // teur-er -> teuer, dunkl-e -> dunkel
            for (candidate in positives) adjectives[candidate]?.let { out += GermanAdjectiveReading(it, GermanDegree.POSITIVE, ending) }
            comparatives[stem]?.let { out += GermanAdjectiveReading(it, GermanDegree.COMPARATIVE, ending) }
            superlatives[stem]?.let { out += GermanAdjectiveReading(it, GermanDegree.SUPERLATIVE, ending) }
        }
        return out.distinct()
    }

    fun isAdjectiveLemma(word: String): Boolean = word in adjectives

    private companion object {
        val VERB_ENDINGS = listOf("e", "est", "st", "t", "et", "en", "n", "te", "test", "ten", "tet", "ete", "etest", "eten", "etet", "d")
        val ADJECTIVE_ENDINGS = listOf("", "e", "en", "em", "er", "es")
    }
}

/** Parses `de-verbs.txt` and `de-words.txt` (comment lines start with #). */
internal fun loadGermanLexicon(
    verbs: InputStream,
    words: InputStream,
): GermanLexicon {
    val verbList = ArrayList<GermanVerb>()
    verbs.bufferedReader().useLines { lines ->
        lines.filter { !it.startsWith("#") && it.isNotBlank() }.forEach { parseGermanVerb(it)?.let(verbList::add) }
    }
    val nounList = ArrayList<GermanNoun>()
    val adjectiveList = ArrayList<GermanAdjective>()
    words.bufferedReader().useLines { lines ->
        lines.filter { !it.startsWith("#") && it.isNotBlank() }.forEach { line ->
            val parts = line.split('|')
            when {
                parts[0] == "N" && parts.size >= 6 -> parseGermanNoun(parts)?.let(nounList::add)
                parts[0] == "A" && parts.size >= 4 -> adjectiveList += parseGermanAdjective(parts)
            }
        }
    }
    return GermanLexicon(verbList, nounList, adjectiveList)
}

private fun expand(
    lemma: String,
    field: String,
): String? =
    when {
        field == "-" -> null
        field == "=" -> lemma
        field.startsWith("+") -> lemma + field.drop(1)
        else -> field
    }

private fun parseGermanNoun(parts: List<String>): GermanNoun? {
    val genders =
        parts[2].mapNotNull {
            when (it) {
                'm' -> GermanGender.MASCULINE
                'f' -> GermanGender.FEMININE
                'n' -> GermanGender.NEUTER
                else -> null
            }
        }
    if (genders.isEmpty()) return null
    val lemma = parts[1]
    val genitives = parts[3].split('/').mapNotNull { expand(lemma, it) }.ifEmpty { listOf(lemma) }
    val plurals = parts[4].split('/').mapNotNull { expand(lemma, it) }
    return GermanNoun(lemma, genders, genitives, plurals, 'w' in parts[5])
}

private fun parseGermanAdjective(parts: List<String>): GermanAdjective {
    val lemma = parts[1]
    val comparative = parts[2].ifEmpty { if (lemma.endsWith("e")) lemma + "r" else lemma + "er" }
    val superlative =
        parts[3].removeSuffix("en").ifEmpty {
            lemma + if (lemma.endsWith("d") || lemma.endsWith("t") || lemma.last() in "sßxz" || lemma.endsWith("sch")) "est" else "st"
        }
    return GermanAdjective(lemma, comparative, superlative)
}
