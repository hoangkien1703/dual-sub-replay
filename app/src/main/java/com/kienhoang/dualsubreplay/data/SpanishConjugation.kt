package com.kienhoang.dualsubreplay.data

/**
 * A Spanish verb form's tense or mood as learners know it. [code] is what grammar patterns read
 * ([Morpheme.conjugationForm], `%SUBJ` matches both subjunctives).
 */
internal enum class SpanishTense(
    val code: String,
) {
    PRESENT("PRES"),
    PRETERITE("PRET"),
    IMPERFECT("IMPF"),
    FUTURE("FUT"),
    CONDITIONAL("COND"),
    PRESENT_SUBJUNCTIVE("SUBJ_PRES"),
    IMPERFECT_SUBJUNCTIVE("SUBJ_IMPF"),
    IMPERATIVE("CMD"),
    INFINITIVE("INF"),
    GERUND("GER"),
    PARTICIPLE("PART"),
}

/** Who a Spanish verb form is for; [pronoun] is shown as it is, in Spanish. */
internal enum class SpanishPerson(
    val code: String,
    val pronoun: String,
) {
    YO("1S", "yo"),
    TU("2S", "tú"),
    EL("3S", "él / ella / usted"),
    NOSOTROS("1P", "nosotros"),
    VOSOTROS("2P", "vosotros"),
    ELLOS("3P", "ellos / ellas / ustedes"),
    VOS("VOS", "vos"),
    NONE("*", ""),
}

/** One way to read a Spanish verb form: its dictionary form, tense and person. */
internal data class SpanishVerbReading(
    val lemma: String,
    val tense: SpanishTense,
    val person: SpanishPerson,
)

/**
 * The regular -ar, -er and -ir endings per cell, the same table as
 * `tools/spanish/build_spanish_data.py`. A cell is a tense letter and a person: 1–6 (yo … ellos) or
 * v (vos); G (gerund) and D (participle) have no person. F and C go on the whole infinitive, the
 * others on the infinitive without -ar, -er or -ir. `-` means the cell does not exist.
 */
private val ENDINGS: Map<Char, Map<String, List<String>>> =
    mapOf(
        'P' to classes("o as a amos áis an ás", "o es e emos éis en és", "o es e imos ís en ís"),
        'I' to classes("é aste ó amos asteis aron -", "í iste ió imos isteis ieron -", "í iste ió imos isteis ieron -"),
        'M' to classes("aba abas aba ábamos abais aban -", "ía ías ía íamos íais ían -", "ía ías ía íamos íais ían -"),
        'F' to classes("é ás á emos éis án -", "é ás á emos éis án -", "é ás á emos éis án -"),
        'C' to classes("ía ías ía íamos íais ían -", "ía ías ía íamos íais ían -", "ía ías ía íamos íais ían -"),
        'S' to classes("e es e emos éis en és", "a as a amos áis an ás", "a as a amos áis an ás"),
        'R' to
            classes("ara aras ara áramos arais aran -", "iera ieras iera iéramos ierais ieran -", "iera ieras iera iéramos ierais ieran -"),
        'E' to
            classes("ase ases ase ásemos aseis asen -", "iese ieses iese iésemos ieseis iesen -", "iese ieses iese iésemos ieseis iesen -"),
        'A' to classes("- a - - ad - á", "- e - - ed - é", "- e - - id - í"),
        'G' to classes("ando", "iendo", "iendo"),
        'D' to classes("ado", "ido", "ido"),
    )

private fun classes(
    ar: String,
    er: String,
    ir: String,
) = mapOf("ar" to ar.split(' '), "er" to er.split(' '), "ir" to ir.split(' '))

private const val CELL_PERSONS = "123456v"

private val TENSE_OF_LETTER =
    mapOf(
        'P' to SpanishTense.PRESENT,
        'I' to SpanishTense.PRETERITE,
        'M' to SpanishTense.IMPERFECT,
        'F' to SpanishTense.FUTURE,
        'C' to SpanishTense.CONDITIONAL,
        'S' to SpanishTense.PRESENT_SUBJUNCTIVE,
        'R' to SpanishTense.IMPERFECT_SUBJUNCTIVE,
        'E' to SpanishTense.IMPERFECT_SUBJUNCTIVE,
        'A' to SpanishTense.IMPERATIVE,
        'G' to SpanishTense.GERUND,
        'D' to SpanishTense.PARTICIPLE,
    )

private val PERSON_OF_CHAR =
    mapOf(
        '1' to SpanishPerson.YO,
        '2' to SpanishPerson.TU,
        '3' to SpanishPerson.EL,
        '4' to SpanishPerson.NOSOTROS,
        '5' to SpanishPerson.VOSOTROS,
        '6' to SpanishPerson.ELLOS,
        'v' to SpanishPerson.VOS,
    )

/** -ar, -er or -ir (oír, reír count as -ir). */
private fun verbClass(infinitive: String): String =
    when (infinitive.takeLast(2)) {
        "ír" -> "ir"
        else -> infinitive.takeLast(2)
    }

/** The regular ending of [cell] for [verbClass], or null where the cell does not exist. */
private fun endingOf(
    cell: String,
    verbClass: String,
): String? {
    val endings = ENDINGS.getValue(cell[0]).getValue(verbClass)
    val ending = if (cell.length == 1) endings[0] else endings[CELL_PERSONS.indexOf(cell[1])]
    return ending.takeUnless { it == "-" }
}

private fun stemFor(
    infinitive: String,
    tense: Char,
) = if (tense == 'F' || tense == 'C') infinitive else infinitive.dropLast(2)

/** Every cell a verb has, as `P1`, `Av`, `G`, `D`. */
private val ALL_CELLS: List<String> =
    "PIMFCSREA".flatMap { tense -> CELL_PERSONS.map { "$tense$it" } }.filter { cell -> endingOf(cell, "ar") != null } + listOf("G", "D")

private fun readingOf(
    lemma: String,
    cell: String,
) = SpanishVerbReading(
    lemma,
    TENSE_OF_LETTER.getValue(cell[0]),
    if (cell.length ==
        1
    ) {
        SpanishPerson.NONE
    } else {
        PERSON_OF_CHAR.getValue(cell[1])
    },
)

/**
 * Spanish verbs from `es-verbs.txt` (made by `tools/spanish/build_spanish_data.py` from
 * Wiktionary): a regular verb is its infinitive, an irregular one also lists its cells that differ
 * from the regular model. [readings] turns a form back into its verbs, tenses and persons: forms of
 * the irregular cells are kept in a table, everything else is found by its regular ending.
 */
internal class SpanishVerbs private constructor(
    /** Infinitive without -se (and oír as oir) → rank (0 is the most common verb). */
    private val ranks: Map<String, Int>,
    /** Such a key → dictionary form, where they differ (oir → oír, arrepentir → arrepentirse). */
    private val pronominal: Map<String, String>,
    /** Infinitive → the cells its line lists, which the regular endings must not produce. */
    private val irregularCells: Map<String, Set<String>>,
    /** Form of an irregular cell → its readings, with the verb's rank. */
    private val irregularForms: Map<String, List<Pair<Int, SpanishVerbReading>>>,
) {
    fun isVerb(infinitive: String): Boolean = plainKey(infinitive) in ranks

    /** Every reading of a lowercase [word], the most common verb first; empty when it is no listed verb's form. */
    fun readings(word: String): List<SpanishVerbReading> {
        if (word.length < 2) return irregularForms[word].orEmpty().map { it.second }
        val found = mutableListOf<Pair<Int, SpanishVerbReading>>()
        irregularForms[word]?.let(found::addAll)
        ranks[plainKey(word)]?.takeIf { word.endsWith("r") }?.let {
            found += it to SpanishVerbReading(lemmaOf(plainKey(word)), SpanishTense.INFINITIVE, SpanishPerson.NONE)
        }
        for (verbClass in VERB_CLASSES) {
            for (cell in ALL_CELLS) {
                val ending = endingOf(cell, verbClass) ?: continue
                // An empty stem is ir's own (ido, id).
                if (!word.endsWith(ending)) continue
                val stem = word.dropLast(ending.length)
                val infinitive = if (cell[0] == 'F' || cell[0] == 'C') stem else stem + verbClass
                if (verbClass(infinitive) != verbClass) continue
                val rank = ranks[infinitive] ?: continue
                if (cell in irregularCells[infinitive].orEmpty()) continue
                found += rank to readingOf(lemmaOf(infinitive), cell)
            }
        }
        participleReadings(word, found)
        return found
            .sortedWith(compareBy({ it.first }, { it.second.tense.ordinal }, { it.second.person.ordinal }))
            .map { it.second }
            .distinct()
    }

    /** A participle used as an adjective: hecha, hechos and cansadas are participles too. */
    private fun participleReadings(
        word: String,
        found: MutableList<Pair<Int, SpanishVerbReading>>,
    ) {
        val masculine =
            when {
                word.endsWith("as") || word.endsWith("os") -> word.dropLast(2) + "o"
                word.endsWith("a") -> word.dropLast(1) + "o"
                else -> return
            }
        if (masculine == word) return
        for ((rank, reading) in readingsOfExactCell(masculine, "D")) found += rank to reading
    }

    private fun readingsOfExactCell(
        word: String,
        cell: String,
    ): List<Pair<Int, SpanishVerbReading>> {
        val irregular = irregularForms[word].orEmpty().filter { it.second.tense == SpanishTense.PARTICIPLE }
        val regular =
            VERB_CLASSES.mapNotNull { verbClass ->
                val ending = endingOf(cell, verbClass) ?: return@mapNotNull null
                if (!word.endsWith(ending)) return@mapNotNull null
                val infinitive = word.dropLast(ending.length) + verbClass
                val rank = ranks[infinitive] ?: return@mapNotNull null
                if (cell in irregularCells[infinitive].orEmpty()) return@mapNotNull null
                rank to readingOf(lemmaOf(infinitive), cell)
            }
        return irregular + regular
    }

    private fun lemmaOf(infinitive: String) = pronominal[infinitive] ?: infinitive

    companion object {
        private val VERB_CLASSES = listOf("ar", "er", "ir")

        /** Oír, reír and sonreír are found by their regular -ir endings, so they are kept as oir, reir. */
        private fun plainKey(infinitive: String) = if (infinitive.endsWith("ír")) infinitive.dropLast(2) + "ir" else infinitive

        /** Reads `es-verbs.txt` (UTF-8, `#` comment lines, most common verb first). */
        fun load(input: java.io.InputStream): SpanishVerbs {
            val ranks = HashMap<String, Int>(4096)
            val pronominal = HashMap<String, String>()
            val irregularCells = HashMap<String, Set<String>>(1024)
            val irregularForms = HashMap<String, MutableList<Pair<Int, SpanishVerbReading>>>(16_384)
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEachIndexed { rank, line ->
                    val parts = line.trim().split(' ')
                    val lemma = parts[0]
                    val infinitive = if (lemma.endsWith("se") && lemma.length > 4) lemma.dropLast(2) else lemma
                    val key = plainKey(infinitive)
                    if (key != lemma) pronominal[key] = lemma
                    if (ranks.putIfAbsent(key, rank) != null) return@forEachIndexed
                    if (parts.size == 1) return@forEachIndexed
                    val cells = mutableSetOf<String>()
                    for (part in parts.drop(1)) {
                        for ((cell, forms) in decodeCells(infinitive, part)) {
                            cells += cell
                            for (form in forms) irregularForms.getOrPut(form) { mutableListOf() } += rank to readingOf(lemma, cell)
                        }
                    }
                    irregularCells[key] = cells
                }
            }
            return SpanishVerbs(ranks, pronominal, irregularCells, irregularForms)
        }

        /** `P1236=piens` (that stem with the regular endings) or `P1:soy` / `D:impreso,imprimido` (the forms). */
        private fun decodeCells(
            infinitive: String,
            part: String,
        ): List<Pair<String, List<String>>> {
            val tense = part[0]
            val stemmed = part.indexOf('=')
            if (stemmed < 0) return listOf(part.substringBefore(':') to part.substringAfter(':').split(','))
            val stem = part.substring(stemmed + 1)
            return part.substring(1, stemmed).mapNotNull { person ->
                val cell = "$tense$person"
                endingOf(cell, verbClass(infinitive))?.let { cell to listOf(stem + it) }
            }
        }
    }
}

/** The regular forms of [infinitive] by cell, for tests: what a verb without overrides conjugates to. */
internal fun regularSpanishForms(infinitive: String): Map<String, String> =
    ALL_CELLS.mapNotNull { cell -> endingOf(cell, verbClass(infinitive))?.let { cell to stemFor(infinitive, cell[0]) + it } }.toMap()
