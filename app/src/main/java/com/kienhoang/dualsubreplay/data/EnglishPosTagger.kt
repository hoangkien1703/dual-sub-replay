package com.kienhoang.dualsubreplay.data

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.exp

/**
 * Apache OpenNLP's English part-of-speech model (Universal Dependencies English EWT, maxent, beam 3),
 * run without the OpenNLP library: its loader asks Android's XML parser for a feature that Android
 * rejects. The features below are the ones OpenNLP 2.5.4 uses with this model's
 * `generator.featuregen`, so the tags are the same. Tags are UD parts of speech (NOUN, VERB, AUX,
 * …); a contraction kept whole is tagged as its parts (`can't` AUX+PART).
 *
 * OpenNLP runs a beam of 3, but the model caches each word's features (`cache="true"`) from the
 * first, best sequence that reaches it, previous tags included. Every sequence at a word then gets
 * the same scores, so the beam always ends on the best tag after the best sequence so far: taking
 * the best tag word by word gives the same tags.
 */
internal class EnglishPosTagger private constructor(
    private val outcomes: Array<String>,
    private val parameters: Map<String, Context>,
) {
    /** One predicate's weights: the outcomes it has weights for, and those weights. */
    class Context(
        val outcomeIds: IntArray,
        val weights: DoubleArray,
    )

    /** The UD tag of each of [tokens]; empty for no tokens. */
    fun tag(tokens: List<String>): List<String> {
        val tags = ArrayList<String>(tokens.size)
        for (index in tokens.indices) {
            val scores = eval(features(tokens, index, tags))
            tags += outcomes[scores.indices.maxBy { scores[it] }]
        }
        return tags
    }

    private fun eval(features: List<String>): DoubleArray {
        val sums = DoubleArray(outcomes.size)
        for (feature in features) {
            val context = parameters[feature] ?: continue
            for (i in context.outcomeIds.indices) sums[context.outcomeIds[i]] += context.weights[i]
        }
        var total = 0.0
        for (i in sums.indices) {
            sums[i] = exp(sums[i])
            total += sums[i]
        }
        for (i in sums.indices) sums[i] /= total
        return sums
    }

    companion object {
        private const val AFFIX_LENGTH = 4
        private const val WINDOW = 2
        private const val MODEL_ENTRY = "pos.model"

        /** Reads the model from OpenNLP's model package (a zip holding `pos.model`). */
        fun load(input: InputStream): EnglishPosTagger {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: throw IOException("No $MODEL_ENTRY in the English model.")
                    if (entry.name == MODEL_ENTRY) return readGisModel(DataInputStream(zip.buffered()))
                }
            }
        }

        /** OpenNLP's binary GIS format: type, correction constants, outcomes, outcome patterns, predicates, weights. */
        private fun readGisModel(data: DataInputStream): EnglishPosTagger {
            if (data.readUTF() != "GIS") throw IOException("The English model is not a GIS model.")
            data.readInt()
            data.readDouble()
            val outcomes = Array(data.readInt()) { data.readUTF() }
            val patterns =
                Array(data.readInt()) {
                    data
                        .readUTF()
                        .split(' ')
                        .filter(String::isNotEmpty)
                        .map(String::toInt)
                        .toIntArray()
                }
            val predicates = Array(data.readInt()) { data.readUTF() }
            val parameters = HashMap<String, Context>(predicates.size * 2)
            var predicate = 0
            for (pattern in patterns) {
                val outcomeIds = pattern.copyOfRange(1, pattern.size)
                repeat(pattern[0]) {
                    parameters[predicates[predicate++]] = Context(outcomeIds, DoubleArray(outcomeIds.size) { data.readDouble() })
                }
            }
            return EnglishPosTagger(outcomes, parameters)
        }

        /** OpenNLP's features for [index]: prior, affixes, ±2-word window with sentence start, token class with word, previous tags. */
        internal fun features(
            tokens: List<String>,
            index: Int,
            previousTags: List<String>,
        ): List<String> {
            val features = ArrayList<String>(40)
            features += "def"
            val token = tokens[index]
            for (length in 1..minOf(AFFIX_LENGTH, token.length)) features += "suf=" + token.substring(token.length - length)
            for (length in 1..minOf(AFFIX_LENGTH, token.length)) features += "pre=" + token.substring(0, length)
            features += "w=" + lowercase(token)
            for (offset in 1..WINDOW) if (index - offset >= 0) features += "p${offset}w=" + lowercase(tokens[index - offset])
            for (offset in 1..WINDOW) if (index + offset < tokens.size) features += "n${offset}w=" + lowercase(tokens[index + offset])
            if (index == 0) features += "S=begin"
            for (offset in 1..WINDOW) if (index - offset == 0) features += "p${offset}S=begin"
            val tokenClass = tokenClass(token)
            features += "wc=$tokenClass"
            features += "w&c=" + lowercase(token) + "," + tokenClass
            if (index >= 1) {
                features += "t=" + previousTags[index - 1]
                if (index >= 2) features += "t2=" + previousTags[index - 2] + "," + previousTags[index - 1]
            }
            return features
        }

        private fun lowercase(text: String): String {
            val codePoints = text.codePoints().map(Character::toLowerCase).toArray()
            return String(codePoints, 0, codePoints.size)
        }

        /** OpenNLP's token class (`FeatureGeneratorUtil.tokenFeature`). */
        internal fun tokenClass(token: String): String {
            val shape = TokenShape.of(token)
            return when {
                shape.allHiragana -> "jah"
                shape.allKatakana -> "jak"
                shape.allLowercase -> "lc"
                shape.digits == 2 -> "2d"
                shape.digits == 4 -> "4d"
                shape.hasDigit -> numberClass(shape)
                shape.allCapitals -> if (token.length == 1) "sc" else "ac"
                CAPITAL_PERIOD.containsMatchIn(token) -> "cp"
                shape.initialCapital -> "ic"
                else -> "other"
            }
        }

        private fun numberClass(shape: TokenShape): String =
            when {
                shape.hasLetter -> "an"
                shape.hasHyphen -> "dd"
                shape.hasSlash -> "ds"
                shape.hasComma -> "dc"
                shape.hasPeriod -> "dp"
                else -> "num"
            }

        private val CAPITAL_PERIOD = Regex("^[A-ZÄÖÜ]\\.$")
    }
}

/** OpenNLP's `StringPattern`: what a token is made of. */
private class TokenShape(
    val initialCapital: Boolean,
    val allCapitals: Boolean,
    val allLowercase: Boolean,
    val allHiragana: Boolean,
    val allKatakana: Boolean,
    val digits: Int,
    val hasDigit: Boolean,
    val hasLetter: Boolean,
    val hasHyphen: Boolean,
    val hasSlash: Boolean,
    val hasComma: Boolean,
    val hasPeriod: Boolean,
) {
    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun of(token: String): TokenShape {
            var initialCapital = false
            var allCapitals = true
            var allLowercase = true
            var hiragana = true
            var katakana = true
            var digits = 0
            var letter = false
            val punctuation = mutableSetOf<Char>()
            token.forEachIndexed { index, ch ->
                val type = Character.getType(ch)
                if (type.toByte() in LETTER_TYPES) {
                    letter = true
                    if (type.toByte() == Character.UPPERCASE_LETTER) {
                        if (index == 0) initialCapital = true
                        allLowercase = false
                    } else {
                        allCapitals = false
                    }
                } else {
                    allCapitals = false
                    allLowercase = false
                    if (type.toByte() == Character.DECIMAL_DIGIT_NUMBER) {
                        digits++
                        hiragana = false
                        katakana = false
                    }
                    if (ch in ",./-") punctuation += ch
                }
                when (Character.UnicodeScript.of(token.codePointAt(index))) {
                    Character.UnicodeScript.COMMON ->
                        if (ch != '・' && ch != 'ー' && ch != '〜') {
                            hiragana = false
                            katakana = false
                        }
                    Character.UnicodeScript.LATIN -> {
                        hiragana = false
                        katakana = false
                    }
                    Character.UnicodeScript.HAN -> {
                        hiragana = false
                        katakana = false
                        allLowercase = false
                    }
                    Character.UnicodeScript.HIRAGANA -> {
                        katakana = false
                        allLowercase = false
                    }
                    Character.UnicodeScript.KATAKANA -> {
                        hiragana = false
                        allLowercase = false
                    }
                    else -> Unit
                }
            }
            return TokenShape(
                initialCapital = initialCapital,
                allCapitals = allCapitals,
                allLowercase = allLowercase,
                allHiragana = hiragana,
                allKatakana = katakana,
                digits = digits,
                hasDigit = digits > 0,
                hasLetter = letter,
                hasHyphen = '-' in punctuation,
                hasSlash = '/' in punctuation,
                hasComma = ',' in punctuation,
                hasPeriod = '.' in punctuation,
            )
        }

        private val LETTER_TYPES =
            setOf(
                Character.UPPERCASE_LETTER,
                Character.LOWERCASE_LETTER,
                Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER,
                Character.OTHER_LETTER,
            )
    }
}
