package com.kienhoang.dualsubreplay.data

import java.util.Locale

/**
 * Grammatical roles for Word Learning Mode (Issue #42 V1-V5).
 */
enum class PartOfSpeech(val label: String, val colorHex: Long) {
    NOUN("Noun", 0xFF4FC3F7),           // Soft Sky Blue
    VERB("Verb", 0xFF81C784),           // Soft Emerald Green
    ADJECTIVE("Adjective", 0xFFFFB74D), // Soft Amber / Gold
    ADVERB("Adverb", 0xFFBA68C8),       // Soft Lavender / Purple
    PRONOUN("Pronoun", 0xFF4DD0E1),     // Soft Teal / Cyan
    CONJUNCTION("Conjunction", 0xFFFF8A65), // Soft Warm Coral
    PREPOSITION("Preposition", 0xFFF06292), // Soft Rose Pink
    PARTICLE("Particle / Grammar", 0xFFE57373), // Soft Rose
    UNALIGNED("Unaligned / Idiom", 0xFF78909C), // Dark Slate Gray (cannot be translated word-to-word)
    OTHER("Other", 0xFFCFD8DC);        // Neutral Light

    companion object {
        fun fromKey(key: String): PartOfSpeech = entries.firstOrNull {
            it.name.equals(key, ignoreCase = true)
        } ?: OTHER
    }
}

/**
 * An individual token extracted from subtitle text with timing and grammatical metadata.
 */
data class AnalyzedToken(
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val partOfSpeech: PartOfSpeech,
    val reading: String? = null,
    val baseForm: String? = null,
)

/**
 * Morphological and language-aware tokenizer for Japanese, CJK, and European/Latin texts.
 * Enables accurate word-level timings in non-spaced languages (Issue #44) and
 * POS-colored word learning (Issue #42).
 */
object LanguageAwareTokenizer {

    private val CJK_REGEX = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF]")
    private val KANJI_REGEX = Regex("[\\u4E00-\\u9FAF]")
    private val HIRAGANA_REGEX = Regex("[\\u3040-\\u309F]")
    private val KATAKANA_REGEX = Regex("[\\u30A0-\\u30FF]")

    // Common Japanese particles and auxiliary grammar words
    private val JAPANESE_PARTICLES = setOf(
        "は", "が", "を", "に", "で", "へ", "と", "も", "から", "まで",
        "より", "の", "ね", "よ", "か", "な", "ぞ", "ぜ", "わ", "て", "ば"
    )

    // Common Japanese auxiliary verbs and verb endings
    private val JAPANESE_VERB_ENDINGS = listOf(
        "ている", "ていた", "てある", "ておく", "ていく", "てくる",
        "ます", "ました", "ません", "たい", "たくない", "たかった",
        "られる", "させる", "ない", "なかった",
        "る", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む", "う"
    )

    // Common Japanese adjectives (ending in い or な)
    private val JAPANESE_ADJECTIVES = setOf(
        "いい", "よい", "すごい", "楽しい", "嬉しい", "美しい", "美味しい", "おいしい",
        "大きい", "小さい", "新しい", "古い", "高い", "安い", "難しい", "やさしい", "優しい",
        "好き", "嫌い", "綺麗", "きれい", "静か", "元気", "大切", "大丈夫"
    )

    // English closed-class words for POS heuristics
    private val ENGLISH_ARTICLES = setOf("the", "a", "an")

    private val ENGLISH_CONJUNCTIONS = setOf(
        "and", "or", "but", "because", "both", "if", "so", "than", "until", "while", "as",
        "though", "although", "either", "neither", "since", "unless"
    )

    private val ENGLISH_PREPOSITIONS = setOf(
        "in", "on", "at", "by", "for", "with", "about", "against", "between",
        "into", "through", "during", "before", "after", "above", "below", "to",
        "from", "up", "down", "out", "off", "over", "under", "of", "via", "onto"
    )

    private val ENGLISH_PRONOUNS = setOf(
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us",
        "them", "my", "your", "his", "her", "its", "our", "their", "mine", "yours",
        "hers", "ours", "theirs", "this", "that", "these", "those", "what", "which",
        "who", "whom", "whose", "myself", "yourself", "himself", "herself", "itself"
    )

    private val ENGLISH_COMMON_ADVERBS = setOf(
        "actually", "really", "very", "too", "quickly", "possibly", "now", "then",
        "here", "there", "again", "once", "just", "always", "never", "often", "sometimes",
        "soon", "almost", "quite", "already", "well", "away", "back", "still", "also"
    )

    // Vietnamese lexical sets for cross-language alignment and POS tagging (Issue #42 V4 & V5)
    private val VIETNAMESE_CONJUNCTIONS = setOf(
        "và", "hoặc", "nhưng", "bởi vì", "vì", "nếu", "mà", "thì", "cả", "tuy", "cho nên"
    )

    private val VIETNAMESE_PREPOSITIONS = setOf(
        "trong", "trên", "tại", "ở", "với", "cho", "từ", "đến", "về", "để", "theo", "giữa"
    )

    private val VIETNAMESE_PRONOUNS = setOf(
        "tôi", "bạn", "chúng tôi", "chúng ta", "nó", "họ", "anh", "chị", "em", "ông", "bà", "mình",
        "này", "đó", "kia", "ai", "gì", "nào"
    )

    private val VIETNAMESE_COMMON_VERBS = setOf(
        "bao gồm", "gồm", "nói", "làm", "biết", "đi", "có", "lấy", "hiểu", "thấy", "nghĩ", "thử",
        "xem", "đến", "muốn", "tìm", "hỏi", "đặt", "giữ", "yêu", "thích"
    )

    private val VIETNAMESE_COMMON_ADJECTIVES = setOf(
        "thật", "hỗn loạn", "quan trọng", "nhiều", "ít", "tốt", "mới", "cũ", "lớn", "nhỏ",
        "nhanh", "chậm", "khó", "dễ", "đẹp", "cao", "thấp", "đúng", "sai"
    )

    // Words that are grammatical filler, auxiliary particles, or do not have a 1:1 direct word translation (Dark Gray)
    private val VIETNAMESE_UNALIGNED_MARKERS = setOf(
        "đã", "đang", "sẽ", "được", "bị", "các", "những", "cái", "chiếc", "rất", "quá", "lắm",
        "ạ", "nhé", "nha", "đâu", "chứ"
    )

    private val ENGLISH_COMMON_VERBS = setOf(
        "is", "am", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having",
        "do", "does", "did", "doing",
        "say", "says", "said", "saying",
        "get", "gets", "got", "getting",
        "make", "makes", "made", "making",
        "go", "goes", "went", "going", "gone",
        "know", "knows", "knew", "knowing", "known",
        "take", "takes", "took", "taking", "taken",
        "see", "sees", "saw", "seeing", "seen",
        "come", "comes", "came", "coming",
        "think", "thinks", "thought", "thinking",
        "look", "looks", "looked", "looking",
        "want", "wants", "wanted", "wanting",
        "give", "gives", "gave", "giving", "given",
        "use", "uses", "used", "using",
        "find", "finds", "found", "finding",
        "tell", "tells", "told", "telling",
        "ask", "asks", "asked", "asking",
        "work", "works", "worked", "working",
        "seem", "seems", "seemed", "seeming",
        "feel", "feels", "felt", "feeling",
        "try", "tries", "tried", "trying",
        "leave", "leaves", "left", "leaving",
        "call", "calls", "called", "calling"
    )

    private val ENGLISH_COMMON_ADJECTIVES = setOf(
        "good", "new", "first", "last", "long", "great", "little", "own", "other", "old",
        "right", "big", "high", "different", "small", "large", "next", "early", "young",
        "important", "few", "public", "bad", "same", "able", "quick", "brown", "lazy",
        "hot", "cold", "fast", "slow", "easy", "hard", "simple", "complex", "bright", "dark"
    )

    fun isCjk(text: String): Boolean = CJK_REGEX.containsMatchIn(text)

    /**
     * Tokenizes [text] into analyzed tokens with start/end indices and PartOfSpeech tags.
     */
    fun tokenize(text: String, languageCode: String? = null): List<AnalyzedToken> {
        if (text.isBlank()) return emptyList()
        val isJapaneseOrCjk = (languageCode != null && (languageCode.startsWith("ja") || languageCode.startsWith("zh")))
            || isCjk(text)

        return if (isJapaneseOrCjk) {
            tokenizeJapanese(text)
        } else {
            tokenizeSpacedLanguage(text)
        }
    }

    /**
     * Splits Japanese text into morphemic chunks by script boundaries
     * (Kanji compounds, Katakana loanwords, Hiragana verb inflections & particles).
     */
    private fun tokenizeJapanese(text: String): List<AnalyzedToken> {
        val tokens = mutableListOf<AnalyzedToken>()
        var cursor = 0
        val length = text.length

        while (cursor < length) {
            val ch = text[cursor]

            // Skip whitespace & Western punctuation
            if (ch.isWhitespace() || ch in " \t\r\n.,!?;:\"'()[]{}") {
                cursor++
                continue
            }

            // Japanese punctuation
            if (ch in "、。！？「」…・ー〜") {
                tokens += AnalyzedToken(
                    text = ch.toString(),
                    startIndex = cursor,
                    endIndex = cursor + 1,
                    partOfSpeech = PartOfSpeech.OTHER,
                )
                cursor++
                continue
            }

            // Katakana loanwords (typically nouns)
            if (isKatakana(ch)) {
                val start = cursor
                while (cursor < length && (isKatakana(text[cursor]) || text[cursor] == 'ー')) {
                    cursor++
                }
                tokens += AnalyzedToken(
                    text = text.substring(start, cursor),
                    startIndex = start,
                    endIndex = cursor,
                    partOfSpeech = PartOfSpeech.NOUN,
                )
                continue
            }

            // Kanji words or Kanji+Hiragana verb/adjective compounds
            if (isKanji(ch)) {
                val start = cursor
                while (cursor < length && isKanji(text[cursor])) {
                    cursor++
                }
                // Check if followed by Hiragana okurigana (e.g. 食べる, 行きます, 楽しい)
                val firstChar = if (cursor < length) text[cursor].toString() else ""
                val firstTwoChars = if (cursor + 2 <= length) text.substring(cursor, cursor + 2) else ""
                val startsWithParticle = firstTwoChars in JAPANESE_PARTICLES || firstChar in JAPANESE_PARTICLES
                if (!startsWithParticle) {
                    val hiraganaStart = cursor
                    while (cursor < length && isHiragana(text[cursor])) {
                        if (cursor > hiraganaStart && JAPANESE_PARTICLES.contains(text[cursor].toString())) {
                            break
                        }
                        cursor++
                    }
                }
                val wordText = text.substring(start, cursor)
                val pos = classifyJapaneseWord(wordText)
                tokens += AnalyzedToken(
                    text = wordText,
                    startIndex = start,
                    endIndex = cursor,
                    partOfSpeech = pos,
                )
                continue
            }

            // Hiragana segments (particles, auxiliaries, standalone words)
            if (isHiragana(ch)) {
                val start = cursor
                // Check if it matches a 2-char particle (から, まで, より)
                if (cursor + 2 <= length && text.substring(start, start + 2) in JAPANESE_PARTICLES) {
                    cursor += 2
                    tokens += AnalyzedToken(
                        text = text.substring(start, cursor),
                        startIndex = start,
                        endIndex = cursor,
                        partOfSpeech = PartOfSpeech.PARTICLE,
                    )
                    continue
                }
                // 1-char particle
                if (ch.toString() in JAPANESE_PARTICLES) {
                    cursor++
                    tokens += AnalyzedToken(
                        text = ch.toString(),
                        startIndex = start,
                        endIndex = cursor,
                        partOfSpeech = PartOfSpeech.PARTICLE,
                    )
                    continue
                }

                // Hiragana word/adverb/verb segment
                while (cursor < length && isHiragana(text[cursor])) {
                    if (cursor > start && text[cursor].toString() in JAPANESE_PARTICLES) break
                    cursor++
                }
                val hText = text.substring(start, cursor)
                tokens += AnalyzedToken(
                    text = hText,
                    startIndex = start,
                    endIndex = cursor,
                    partOfSpeech = classifyJapaneseWord(hText),
                )
                continue
            }

            // Fallback for Latin / numbers inside Japanese text
            val start = cursor
            while (cursor < length && !isCjk(text[cursor].toString()) && !text[cursor].isWhitespace() && text[cursor] !in "、。！？") {
                cursor++
            }
            tokens += AnalyzedToken(
                text = text.substring(start, cursor),
                startIndex = start,
                endIndex = cursor,
                partOfSpeech = PartOfSpeech.NOUN,
            )
        }

        return tokens
    }

    private fun isKanji(ch: Char): Boolean = ch.code in 0x4E00..0x9FAF
    private fun isHiragana(ch: Char): Boolean = ch.code in 0x3040..0x309F
    private fun isKatakana(ch: Char): Boolean = ch.code in 0x30A0..0x30FF

    private fun classifyJapaneseWord(word: String): PartOfSpeech {
        if (word in JAPANESE_PARTICLES) return PartOfSpeech.PARTICLE
        if (word in JAPANESE_ADJECTIVES) return PartOfSpeech.ADJECTIVE
        if (word.endsWith("い") && word.length > 1) return PartOfSpeech.ADJECTIVE
        if (JAPANESE_VERB_ENDINGS.any { word.endsWith(it) }) return PartOfSpeech.VERB
        return PartOfSpeech.NOUN
    }

    /**
     * Splits spaced/European languages into words while tracking exact string indices and POS.
     */
    private fun tokenizeSpacedLanguage(text: String): List<AnalyzedToken> {
        val tokens = mutableListOf<AnalyzedToken>()
        val regex = Regex("\\b[\\w'-]+\\b")
        regex.findAll(text).forEach { match ->
            val word = match.value
            val cleanWord = word.lowercase(Locale.ROOT)
            val pos = classifyEnglishWord(cleanWord, word)
            tokens += AnalyzedToken(
                text = word,
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                partOfSpeech = pos,
            )
        }
        return tokens
    }

    private fun classifyEnglishWord(lower: String, original: String): PartOfSpeech {
        if (lower in ENGLISH_ARTICLES) return PartOfSpeech.PARTICLE
        if (lower in ENGLISH_CONJUNCTIONS) return PartOfSpeech.CONJUNCTION
        if (lower in ENGLISH_PREPOSITIONS) return PartOfSpeech.PREPOSITION
        if (lower in ENGLISH_PRONOUNS) return PartOfSpeech.PRONOUN
        if (lower in ENGLISH_COMMON_ADVERBS) return PartOfSpeech.ADVERB
        if (lower in ENGLISH_COMMON_ADJECTIVES) return PartOfSpeech.ADJECTIVE
        if (lower in ENGLISH_COMMON_VERBS) return PartOfSpeech.VERB

        // Suffix heuristics
        if (lower.endsWith("ly") && lower.length > 3) return PartOfSpeech.ADVERB
        if (lower.endsWith("ing") || lower.endsWith("ed") || lower.endsWith("ize") || lower.endsWith("ify")) {
            return PartOfSpeech.VERB
        }
        if (lower.endsWith("ful") || lower.endsWith("less") || lower.endsWith("able") ||
            lower.endsWith("ive") || lower.endsWith("ous") || lower.endsWith("al")) {
            return PartOfSpeech.ADJECTIVE
        }
        if (lower.endsWith("tion") || lower.endsWith("ment") || lower.endsWith("ness") ||
            lower.endsWith("ity") || lower.endsWith("er") || lower.endsWith("or")) {
            return PartOfSpeech.NOUN
        }

        return PartOfSpeech.NOUN
    }

    /**
     * Splits spaced text into word tokens with accurate character bounds.
     */
    fun tokenizeWordsWithOffsets(text: String): List<AnalyzedToken> {
        val tokens = mutableListOf<AnalyzedToken>()
        val regex = Regex("[\\p{L}\\p{M}\\p{N}'-]+")
        regex.findAll(text).forEach { match ->
            tokens += AnalyzedToken(
                text = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                partOfSpeech = PartOfSpeech.OTHER,
            )
        }
        return tokens
    }

    /**
     * Aligns translated subtitle text with the original sentence's tokens (Issue #42 V4 & V5).
     * Matching words share the original grammatical POS colors. Words that have no direct
     * word-for-word counterpart or represent grammatical filler/aspect markers are colored
     * in dark gray (PartOfSpeech.UNALIGNED).
     */
    fun alignAndTokenizeTranslation(
        translationText: String,
        originalTokens: List<AnalyzedToken>,
        translationLanguage: String? = null,
    ): List<AnalyzedToken> {
        if (translationText.isBlank()) return emptyList()
        val isCjk = isCjk(translationText) || (translationLanguage != null && (translationLanguage.startsWith("ja") || translationLanguage.startsWith("zh")))

        val rawTokens = if (isCjk) {
            tokenizeJapanese(translationText)
        } else {
            tokenizeWordsWithOffsets(translationText)
        }

        if (originalTokens.isEmpty()) {
            return rawTokens
        }

        val originalMeaningfulTokens = originalTokens.filter {
            it.partOfSpeech != PartOfSpeech.OTHER
        }

        val totalRaw = rawTokens.size
        val totalOrig = originalMeaningfulTokens.size
        val result = mutableListOf<AnalyzedToken>()

        rawTokens.forEachIndexed { index, token ->
            val lower = token.text.lowercase(Locale.ROOT)

            // 1. Untranslatable auxiliary / filler marker -> Dark Gray
            if (lower in VIETNAMESE_UNALIGNED_MARKERS) {
                result += token.copy(partOfSpeech = PartOfSpeech.UNALIGNED)
                return@forEachIndexed
            }

            // 2. Direct literal / transliteration match with original word
            val directMatch = originalTokens.find { orig ->
                orig.text.equals(token.text, ignoreCase = true) ||
                orig.text.lowercase(Locale.ROOT).contains(lower) ||
                lower.contains(orig.text.lowercase(Locale.ROOT))
            }
            if (directMatch != null) {
                result += token.copy(partOfSpeech = directMatch.partOfSpeech)
                return@forEachIndexed
            }

            // 3. Known lexical classification for target language
            val knownPos = classifyVietnameseWord(lower)
            if (knownPos != null) {
                result += token.copy(partOfSpeech = knownPos)
                return@forEachIndexed
            }

            // 4. Positional sequence alignment to corresponding original word
            if (totalOrig > 0 && totalRaw > 0) {
                val origIndex = ((index.toFloat() / totalRaw) * totalOrig).toInt().coerceIn(0, totalOrig - 1)
                val alignedOrig = originalMeaningfulTokens[origIndex]
                result += token.copy(partOfSpeech = alignedOrig.partOfSpeech)
            } else {
                result += token.copy(partOfSpeech = PartOfSpeech.UNALIGNED)
            }
        }

        return result
    }

    private fun classifyVietnameseWord(lower: String): PartOfSpeech? {
        if (lower in VIETNAMESE_CONJUNCTIONS) return PartOfSpeech.CONJUNCTION
        if (lower in VIETNAMESE_PREPOSITIONS) return PartOfSpeech.PREPOSITION
        if (lower in VIETNAMESE_PRONOUNS) return PartOfSpeech.PRONOUN
        if (lower in VIETNAMESE_COMMON_VERBS) return PartOfSpeech.VERB
        if (lower in VIETNAMESE_COMMON_ADJECTIVES) return PartOfSpeech.ADJECTIVE
        return null
    }
}
