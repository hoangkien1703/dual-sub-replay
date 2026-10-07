package com.kienhoang.dualsubreplay.data

/**
 * One grammar point in a sentence: its usual written [form] (〜 stands for the word it attaches
 * to), the characters it covers, its [meanings] (the likeliest in this sentence first) and the
 * JLPT [level] it is taught at.
 */
internal data class GrammarMatch(
    val form: String,
    val start: Int,
    val end: Int,
    val meanings: List<GrammarMeaning>,
    val level: JlptLevel = JlptLevel.N5,
)

/** A rule's hit: how many morphemes from the start index it covers, and what it is. */
private class RuleHit(
    val length: Int,
    val form: String,
    val meanings: List<GrammarMeaning>,
    val level: JlptLevel? = null,
)

private typealias GrammarRule = (List<Morpheme>, Int) -> RuleHit?

private const val PARTICLE = "助詞"
private const val AUXILIARY = "助動詞"
private const val VERB = "動詞"
private const val ADJECTIVE = "形容詞"
private const val NOUN = "名詞"
private const val DEPENDENT = "非自立"
private const val SUFFIX = "接尾"
private const val CASE = "格助詞"
private const val CONJUNCTIVE = "接続助詞"

/** A point found at [index]; a lower [priority] wins between hits of the same length. */
private class Candidate(
    val priority: Int,
    val index: Int,
    val hit: RuleHit,
)

/**
 * Finds the grammar points of a sentence from its analyzer morphemes, the way Renshuu and
 * ichi.moe do: the analyzer tags each piece, and a list of patterns names them. The catalogue
 * (`JapaneseGrammarCatalogue.kt`) holds most points as data; the rules below handle the few whose
 * meaning depends on the words around them. Longer points win and each morpheme belongs to one
 * point, so the た of 〜ました or the parts of 〜わけにはいかない are not listed again on their own.
 */
internal fun japaneseGrammar(morphemes: List<Morpheme>): List<GrammarMatch> {
    val catalogue =
        GRAMMAR_CATALOGUE.flatMapIndexed { priority, pattern ->
            morphemes.indices.mapNotNull { index ->
                pattern
                    .lengthAt(morphemes, index)
                    .takeIf { it > 0 }
                    ?.let { Candidate(priority, index, RuleHit(it, pattern.form, pattern.meanings, pattern.level)) }
            }
        }
    val rules =
        GRAMMAR_RULES.flatMapIndexed { priority, rule ->
            morphemes.indices.mapNotNull { index ->
                rule(morphemes, index)?.let { hit -> Candidate(GRAMMAR_CATALOGUE.size + priority, index, hit) }
            }
        }
    val accepted = mutableListOf<Pair<IntRange, GrammarMatch>>()
    val ordered = (catalogue + rules).sortedWith(compareBy({ -it.hit.length }, { it.priority }, { it.index }))
    for (candidate in ordered) {
        val range = candidate.index until candidate.index + candidate.hit.length
        if (accepted.any { (taken, _) -> range.first <= taken.last && range.last >= taken.first }) continue
        val hit = candidate.hit
        val level = hit.level ?: RULE_LEVELS[hit.form] ?: JlptLevel.N5
        accepted += range to GrammarMatch(hit.form, morphemes[range.first].start, morphemes[range.last].end, hit.meanings, level)
    }
    return accepted.map { it.second }.sortedWith(compareBy({ it.start }, { it.end }))
}

/** The JLPT level of the points the rules below find; any form not listed is N5. */
private val RULE_LEVELS =
    mapOf(
        "〜なきゃ" to JlptLevel.N4,
        "〜なければならない" to JlptLevel.N4,
        "〜なければいけない" to JlptLevel.N4,
        "〜なくてはいけない" to JlptLevel.N4,
        "〜ことができる" to JlptLevel.N4,
        "〜てしまう" to JlptLevel.N4,
        "〜てくれる" to JlptLevel.N4,
        "〜てあげる" to JlptLevel.N4,
        "〜てもらう" to JlptLevel.N4,
        "〜てみる" to JlptLevel.N4,
        "〜ておく" to JlptLevel.N4,
        "〜てある" to JlptLevel.N4,
        "〜てくる" to JlptLevel.N4,
        "〜ていく" to JlptLevel.N4,
        "〜てる" to JlptLevel.N4,
        "〜ちゃう" to JlptLevel.N4,
        "〜とく" to JlptLevel.N4,
        "だろう" to JlptLevel.N4,
        "〜たら" to JlptLevel.N4,
        "〜(よ)う" to JlptLevel.N4,
        "〜(ら)れる" to JlptLevel.N4,
        "〜(さ)せる" to JlptLevel.N4,
        "〜そう" to JlptLevel.N4,
        "ば" to JlptLevel.N4,
        "たり" to JlptLevel.N4,
        "のに" to JlptLevel.N4,
        "〜の" to JlptLevel.N4,
        "において" to JlptLevel.N3,
        "における" to JlptLevel.N3,
        "について" to JlptLevel.N3,
        "についての" to JlptLevel.N3,
        "によって" to JlptLevel.N3,
        "により" to JlptLevel.N3,
        "による" to JlptLevel.N3,
        "として" to JlptLevel.N3,
        "にとって" to JlptLevel.N3,
        "に対して" to JlptLevel.N3,
        "に対する" to JlptLevel.N3,
    )

/**
 * The grammar points that belong to a selection from [start] to [end]: those inside or overlapping
 * it, and one that starts right after it (体育館 + に), since particles and endings follow their word.
 */
internal fun grammarForSelection(
    matches: List<GrammarMatch>,
    start: Int,
    end: Int,
): List<GrammarMatch> {
    val inside = matches.filter { it.start < end && it.end > start }
    val following = matches.filter { it.start == end }.take(1)
    return (inside + following).distinct()
}

private fun Morpheme.isParticle(vararg details: String) = pos == PARTICLE && (details.isEmpty() || detail in details)

private fun Morpheme.isTe() = isParticle(CONJUNCTIVE) && surface in setOf("て", "で")

private fun Morpheme.isHelperVerb(vararg bases: String) = pos == VERB && detail == DEPENDENT && baseForm in bases

private fun List<Morpheme>.at(index: Int): Morpheme? = getOrNull(index)

/** The indices of the verbs after [index] in the same clause, up to the next punctuation. */
private fun List<Morpheme>.clauseVerbs(index: Int): List<Int> =
    (index + 1 until size)
        .takeWhile { this[it].pos != "記号" }
        .filter { this[it].pos == VERB }

/** The first verb after [index] in the same clause, skipping particles and nouns. */
private fun List<Morpheme>.nextVerb(index: Int): Morpheme? = clauseVerbs(index).firstOrNull()?.let(::get)

private val QUOTE_VERBS = setOf("言う", "思う", "考える", "聞く", "書く", "呼ぶ", "答える", "感じる")
private val FEELING_WORDS = setOf("好き", "嫌い", "大好き", "上手", "下手", "得意", "苦手", "できる", "分かる", "わかる", "欲しい", "ほしい")
private val MOVEMENT_VERBS = setOf("行く", "来る", "帰る", "着く", "入る", "乗る", "なる", "戻る", "向かう", "置く", "出かける", "登る", "移る")
private val PURPOSE_VERBS = setOf("行く", "来る", "帰る", "出かける", "戻る")
private val EXCHANGE_VERBS =
    setOf("あげる", "くれる", "もらう", "貸す", "借りる", "教える", "習う", "言う", "聞く", "会う", "話す", "見せる", "送る", "渡す", "頼む", "電話")
private val TIME_ENDINGS = listOf("時", "日", "年", "月", "曜日", "分", "朝", "夜", "週", "秒")

private val NI_MEANINGS =
    listOf(
        GrammarMeaning.PLACE_EXIST,
        GrammarMeaning.TIME,
        GrammarMeaning.DESTINATION,
        GrammarMeaning.RECIPIENT,
        GrammarMeaning.PURPOSE,
        GrammarMeaning.BY_PASSIVE,
    )

/** に has many uses; the words around it pick the likeliest, and the rest stay as alternatives. */
private fun likeliestNiMeaning(
    morphemes: List<Morpheme>,
    index: Int,
): GrammarMeaning {
    val previous = morphemes.at(index - 1)
    val verbs = morphemes.clauseVerbs(index)
    val verb = verbs.firstOrNull()?.let(morphemes::get)
    val passive = verbs.any { morphemes[it].detail == SUFFIX && morphemes[it].baseForm in setOf("れる", "られる") }
    val time = previous != null && (previous.detail == "副詞可能" || TIME_ENDINGS.any { previous.surface.endsWith(it) })
    return when {
        previous?.pos == VERB && previous.conjugationForm == "連用形" && verb?.baseForm in PURPOSE_VERBS -> GrammarMeaning.PURPOSE
        passive -> GrammarMeaning.BY_PASSIVE
        time -> GrammarMeaning.TIME
        verb?.baseForm in MOVEMENT_VERBS -> GrammarMeaning.DESTINATION
        verbs.any { morphemes[it].baseForm in EXCHANGE_VERBS } || morphemes.at(index + 1)?.surface in EXCHANGE_VERBS ->
            GrammarMeaning.RECIPIENT
        else -> GrammarMeaning.PLACE_EXIST
    }
}

private fun likeliestFirst(
    meanings: List<GrammarMeaning>,
    likeliest: GrammarMeaning,
): List<GrammarMeaning> = listOf(likeliest) + (meanings - likeliest)

private fun hit(
    form: String,
    vararg meanings: GrammarMeaning,
    length: Int = 1,
) = RuleHit(length, form, meanings.toList())

private fun mustRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val first = m[i]
    if (first.pos != AUXILIARY || first.baseForm != "ない") return null
    if (first.surface in setOf("なきゃ", "なくちゃ")) return hit("〜なきゃ", GrammarMeaning.MUST)
    // なければならない / なければいけない / なくてはいけない
    val conditional = first.surface == "なけれ" && m.at(i + 1)?.surface == "ば"
    val teWa = first.surface == "なく" && m.at(i + 1)?.isTe() == true && m.at(i + 2)?.surface == "は"
    if (!conditional && !teWa) return null
    val helperIndex = if (conditional) i + 2 else i + 3
    val helper = m.at(helperIndex)?.takeIf { it.baseForm in setOf("なる", "いける", "だめ", "ダメ") } ?: return null
    val endings = m.drop(helperIndex + 1).takeWhile { it.pos == AUXILIARY }.size
    val form = if (conditional) "〜なければ${if (helper.baseForm == "なる") "ならない" else "いけない"}" else "〜なくてはいけない"
    return hit(form, GrammarMeaning.MUST, length = helperIndex - i + 1 + endings)
}

private fun teCombinationRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val te = m[i]
    val contracted = te.isParticle(CONJUNCTIVE) && te.surface in setOf("ちゃ", "じゃ")
    if (!te.isTe() && !contracted) return null
    val second = m.at(i + 1) ?: return null
    val third = m.at(i + 2)
    return when {
        (second.surface == "は" || contracted) && (if (contracted) second else third)?.baseForm in setOf("いける", "だめ", "ダメ") -> {
            val helperIndex = if (contracted) i + 1 else i + 2
            val endings = m.drop(helperIndex + 1).takeWhile { it.pos == AUXILIARY }.size
            hit("〜てはいけない", GrammarMeaning.MUST_NOT, length = helperIndex - i + 1 + endings)
        }
        !contracted && second.surface == "も" && third?.baseForm in setOf("いい", "よい", "良い") ->
            hit("〜てもいい", GrammarMeaning.MAY, length = 3)
        else -> null
    }
}

private val TE_HELPERS =
    mapOf(
        "いる" to ("〜ている" to GrammarMeaning.PROGRESSIVE),
        "しまう" to ("〜てしまう" to GrammarMeaning.COMPLETION),
        "くれる" to ("〜てくれる" to GrammarMeaning.FAVOR_RECEIVE),
        "くださる" to ("〜てください" to GrammarMeaning.PLEASE),
        "あげる" to ("〜てあげる" to GrammarMeaning.FAVOR_GIVE),
        "もらう" to ("〜てもらう" to GrammarMeaning.FAVOR_HAVE),
        "みる" to ("〜てみる" to GrammarMeaning.TRY),
        "おく" to ("〜ておく" to GrammarMeaning.IN_ADVANCE),
        "ある" to ("〜てある" to GrammarMeaning.RESULT_STATE),
        "くる" to ("〜てくる" to GrammarMeaning.CHANGE_COMING),
        "来る" to ("〜てくる" to GrammarMeaning.CHANGE_COMING),
        "いく" to ("〜ていく" to GrammarMeaning.CHANGE_GOING),
        "行く" to ("〜ていく" to GrammarMeaning.CHANGE_GOING),
    )

/** Casual contractions that already include the て: 見てる, 食べちゃう, やっとく. */
private val CONTRACTED_HELPERS =
    mapOf(
        "てる" to ("〜てる" to GrammarMeaning.PROGRESSIVE),
        "でる" to ("〜てる" to GrammarMeaning.PROGRESSIVE),
        "ちゃう" to ("〜ちゃう" to GrammarMeaning.COMPLETION),
        "じゃう" to ("〜ちゃう" to GrammarMeaning.COMPLETION),
        "とく" to ("〜とく" to GrammarMeaning.IN_ADVANCE),
        "どく" to ("〜とく" to GrammarMeaning.IN_ADVANCE),
    )

private fun teHelperRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val first = m[i]
    CONTRACTED_HELPERS[first.baseForm]?.takeIf { first.pos == VERB && first.detail == DEPENDENT }?.let { (form, meaning) ->
        return hit(form, meaning)
    }
    if (!first.isTe()) return null
    val helper = m.at(i + 1)?.takeIf { it.isHelperVerb(*TE_HELPERS.keys.toTypedArray()) } ?: return null
    val (form, meaning) = TE_HELPERS.getValue(helper.baseForm)
    return hit(form, meaning, length = 2)
}

private fun canRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val koto = m[i]
    if (koto.surface != "こと" || koto.pos != NOUN) return null
    if (m.at(i + 1)?.surface != "が" || m.at(i + 2)?.baseForm != "できる") return null
    return hit("〜ことができる", GrammarMeaning.CAN, length = 3)
}

private fun masuRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val masu = m[i]
    if (masu.pos != AUXILIARY || masu.baseForm != "ます") return null
    val next = m.at(i + 1)
    return when {
        masu.conjugationForm == "連用形" && next?.baseForm == "た" -> hit("〜ました", GrammarMeaning.POLITE_PAST, length = 2)
        masu.conjugationForm == "未然形" && next?.baseForm == "ん" -> {
            val past = m.at(i + 2)?.surface == "でし" && m.at(i + 3)?.baseForm == "た"
            if (past) {
                hit("〜ませんでした", GrammarMeaning.POLITE_NEGATIVE_PAST, length = 4)
            } else {
                hit("〜ません", GrammarMeaning.POLITE_NEGATIVE, length = 2)
            }
        }
        masu.conjugationForm == "未然ウ接続" && next?.baseForm == "う" -> hit("〜ましょう", GrammarMeaning.LETS_POLITE, length = 2)
        else -> hit("〜ます", GrammarMeaning.POLITE)
    }
}

private fun auxiliaryRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val aux = m[i]
    if (aux.pos != AUXILIARY) return null
    val next = m.at(i + 1)
    val previous = m.at(i - 1)
    return when (aux.baseForm) {
        "です", "だ" ->
            when {
                aux.conjugationForm == "未然形" && next?.baseForm == "う" ->
                    hit(if (aux.baseForm == "です") "でしょう" else "だろう", GrammarMeaning.PROBABLY, length = 2)
                aux.conjugationForm == "体言接続" -> hit("〜な", GrammarMeaning.NA_ADJECTIVE)
                aux.baseForm == "です" -> hit("です", GrammarMeaning.COPULA_POLITE)
                previous?.pos == VERB || previous?.pos == ADJECTIVE -> null
                else -> hit("だ", GrammarMeaning.COPULA)
            }
        "た" -> if (aux.surface in setOf("たら", "だら")) hit("〜たら", GrammarMeaning.IF_WHEN) else hit("〜た", GrammarMeaning.PAST)
        "ない", "ぬ", "ん" -> hit("〜ない", GrammarMeaning.NEGATIVE)
        "たい" -> hit("〜たい", GrammarMeaning.WANT)
        "う", "よう" -> if (previous?.pos == VERB) hit("〜(よ)う", GrammarMeaning.VOLITIONAL) else null
        else -> null
    }
}

private fun verbEndingRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val ending = m[i]
    return when {
        ending.pos == VERB && ending.detail == SUFFIX && ending.baseForm in setOf("れる", "られる") ->
            hit("〜(ら)れる", GrammarMeaning.PASSIVE, GrammarMeaning.POTENTIAL, GrammarMeaning.RESPECT)
        ending.pos == VERB && ending.detail == SUFFIX && ending.baseForm in setOf("せる", "させる") ->
            hit("〜(さ)せる", GrammarMeaning.CAUSATIVE)
        ending.pos == NOUN && ending.subDetail == "助動詞語幹" && ending.surface == "そう" -> hit("〜そう", GrammarMeaning.LOOKS_LIKE)
        ending.pos == ADJECTIVE && ending.baseForm == "ない" && m.at(i - 1)?.pos == ADJECTIVE -> hit("〜ない", GrammarMeaning.NEGATIVE)
        else -> null
    }
}

private val COMPOUND_PARTICLES =
    mapOf(
        "において" to GrammarMeaning.IN_AT_FORMAL,
        "における" to GrammarMeaning.IN_AT_FORMAL,
        "について" to GrammarMeaning.ABOUT,
        "についての" to GrammarMeaning.ABOUT,
        "によって" to GrammarMeaning.BY_DEPENDING,
        "により" to GrammarMeaning.BY_DEPENDING,
        "による" to GrammarMeaning.BY_DEPENDING,
        "として" to GrammarMeaning.AS_ROLE,
        "にとって" to GrammarMeaning.FOR_VIEWPOINT,
        "に対して" to GrammarMeaning.TOWARD_CONTRAST,
        "に対する" to GrammarMeaning.TOWARD_CONTRAST,
    )

private fun compoundParticleRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val particle = m[i]
    if (!particle.isParticle(CASE)) return null
    return COMPOUND_PARTICLES[particle.surface]?.let { hit(particle.surface, it) }
}

/** Particles whose meaning does not depend on the words around them. */
private val SIMPLE_PARTICLES =
    mapOf(
        "へ" to listOf(GrammarMeaning.TOWARD),
        "も" to listOf(GrammarMeaning.ALSO, GrammarMeaning.EVEN),
        "まで" to listOf(GrammarMeaning.UNTIL),
        "より" to listOf(GrammarMeaning.THAN, GrammarMeaning.FROM_FORMAL),
        "や" to listOf(GrammarMeaning.AND_AMONG),
        "ね" to listOf(GrammarMeaning.SEEK_AGREEMENT),
        "よ" to listOf(GrammarMeaning.NEW_INFO),
        "けど" to listOf(GrammarMeaning.ALTHOUGH),
        "けれど" to listOf(GrammarMeaning.ALTHOUGH),
        "けれども" to listOf(GrammarMeaning.ALTHOUGH),
        "ので" to listOf(GrammarMeaning.SO),
        "のに" to listOf(GrammarMeaning.EVEN_THOUGH),
        "ながら" to listOf(GrammarMeaning.WHILE),
        "たり" to listOf(GrammarMeaning.THINGS_LIKE),
        "だり" to listOf(GrammarMeaning.THINGS_LIKE),
        "しか" to listOf(GrammarMeaning.ONLY_NEGATIVE),
        "だけ" to listOf(GrammarMeaning.ONLY),
        "ば" to listOf(GrammarMeaning.IF),
    )

private fun particleRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val particle = m[i]
    if (particle.pos != PARTICLE) return null
    val surface = particle.surface
    val form = if (surface == "だり") "たり" else surface
    return when (surface) {
        "は" -> hit(surface, GrammarMeaning.TOPIC, GrammarMeaning.CONTRAST).takeIf { particle.detail == "係助詞" }
        "が" -> gaHit(m, i)
        "を" -> hit(surface, GrammarMeaning.OBJECT, GrammarMeaning.THROUGH)
        "に" -> RuleHit(1, surface, likeliestFirst(NI_MEANINGS, likeliestNiMeaning(m, i))).takeIf { particle.detail == CASE }
        "で" ->
            when {
                particle.detail == CASE ->
                    hit(surface, GrammarMeaning.PLACE_OF_ACTION, GrammarMeaning.MEANS, GrammarMeaning.CAUSE, GrammarMeaning.LIMIT)
                particle.isTe() -> hit("〜て", GrammarMeaning.TE_AND)
                else -> null
            }
        "と" -> toHit(m, i)
        "から" -> hit(surface, if (particle.detail == CONJUNCTIVE) GrammarMeaning.BECAUSE else GrammarMeaning.FROM)
        "の" -> hit(surface, if (particle.detail == "終助詞") GrammarMeaning.EXPLAIN else GrammarMeaning.POSSESSIVE)
        "か" -> kaHit(m, i)
        "て" -> hit("〜て", GrammarMeaning.TE_AND).takeIf { particle.isTe() }
        else -> SIMPLE_PARTICLES[surface]?.let { RuleHit(1, form, it) }
    }
}

private fun gaHit(
    m: List<Morpheme>,
    i: Int,
): RuleHit {
    if (m[i].detail == CONJUNCTIVE) return hit("が", GrammarMeaning.BUT)
    val feeling = m.drop(i + 1).take(2).any { it.baseForm in FEELING_WORDS || it.surface in FEELING_WORDS }
    val meanings = listOf(GrammarMeaning.SUBJECT, GrammarMeaning.OBJECT_OF_FEELING)
    return RuleHit(1, "が", if (feeling) meanings.reversed() else meanings)
}

private fun toHit(
    m: List<Morpheme>,
    i: Int,
): RuleHit {
    if (m[i].detail == CONJUNCTIVE) return hit("と", GrammarMeaning.WHEN_NATURAL)
    val quoting = m.nextVerb(i)?.baseForm in QUOTE_VERBS
    val meanings = listOf(GrammarMeaning.WITH_AND, GrammarMeaning.QUOTE)
    return RuleHit(1, "と", if (quoting) meanings.reversed() else meanings)
}

private fun kaHit(
    m: List<Morpheme>,
    i: Int,
): RuleHit {
    val next = m.at(i + 1)
    val ending = next == null || next.pos == "記号"
    val meanings = listOf(GrammarMeaning.QUESTION, GrammarMeaning.OR)
    return RuleHit(1, "か", if (ending) meanings else meanings.reversed())
}

/** の that turns a verb or adjective phrase into a noun (勉強するのが好き), or the のに of 雨なのに. */
private fun nominalizerRule(
    m: List<Morpheme>,
    i: Int,
): RuleHit? {
    val no = m[i]
    if (no.surface != "の" || no.pos != NOUN || no.detail != DEPENDENT) return null
    val previous = m.at(i - 1) ?: return null
    val next = m.at(i + 1)
    return when {
        previous.surface == "な" && next?.surface == "に" && next.isParticle(CASE) -> hit("のに", GrammarMeaning.EVEN_THOUGH, length = 2)
        previous.pos == VERB || previous.pos == ADJECTIVE || previous.pos == AUXILIARY -> hit("〜の", GrammarMeaning.NOMINALIZER)
        else -> null
    }
}

/** In priority order: between hits of the same length, the earlier rule wins. */
private val GRAMMAR_RULES: List<GrammarRule> =
    listOf(
        ::mustRule,
        ::teCombinationRule,
        ::canRule,
        ::teHelperRule,
        ::masuRule,
        ::auxiliaryRule,
        ::verbEndingRule,
        ::compoundParticleRule,
        ::nominalizerRule,
        ::particleRule,
    )
