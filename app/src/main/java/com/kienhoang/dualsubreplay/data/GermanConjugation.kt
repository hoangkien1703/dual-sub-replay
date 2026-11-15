package com.kienhoang.dualsubreplay.data

private const val VOWELS = "aeiouäöüy"
private const val SIBILANTS = "sßxz"

/** Tenses and moods of a finite or non-finite verb cell; [code] is the first part of a cell such as `pres3s`. */
internal enum class GermanTense(
    val code: String,
) {
    PRESENT("pres"),
    PRETERITE("prat"),
    SUBJUNCTIVE_I("k1"),
    SUBJUNCTIVE_II("k2"),
    IMPERATIVE("imp"),
    PARTICIPLE_I("pa1"),
    PARTICIPLE_II("pa2"),
    INFINITIVE("inf"),
}

/** One cell of a conjugation table: the tense or mood, the person (0 for non-finite) and the number. */
internal data class GermanCell(
    val tense: GermanTense,
    val person: Int = 0,
    val plural: Boolean = false,
) {
    val code: String get() = tense.code + (if (person > 0) person.toString() + (if (plural) "p" else "s") else "")
}

/**
 * A verb of `de-verbs.txt`. Only the principal parts the rules cannot derive are stored; the rest are
 * derived exactly as `tools/german/build_german_data.py` does (keep needsE, weakPresent3, weakPreterite and
 * weakParticiple in sync with it). [prefix] is a separable prefix; [base] is the verb without it.
 */
internal class GermanVerb(
    val infinitive: String,
    val auxiliaries: String,
    val modal: Boolean,
    val reflexive: Boolean,
    val prefix: String,
    private val present3Given: String,
    private val past3Given: String,
    private val participlesGiven: List<String>,
    private val subjunctive2Given: List<String>,
) {
    val base: String = if (prefix.isNotEmpty() && infinitive.startsWith(prefix)) infinitive.removePrefix(prefix) else infinitive
    val stem: String = germanStem(base)
    val separable: Boolean get() = prefix.isNotEmpty()
    val takesSein: Boolean get() = 's' in auxiliaries
    val takesHaben: Boolean get() = 'h' in auxiliaries || auxiliaries.isEmpty()

    val present3: String = present3Given.ifEmpty { stem + (if (needsE(stem)) "et" else "t") }
    val past3: String = past3Given.ifEmpty { stem + (if (needsE(stem)) "ete" else "te") }
    val participle: String = participlesGiven.firstOrNull() ?: weakParticiple(infinitive, base, prefix)

    /** Participle II and, for modals and verbs like lassen, the infinitive that stands for it (hat lassen). */
    val participles: List<String> = participlesGiven.ifEmpty { listOf(participle) }

    /** Past endings are -te (weak or mixed) or without a vowel (strong). */
    private val weakType: Boolean get() = past3Given.isEmpty() || past3.endsWith("te")

    /** Every form of every cell. The finite cells are the detached forms (steht); see [attached]. */
    val forms: Map<GermanCell, Set<String>> by lazy { buildForms() }

    /** The finite cells of a separable verb with its prefix in front (aufsteht), as in a subordinate clause. */
    val attached: Map<GermanCell, Set<String>> by lazy {
        forms.mapValues { (cell, set) ->
            if (cell.person > 0 && separable) set.mapTo(LinkedHashSet()) { prefix + it } else set
        }
    }

    /** Principal parts the rules cannot derive, as the stems analysis may meet them (gib, ging, hielt). */
    val irregularStems: Set<String> by lazy {
        // A regular verb is found by stripping endings, so its table is not built just to index it.
        if (present3Given.isEmpty() && past3Given.isEmpty() && subjunctive2Given.isEmpty() && infinitive != "sein") return@lazy emptySet()
        buildSet {
            for (part in listOf(present3Given, past3Given) + subjunctive2Given) if (part.isNotEmpty()) add(part)
            if (present3Given.endsWith("t")) add(present3Given.dropLast(1))
            if (present3Given.isNotEmpty()) {
                add(presentSecond())
                if (!modal) imperativeSingular().filter { it.isNotEmpty() }.forEach(::add)
            }
            // sein is too irregular for stripped endings: every one of its forms is a stem of its own.
            if (infinitive == "sein") addAll(forms.values.flatten())
        }
    }

    private fun buildForms(): Map<GermanCell, Set<String>> {
        val table = FormTable()
        addPresent(table)
        addPreterite(table)
        addSubjunctiveI(table)
        addSubjunctiveII(table)
        addImperativeAndParticiples(table)
        return table.cells
    }

    /** The plural stem: tun keeps its u (tut) where other verbs lose -en. */
    private val pluralStem: String get() = if (base == "tun") "tu" else stem

    private fun pluralEnding(): String = pluralStem + (if (needsE(stem)) "et" else "t")

    private fun addPresent(table: FormTable) {
        val sein = infinitive == "sein"
        val first =
            when {
                sein -> arrayOf("bin")
                modal -> arrayOf(present3)
                base.endsWith("eln") -> arrayOf(stem.dropLast(2) + "le", stem + "e", stem)
                base.endsWith("ern") -> arrayOf(stem + "e", stem.dropLast(2) + "re", stem)
                else -> arrayOf(stem + "e")
            }
        table.add(GermanTense.PRESENT, 1, false, *first)
        table.add(GermanTense.PRESENT, 2, false, presentSecond())
        table.add(GermanTense.PRESENT, 3, false, if (sein) "ist" else present3)
        table.add(GermanTense.PRESENT, 1, true, if (sein) "sind" else base)
        table.add(GermanTense.PRESENT, 2, true, if (sein) "seid" else pluralEnding())
        table.add(GermanTense.PRESENT, 3, true, if (sein) "sind" else base)
    }

    /** Präteritum: the stored form is the 1st and 3rd person singular; the rest are its endings. */
    private fun addPreterite(table: FormTable) {
        val past = past3
        val dentalEnd = past.endsWith("d") || past.endsWith("t") || past.endsWith("sch")
        val sibilantEnd = past.last() in SIBILANTS
        table.add(GermanTense.PRETERITE, 1, false, past)
        table.add(GermanTense.PRETERITE, 3, false, past)
        if (past.endsWith("e")) {
            table.add(GermanTense.PRETERITE, 2, false, past + "st")
            table.add(GermanTense.PRETERITE, 1, true, past + "n")
            table.add(GermanTense.PRETERITE, 2, true, past + "t")
        } else {
            when {
                sibilantEnd -> table.add(GermanTense.PRETERITE, 2, false, past + "t", past + "est")
                dentalEnd -> table.add(GermanTense.PRETERITE, 2, false, past + "st", past + "est")
                else -> table.add(GermanTense.PRETERITE, 2, false, past + "st")
            }
            table.add(GermanTense.PRETERITE, 1, true, past + "en")
            table.add(GermanTense.PRETERITE, 2, true, past + if (dentalEnd) "et" else "t")
        }
        table.add(GermanTense.PRETERITE, 3, true, *table.forms(GermanTense.PRETERITE, 1, true).toTypedArray())
    }

    private fun addSubjunctiveI(table: FormTable) {
        if (infinitive == "sein") {
            table.add(GermanTense.SUBJUNCTIVE_I, 1, false, "sei")
            table.add(GermanTense.SUBJUNCTIVE_I, 2, false, "seist", "seiest")
            table.add(GermanTense.SUBJUNCTIVE_I, 3, false, "sei")
            table.add(GermanTense.SUBJUNCTIVE_I, 1, true, "seien")
            table.add(GermanTense.SUBJUNCTIVE_I, 2, true, "seiet")
            table.add(GermanTense.SUBJUNCTIVE_I, 3, true, "seien")
            return
        }
        // -eln and -ern verbs drop one e (sammle, wandre) or keep both (sammele, wandere).
        val contracts = base.endsWith("eln") || base.endsWith("ern")
        val kStems = listOf(stem) + (if (contracts) listOf(stem.dropLast(2) + stem.last()) else emptyList())
        val plural = if (base == "tun") "tuen" else base
        table.add(GermanTense.SUBJUNCTIVE_I, 1, false, *kStems.map { it + "e" }.toTypedArray())
        table.add(GermanTense.SUBJUNCTIVE_I, 2, false, *kStems.map { it + "est" }.toTypedArray())
        table.add(GermanTense.SUBJUNCTIVE_I, 3, false, *kStems.map { it + "e" }.toTypedArray())
        table.add(GermanTense.SUBJUNCTIVE_I, 1, true, plural)
        table.add(GermanTense.SUBJUNCTIVE_I, 2, true, *kStems.map { it + "et" }.toTypedArray())
        table.add(GermanTense.SUBJUNCTIVE_I, 3, true, plural)
    }

    /** Konjunktiv II: the stored forms, or the preterite of a weak verb. */
    private fun addSubjunctiveII(table: FormTable) {
        val second =
            when {
                subjunctive2Given.isNotEmpty() -> subjunctive2Given
                weakType -> listOf(past3)
                else -> emptyList()
            }
        for (form in second) {
            val bare = form.removeSuffix("e")
            val ends = form.endsWith("e")
            table.add(GermanTense.SUBJUNCTIVE_II, 1, false, form)
            table.add(GermanTense.SUBJUNCTIVE_II, 3, false, form)
            table.add(GermanTense.SUBJUNCTIVE_II, 2, false, if (ends) form + "st" else form + "est", bare + "st")
            table.add(GermanTense.SUBJUNCTIVE_II, 1, true, if (ends) form + "n" else form + "en")
            table.add(GermanTense.SUBJUNCTIVE_II, 3, true, if (ends) form + "n" else form + "en")
            table.add(GermanTense.SUBJUNCTIVE_II, 2, true, if (ends) form + "t" else form + "et", bare + "t")
        }
    }

    private fun addImperativeAndParticiples(table: FormTable) {
        val sein = infinitive == "sein"
        if (!modal) {
            table.add(GermanTense.IMPERATIVE, 2, false, *imperativeSingular())
            table.add(GermanTense.IMPERATIVE, 2, true, if (sein) "seid" else pluralEnding())
        }
        val presentParticiple =
            when {
                sein -> "seiend"
                base == "tun" -> infinitive.dropLast(1) + "end"
                else -> infinitive + "d"
            }
        table.add(GermanTense.PARTICIPLE_I, 0, false, presentParticiple)
        table.add(GermanTense.PARTICIPLE_II, 0, false, *participles.toTypedArray())
        table.add(GermanTense.INFINITIVE, 0, false, infinitive)
    }

    private fun presentSecond(): String {
        val p3 = present3
        return when {
            infinitive == "sein" -> "bist"
            base == "werden" -> "wirst"
            modal -> p3 + if (p3.last() in SIBILANTS) "t" else "st"
            present3Given.isNotEmpty() ->
                when {
                    p3.endsWith("st") || p3.endsWith("ßt") || p3.endsWith("zt") || p3.endsWith("xt") -> p3
                    stem.endsWith("t") -> p3 + "st"
                    p3.endsWith("t") -> p3.dropLast(1) + "st"
                    else -> p3 + "st"
                }
            stem.last() in SIBILANTS -> stem + "t"
            needsE(stem) -> stem + "est"
            else -> stem + "st"
        }
    }

    private fun imperativeSingular(): Array<String> {
        if (infinitive == "sein") return arrayOf("sei")
        if (base == "werden") return arrayOf("werde")
        val given = present3Given
        if (given.isNotEmpty() && eChangesToI(given)) return arrayOf(if (stem.endsWith("t")) given else given.removeSuffix("t"))
        val dropped = if (base.endsWith("eln") || base.endsWith("ern")) stem.dropLast(2) + stem.last() + "e" else ""
        return arrayOf(stem, stem + "e", dropped)
    }

    private fun eChangesToI(p3: String): Boolean {
        val bare = if (stem.endsWith("t")) p3 else p3.removeSuffix("t")
        val at = bare.indices.firstOrNull { it < stem.length && bare[it] != stem[it] } ?: return false
        return stem[at] == 'e' && bare[at] == 'i'
    }
}

/** The cells of one verb's conjugation table while it is built. */
private class FormTable {
    val cells = LinkedHashMap<GermanCell, MutableSet<String>>()

    fun add(
        tense: GermanTense,
        person: Int,
        plural: Boolean,
        vararg forms: String,
    ) {
        cells.getOrPut(GermanCell(tense, person, plural)) { LinkedHashSet() }.addAll(forms.filter { it.isNotEmpty() })
    }

    fun forms(
        tense: GermanTense,
        person: Int,
        plural: Boolean,
    ): Set<String> = cells.getValue(GermanCell(tense, person, plural))
}

internal fun germanStem(base: String): String = if (base.endsWith("en")) base.dropLast(2) else base.dropLast(1)

/** Whether -st, -t and -te take an e: arbeitet, atmet, rechnet, öffnet, but wohnt, kennt, lernt, kommt. */
internal fun needsE(stem: String): Boolean {
    if (stem.endsWith("d") || stem.endsWith("t")) return true
    if (stem.length < 2 || stem.last() !in "mn") return false
    val before = stem[stem.length - 2]
    if (before in VOWELS || before == 'l' || before == 'r' || before == stem.last()) return false
    return !(before == 'h' && stem.length > 2 && stem[stem.length - 3] in VOWELS)
}

private val INSEPARABLE = listOf("be", "emp", "ent", "er", "ge", "miss", "ver", "zer")

private fun weakParticiple(
    infinitive: String,
    base: String,
    prefix: String,
): String {
    val stem = germanStem(base)
    val ending = if (needsE(stem)) "et" else "t"
    return when {
        prefix.isNotEmpty() -> prefix + "ge" + stem + ending
        infinitive.endsWith("ieren") || INSEPARABLE.any { infinitive.startsWith(it) } -> stem + ending
        else -> "ge" + stem + ending
    }
}

/** One analysis of a verb form: which verb, which cell, and whether it is the prefix-attached form of a separable verb. */
internal data class GermanVerbReading(
    val verb: GermanVerb,
    val cell: GermanCell,
    val attached: Boolean,
)

/** Parses a line of `de-verbs.txt`: `lemma|flags(+prefix)|3sg|preterite|participle|subjunctive II (a/b)`. */
internal fun parseGermanVerb(line: String): GermanVerb? {
    val parts = line.split('|')
    if (parts.size < 6 || parts[0].isEmpty()) return null
    val flags = parts[1].substringBefore('+')
    val prefix = parts[1].substringAfter('+', "")
    return GermanVerb(
        infinitive = parts[0],
        auxiliaries = flags.filter { it == 'h' || it == 's' },
        modal = 'm' in flags,
        reflexive = 'r' in flags,
        prefix = prefix,
        present3Given = parts[2],
        past3Given = parts[3],
        participlesGiven = parts[4].split('/').filter { it.isNotEmpty() },
        subjunctive2Given = parts[5].split('/').filter { it.isNotEmpty() },
    )
}
