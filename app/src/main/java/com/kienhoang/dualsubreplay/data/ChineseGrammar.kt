package com.kienhoang.dualsubreplay.data

/**
 * The Chinese grammar points of a line's [morphemes]. The rules below read the words around a
 * particle (了 at the end, 是…的), so they win over the catalogue's plain 是 at the same length.
 */
internal fun chineseGrammar(morphemes: List<Morpheme>): List<GrammarMatch> =
    matchGrammar(morphemes, CHINESE_GRAMMAR_CATALOGUE, CHINESE_RULES, rulesFirst = true) { HskLevel.HSK1 }

private val CHINESE_RULES: List<GrammarRule> = listOf(::particleLe, ::shiDe, ::particleDe, ::verbNotVerb, ::reduplication, ::yiJiu)

/** Particles that may follow a sentence-final 了: 了吗, 了吧. */
private val FINAL_PARTICLES = setOf("吗", "呢", "吧", "啊", "呀", "啦", "嘛")

private fun endsClause(morpheme: Morpheme?) = morpheme == null || morpheme.pos == PUNCTUATION_TAG || morpheme.surface in FINAL_PARTICLES

private fun isVerb(morpheme: Morpheme?) = morpheme != null && morpheme.pos.startsWith("v")

/** 了 inside a sentence marks a completed action; at the end, a change or a new situation. */
private fun particleLe(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val le = morphemes[index]
    if (le.surface != "了" || !(le.pos.startsWith("u") || le.pos == "y")) return null
    if (!endsClause(morphemes.getOrNull(index + 1))) {
        return RuleHit(1, "V + 了 le", listOf(GrammarMeaning.ZH_LE_COMPLETED), HskLevel.HSK1)
    }
    val meanings =
        if (isVerb(morphemes.getOrNull(index - 1))) {
            listOf(GrammarMeaning.ZH_LE_CHANGE, GrammarMeaning.ZH_LE_COMPLETED)
        } else {
            listOf(GrammarMeaning.ZH_LE_CHANGE)
        }
    return RuleHit(1, "…了 le", meanings, HskLevel.HSK2)
}

private const val SHI_DE_REACH = 7

/** Where the 的 of a 是…的 sentence starting at [shi] is (a verb between, 的 at the end), or -1. */
private fun shiDeEnd(
    morphemes: List<Morpheme>,
    shi: Int,
): Int {
    if (morphemes[shi].surface != "是") return -1
    for (at in shi + 1..minOf(shi + SHI_DE_REACH, morphemes.lastIndex)) {
        val morpheme = morphemes[at]
        if (morpheme.pos == PUNCTUATION_TAG) return -1
        if (morpheme.surface == "的") {
            val verbBetween = (shi + 1 until at).any { isVerb(morphemes[it]) }
            return if (verbBetween && endsClause(morphemes.getOrNull(at + 1))) at else -1
        }
    }
    return -1
}

private fun shiDeHit() = RuleHit(1, "是…的 shì…de", listOf(GrammarMeaning.ZH_SHI_DE), HskLevel.HSK3)

/** 是 that opens a 是…的 sentence (我是昨天来的). */
private fun shiDe(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? = if (shiDeEnd(morphemes, index) >= 0) shiDeHit() else null

/** 的 that closes a 是…的 sentence, otherwise the ordinary 的 before a noun. */
private fun particleDe(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    if (morphemes[index].surface != "的") return null
    val closesShiDe = (maxOf(0, index - SHI_DE_REACH) until index).any { shiDeEnd(morphemes, it) == index }
    return if (closesShiDe) shiDeHit() else RuleHit(1, "的 de", listOf(GrammarMeaning.ZH_DE), HskLevel.HSK1)
}

private val NEGATIONS = setOf("不", "没")

/** 去不去, 是不是, 有没有, 喜不喜欢: a yes/no question made from the verb and its negative. */
private fun verbNotVerb(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val word = morphemes[index].surface
    val middle = morphemes.getOrNull(index + 1)?.surface
    val repeat = morphemes.getOrNull(index + 2)?.surface
    val length =
        when {
            word.length == 3 && word[0] == word[2] && word.substring(1, 2) in NEGATIONS -> 1
            middle in NEGATIONS && repeat == word && (isVerb(morphemes[index]) || morphemes[index].pos.startsWith("a")) -> 3
            word.length == 1 && middle == "不" && repeat != null && repeat.length == 2 && repeat[0] == word[0] -> 3
            else -> return null
        }
    return RuleHit(length, "V 不 V", listOf(GrammarMeaning.ZH_V_NOT_V), HskLevel.HSK2)
}

/** 看看, 看一看, 休息休息: a doubled verb, doing something a little. */
private fun reduplication(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val morpheme = morphemes[index]
    val word = morpheme.surface
    val next = morphemes.getOrNull(index + 1)?.surface
    val length =
        when {
            !isVerb(morpheme) && !(word.length == 3 && word[1] == '一') -> return null
            word.length == 2 && word[0] == word[1] -> 1
            word.length == 3 && word[0] == word[2] && word[1] == '一' -> 1
            word.length == 1 && next == "一" && morphemes.getOrNull(index + 2)?.surface == word -> 3
            word.length == 2 && next == word -> 2
            else -> return null
        }
    return RuleHit(length, "VV (看看)", listOf(GrammarMeaning.ZH_REDUPLICATION), HskLevel.HSK2)
}

private const val YI_JIU_REACH = 10

/** 一 + verb with 就 later in the sentence: as soon as… (我一到家就睡觉). */
private fun yiJiu(
    morphemes: List<Morpheme>,
    index: Int,
): RuleHit? {
    val word = morphemes[index].surface
    val joined = word.length == 2 && word[0] == '一' && isVerb(morphemes[index])
    val length =
        when {
            joined -> 1
            word == "一" && isVerb(morphemes.getOrNull(index + 1)) -> 2
            else -> return null
        }
    val rest = morphemes.drop(index + length).take(YI_JIU_REACH).takeWhile { it.surface !in setOf("。", "！", "？", ".", "!", "?") }
    return if (rest.any { it.surface == "就" }) RuleHit(length, "一…就 yī…jiù", listOf(GrammarMeaning.ZH_YI_JIU), HskLevel.HSK3) else null
}
