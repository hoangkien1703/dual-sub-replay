package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * English words tagged in context with OpenNLP's part-of-speech model, with dictionary forms and
 * word levels from [EnglishLexicon] (all bundled in the APK). The data loads on a background
 * thread the first time English is shown; until then [analyze] and [morphemes] return null and
 * callers use their heuristic.
 */
internal object EnglishMorphology {
    private val analyzer =
        BackgroundLoaded("english-analyzer") {
            EnglishAnalyzer(LearnerData.open("en-pos.zip").use(EnglishPosTagger::load), loadEnglishLexicon())
        }

    val revision: StateFlow<Int> get() = analyzer.revision

    fun warmUp() = analyzer.warmUp()

    fun awaitReady(timeoutMs: Long = 60_000): Boolean = analyzer.awaitReady(timeoutMs)

    /** Learner words of [text] with their parts of speech and dictionary forms, or null while the data loads. */
    fun analyze(text: String): List<AnalyzedToken>? = analyzer.get()?.let { englishWords(it, text) }

    /** The pieces grammar is read from (contractions split, punctuation kept), or null while the data loads. */
    fun morphemes(text: String): List<Morpheme>? = analyzer.get()?.let { englishMorphemes(it, text) }

    /** The CEFR-J level of a word from [analyze], or null for unlisted words, contractions and while loading. */
    fun wordLevel(token: AnalyzedToken): CefrLevel? = analyzer.get()?.let { englishWordLevel(it.lexicon, token) }
}

/** What English analysis needs: the tagger and the dictionary forms and levels. */
internal class EnglishAnalyzer(
    val tagger: EnglishPosTagger,
    val lexicon: EnglishLexicon,
)

internal fun loadEnglishLexicon(): EnglishLexicon =
    LearnerData.open("en-words.txt").use { words ->
        LearnerData.open("en-exceptions.txt").use { exceptions ->
            LearnerData.open("en-levels.txt").use { levels -> EnglishLexicon.load(words, exceptions, levels) }
        }
    }

/** A word's level by its dictionary form and part of speech; contractions and possessives have none. */
internal fun englishWordLevel(
    lexicon: EnglishLexicon,
    token: AnalyzedToken,
): CefrLevel? {
    val lower = token.text.lowercase(Locale.ROOT)
    if ('\'' in lower || '\u2019' in lower) return null
    val part =
        when (token.partOfSpeech) {
            PartOfSpeech.NOUN -> 'n'
            PartOfSpeech.VERB -> 'v'
            PartOfSpeech.ADJECTIVE -> 'a'
            PartOfSpeech.ADVERB -> 'r'
            else -> 'f'
        }
    return lexicon.level(token.baseForm ?: lower, part)
}

/** A word or punctuation mark of an English line and its UD tag (`AUX+PART` for a whole contraction). */
internal data class TaggedEnglishToken(
    val text: String,
    val start: Int,
    val tag: String,
    val isWord: Boolean,
)

/** Same words as the spaced-language tokenizer, plus each punctuation mark the model expects to see. */
private val ENGLISH_TOKEN = Regex("([\\p{L}\\p{M}\\p{N}_]+(?:['’-]+[\\p{L}\\p{M}\\p{N}_]+)*)|[^\\s\\p{L}\\p{M}\\p{N}_]")

internal fun tagEnglish(
    tagger: EnglishPosTagger,
    text: String,
): List<TaggedEnglishToken> {
    val matches = ENGLISH_TOKEN.findAll(text).toList()
    val tags = tagger.tag(matches.map { it.value.replace('’', '\'') })
    return matches.mapIndexed { index, match ->
        TaggedEnglishToken(match.value, match.range.first, tags[index], match.groups[1] != null)
    }
}

/** Words colored by their first piece's tag, so a verb after a helper or "going to" shows as a verb. */
internal fun englishWords(
    analyzer: EnglishAnalyzer,
    text: String,
): List<AnalyzedToken> {
    val tokens = tagEnglish(analyzer.tagger, text)
    val morphemes = englishMorphemesOf(tokens, analyzer.lexicon)
    return tokens.filter { it.isWord }.map { token ->
        val parts = morphemes.filter { it.start >= token.start && it.start < token.start + token.text.length }
        val head = parts.firstOrNull()
        val lower = token.text.lowercase(Locale.ROOT).replace('’', '\'')
        AnalyzedToken(
            text = token.text,
            startIndex = token.start,
            endIndex = token.start + token.text.length,
            partOfSpeech = englishPartOfSpeech(head?.pos ?: token.tag.substringBefore('+'), lower),
            baseForm = head?.baseForm?.takeIf { parts.size == 1 && it != lower && it != "*" },
        )
    }
}

internal fun englishMorphemes(
    analyzer: EnglishAnalyzer,
    text: String,
): List<Morpheme> = englishMorphemesOf(tagEnglish(analyzer.tagger, text), analyzer.lexicon)

/** The app's part of speech for a UD tag; determiners are grammar words, except this/that/these/those. */
internal fun englishPartOfSpeech(
    tag: String,
    lowerWord: String,
): PartOfSpeech =
    when (tag) {
        "NOUN", "PROPN" -> PartOfSpeech.NOUN
        "VERB", "AUX" -> PartOfSpeech.VERB
        "ADJ" -> PartOfSpeech.ADJECTIVE
        "ADV" -> PartOfSpeech.ADVERB
        "PRON" -> PartOfSpeech.PRONOUN
        "DET" -> if (lowerWord in DEMONSTRATIVES) PartOfSpeech.PRONOUN else PartOfSpeech.PARTICLE
        "ADP" -> PartOfSpeech.PREPOSITION
        "CCONJ", "SCONJ" -> PartOfSpeech.CONJUNCTION
        "PART" -> PartOfSpeech.PARTICLE
        else -> PartOfSpeech.OTHER
    }

private val DEMONSTRATIVES = setOf("this", "that", "these", "those")

/**
 * The pieces grammar patterns read: lowercase surface, UD tag ([Morpheme.pos]), form
 * ([Morpheme.conjugationForm] and [Morpheme.detail]: BASE, PRES, S3, PAST, PP, ING, MODAL, COMP,
 * SUP, LY, PL or `*`) and base form. A contraction is split into its parts (can't → ca + n't,
 * I'm → i + 'm) so its helper verb counts.
 */
internal fun englishMorphemesOf(
    tokens: List<TaggedEnglishToken>,
    lexicon: EnglishLexicon,
): List<Morpheme> {
    val pieces = fixVerbsAfterHelpers(tokens.flatMap(::splitContraction))
    val lemmas = pieces.mapIndexed { index, piece -> englishLemma(lexicon, piece, pieces, index) }
    return pieces.mapIndexed { index, piece ->
        val lemma = lemmas[index]
        val form = englishForm(piece, lemma, pieces, index)
        Morpheme(
            surface = piece.text,
            start = piece.start,
            pos = piece.tag,
            detail = form,
            conjugationForm = form,
            baseForm = lemma,
        )
    }
}

/** One piece of a token: its lowercase text, where it starts, and its single UD tag. */
private data class Piece(
    val text: String,
    val start: Int,
    val tag: String,
)

/** Endings split off a word, longest first, with the tag each has when the model gave only one. */
private val CONTRACTION_ENDINGS =
    listOf(
        "n't" to "PART",
        "'re" to "AUX",
        "'ve" to "AUX",
        "'ll" to "AUX",
        "'s" to "AUX",
        "'d" to "AUX",
        "'m" to "AUX",
    )

/** Informal forms written as one word: gonna = gon + na (going to). */
private val FUSED_WORDS = mapOf("gonna" to 3, "wanna" to 3, "gotta" to 3, "cannot" to 3)

private fun splitContraction(token: TaggedEnglishToken): List<Piece> {
    val lower = lowercaseSameLength(token.text).replace('\u2019', '\'')
    val tags = token.tag.split('+')
    val cut =
        FUSED_WORDS[lower]
            ?: CONTRACTION_ENDINGS
                .firstOrNull { (ending, _) ->
                    lower.endsWith(ending) && lower.length > ending.length
                }?.let { (ending, _) ->
                    lower.length - ending.length
                }
    if (!token.isWord || cut == null) return listOf(Piece(lower, token.start, tags[0]))
    val base = lower.substring(0, cut)
    val ending = lower.substring(cut)
    // The model tags a few whole contractions oddly (I'd as SCONJ); the pronoun part is a pronoun.
    val baseTag = if (tags.size == 1 && base in SUBJECT_PRONOUNS) "PRON" else tags[0]
    val endingTag =
        when {
            tags.size > 1 -> tags[1]
            ending != "'s" -> CONTRACTION_ENDINGS.firstOrNull { it.first == ending }?.second ?: "PART"
            base == "let" -> "PRON"
            base in IS_CONTRACTIONS -> "AUX"
            // A possessive stays part of its noun: Mary's, the teacher's.
            else -> return listOf(Piece(lower, token.start, tags[0]))
        }
    return listOf(Piece(base, token.start, baseTag), Piece(ending, token.start + cut, endingTag))
}

private val SUBJECT_PRONOUNS = setOf("i", "you", "he", "she", "it", "we", "they")

/** Words before "to" that are followed by a verb: going to, ought to, used to, want to. */
private val INFINITIVE_LEADS =
    setOf(
        "going",
        "gon",
        "ought",
        "used",
        "able",
        "supposed",
        "want",
        "wants",
        "wanted",
        "need",
        "needs",
        "needed",
        "have",
        "has",
        "had",
        "got",
        "try",
        "tries",
        "tried",
        "trying",
    )

/** Places after "going to" that are nouns even though they look like verbs: going to school, to work. */
private val GOING_TO_PLACES =
    setOf(
        "school",
        "work",
        "bed",
        "church",
        "class",
        "college",
        "university",
        "hospital",
        "jail",
        "prison",
        "town",
        "court",
        "war",
        "sea",
        "dinner",
        "lunch",
        "breakfast",
        "market",
        "office",
    )

private val NOT_A_VERB_TAGS = setOf("NOUN", "ADJ")

/**
 * The model often tags a verb that is also a noun as a noun after a helper (it may rain, you'd
 * better hurry) or after "to" (going to rain, ought to apologize). After a modal, and after "to"
 * that follows going, ought, used, want, need, have or got, such a word is the verb.
 */
private fun fixVerbsAfterHelpers(pieces: List<Piece>): List<Piece> {
    val fixed = pieces.toMutableList()
    for (index in fixed.indices) {
        val piece = fixed[index]
        if (piece.tag == "AUX" && (piece.text in MODALS || piece.text == "'d")) {
            val next =
                (index + 1 until fixed.size).firstOrNull { fixed[it].tag != "ADV" && fixed[it].text !in WORDS_BEFORE_VERB } ?: continue
            if (fixed[next].tag in NOT_A_VERB_TAGS) fixed[next] = fixed[next].copy(tag = "VERB")
        }
        val lead = fixed.getOrNull(index - 1)?.text
        val verb = fixed.getOrNull(index + 1)
        val accustomed =
            lead == "used" &&
                fixed.getOrNull(index - 2)?.let { spelledVerbLemma(CONTRACTED_LEMMAS[it.text] ?: it.text) in setOf("be", "get") } == true
        if (piece.text == "to" && lead in INFINITIVE_LEADS && !accustomed && verb != null && verb.tag in NOT_A_VERB_TAGS) {
            if (lead == "going" && verb.text in GOING_TO_PLACES) continue
            fixed[index] = piece.copy(tag = "PART")
            fixed[index + 1] = verb.copy(tag = "VERB")
        }
    }
    return fixed
}

/** Words between a helper and its verb: not, and better / rather after 'd. */
private val WORDS_BEFORE_VERB = setOf("not", "n't", "better", "rather")

/** Words whose 's is "is" or "has" rather than a possessive when the model gave one tag. */
private val IS_CONTRACTIONS = setOf("it", "that", "what", "there", "here", "he", "she", "who", "where", "how", "when", "why", "this")

/** Lowercase that keeps the length, so offsets still point into the original line. */
private fun lowercaseSameLength(text: String): String = text.lowercase(Locale.ROOT).takeIf { it.length == text.length } ?: text

private val CONTRACTED_LEMMAS =
    mapOf(
        "n't" to "not",
        "'re" to "be",
        "'m" to "be",
        "'ve" to "have",
        "'ll" to "will",
        "ca" to "can",
        "wo" to "will",
        "sha" to "shall",
        "ai" to "be",
        "gon" to "go",
        "wan" to "want",
        "na" to "to",
        "ta" to "to",
        "not" to "not",
    )

private fun englishLemma(
    lexicon: EnglishLexicon,
    piece: Piece,
    pieces: List<Piece>,
    index: Int,
): String {
    val word = piece.text
    if (piece.tag == "PUNCT") return word
    CONTRACTED_LEMMAS[word]?.let { return it }
    if (word == "'d") return if (nextVerbForm(pieces, index) == "PP") "have" else "would"
    if (word == "'s") {
        if (piece.tag != "AUX") return word
        val next = nextVerb(pieces, index)?.text
        return if (next == "been" || next == "got") "have" else "be"
    }
    if (word in MODALS) return word
    return when (piece.tag) {
        "VERB", "AUX" -> lexicon.verbLemma(word)
        "ADV" -> lexicon.adverbLemma(word)
        "ADJ" -> lexicon.adjectiveLemma(word)
        // A possessive stays one piece (the teacher's); its dictionary form is the noun.
        "NOUN" -> lexicon.nounLemma(word.removeSuffix("'s").removeSuffix("'"))
        else -> word
    }
}

/** The next helper or main verb after [index], skipping not and adverbs. */
private fun nextVerb(
    pieces: List<Piece>,
    index: Int,
): Piece? =
    pieces
        .drop(index + 1)
        .take(MAX_GAP)
        .firstOrNull { it.tag != "ADV" && it.tag != "PART" }
        ?.takeIf { it.tag == "VERB" || it.tag == "AUX" }

private fun nextVerbForm(
    pieces: List<Piece>,
    index: Int,
): String? = nextVerb(pieces, index)?.let { verb -> verbForm(verb.text, spelledVerbLemma(verb.text), pieces, pieces.indexOf(verb)) }

private const val MAX_GAP = 3

private fun englishForm(
    piece: Piece,
    lemma: String,
    pieces: List<Piece>,
    index: Int,
): String =
    when (piece.tag) {
        "VERB", "AUX" -> verbForm(piece.text, lemma, pieces, index)
        "ADJ" -> adjectiveForm(piece.text, lemma)
        "ADV" ->
            when {
                piece.text in ADVERB_COMPARATIVES -> "COMP"
                piece.text in setOf("most", "least", "best", "worst") -> "SUP"
                piece.text.endsWith("ly") && piece.text.length > MIN_LY_LENGTH -> "LY"
                else -> "*"
            }
        "NOUN" -> if (lemma != piece.text.removeSuffix("'s").removeSuffix("'")) "PL" else "*"
        else -> "*"
    }

private const val MIN_LY_LENGTH = 3

private val MODALS = setOf("can", "ca", "could", "will", "wo", "would", "shall", "sha", "should", "may", "might", "must", "'ll")

private fun verbForm(
    word: String,
    lemma: String,
    pieces: List<Piece>,
    index: Int,
): String =
    when {
        word in MODALS || (word == "'d" && lemma == "would") -> "MODAL"
        word in setOf("am", "is", "are", "'m", "'re") || (word == "'s" && lemma == "be") -> "PRES"
        word in setOf("was", "were") -> "PAST"
        word == "'s" -> "S3"
        word == "'d" -> "PAST"
        word == "been" -> "PP"
        // come, run, put: the base form is also a participle after have.
        word == lemma -> if ((word in IRREGULAR_PP_ONLY || word in IRREGULAR_PAST_AND_PP) && afterHelper(pieces, index)) "PP" else "BASE"
        word in IRREGULAR_PP_ONLY -> "PP"
        word in IRREGULAR_PAST_ONLY -> "PAST"
        word in IRREGULAR_PAST_AND_PP || word.endsWith("ed") -> if (afterHelper(pieces, index)) "PP" else "PAST"
        word.endsWith("ing") -> "ING"
        word.endsWith("s") -> "S3"
        else -> "BASE"
    }

/** Whether a form of have, be or get comes just before [index] (only not, adverbs and a subject between). */
private fun afterHelper(
    pieces: List<Piece>,
    index: Int,
): Boolean {
    for (i in index - 1 downTo maxOf(0, index - MAX_GAP - 1)) {
        val piece = pieces[i]
        if (piece.tag == "ADV" || piece.tag == "PRON" || piece.text == "not" || piece.text == "n't") continue
        if (piece.tag != "AUX" && piece.tag != "VERB") return false
        val lemma = CONTRACTED_LEMMAS[piece.text] ?: if (piece.text == "'s" || piece.text == "'d") "have" else spelledVerbLemma(piece.text)
        return lemma in setOf("have", "be", "get")
    }
    return false
}

/** A comparative or superlative: the irregular ones, or -er / -est on an adjective the dictionary knows. */
private fun adjectiveForm(
    word: String,
    lemma: String,
): String =
    when {
        word in setOf("better", "worse", "more", "less", "further", "farther") -> "COMP"
        word in setOf("best", "worst", "most", "least") -> "SUP"
        lemma != word && word.endsWith("er") -> "COMP"
        lemma != word && word.endsWith("est") -> "SUP"
        else -> "*"
    }

internal val ADVERB_COMPARATIVES =
    setOf(
        "more",
        "less",
        "better",
        "worse",
        "faster",
        "harder",
        "sooner",
        "later",
        "earlier",
        "longer",
        "further",
        "farther",
        "closer",
        "higher",
        "lower",
        "easier",
        "louder",
        "quicker",
        "slower",
    )

internal val COMPARISON_LEMMAS =
    mapOf(
        "better" to "good",
        "best" to "good",
        "worse" to "bad",
        "worst" to "bad",
        "further" to "far",
        "farther" to "far",
    )

/** The dictionary form of a verb by its spelling, for helper verbs and words the dictionary does not know. */
internal fun spelledVerbLemma(word: String): String {
    IRREGULAR_LEMMAS[word]?.let { return it }
    return when {
        word.endsWith("ied") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("ed") && word.length > 3 -> regularStem(word.dropLast(2), "ed")
        word.endsWith("ing") && word.length > 4 -> regularStem(word.dropLast(3), "ing")
        word.endsWith("es") && word.length > 3 && ES_ENDINGS.any { word.dropLast(2).endsWith(it) } -> word.dropLast(2)
        word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && word.length > 2 -> word.dropLast(1)
        else -> word
    }
}

private val ES_ENDINGS = listOf("ss", "sh", "ch", "x", "zz", "o")

/** Undoes the spelling changes of -ed and -ing: stopp → stop, liv → live, agre(ed) → agree. */
private fun regularStem(
    stem: String,
    suffix: String,
): String =
    when {
        stem in KEEP_DOUBLE -> stem
        stem.length > 2 && stem.last() == stem[stem.length - 2] && stem.last() in "bdgkmnprtz" -> stem.dropLast(1)
        suffix == "ed" && stem.endsWith("e") -> stem + "e"
        stem.last() in "aeiouy" && stem.last() != 'u' -> stem
        needsSilentE(stem) -> stem + "e"
        else -> stem
    }

private val KEEP_DOUBLE = setOf("add", "odd", "err", "ebb", "buzz", "fizz", "purr")

private val E_ENDINGS =
    listOf(
        Regex("[^aeiou]at$"),
        Regex("[^aeiou]id$"),
        Regex("[^aeiou]ud$"),
        Regex("ir$"),
        Regex("[^aeiou]ur$"),
        Regex("iz$"),
        Regex("[^aeiou][aeiou]s$"),
        Regex("[^aeiou]in$"),
        Regex("[^aeiou]ib$"),
        Regex("[^aeiou]ar$"),
        Regex("[^aeiou]um$"),
        Regex("[^aeiou]os$"),
        Regex("[^aeiou]ag$"),
        Regex("[^aeiou]l$"),
    )

private val NO_SILENT_E = setOf("focus", "bias", "visit", "travel", "cancel", "label", "model", "level", "signal", "total", "fuel", "feel")

private fun needsSilentE(stem: String): Boolean =
    stem !in NO_SILENT_E &&
        (
            stem.endsWith("u") ||
                stem.endsWith("v") ||
                stem.endsWith("c") ||
                stem.endsWith("dg") ||
                stem in E_STEMS ||
                oneSyllableNeedsE(stem) ||
                (stem.length > 4 && E_ENDINGS.any { it.containsMatchIn(stem) } && !stem.endsWith("al") && !stem.endsWith("el"))
        )

/** One vowel letter between consonants at the end of a one-syllable stem: mak(e), hop(e), us(e). */
private fun oneSyllableNeedsE(stem: String) = Regex("^[^aeiou]*[aeiou][^aeiouwxy]$").matches(stem)

private val E_STEMS =
    setOf("creat", "chang", "arrang", "challeng", "exchang", "rang", "complet", "delet", "compet", "explor", "ignor", "ador", "stor")

internal val IRREGULAR_PLURALS =
    mapOf(
        "children" to "child",
        "men" to "man",
        "women" to "woman",
        "feet" to "foot",
        "teeth" to "tooth",
        "mice" to "mouse",
        "geese" to "goose",
    )

/** base past past-participle; alternatives joined by `/`. */
private const val IRREGULAR_VERBS =
    "be was/were been,have had had,do did done,go went gone,get got got/gotten,make made made,say said said," +
        "see saw seen,take took taken,come came come,know knew known,think thought thought,give gave given," +
        "find found found,tell told told,become became become,leave left left,feel felt felt,bring brought brought," +
        "begin began begun,keep kept kept,hold held held,write wrote written,stand stood stood,hear heard heard," +
        "let let let,mean meant meant,set set set,meet met met,run ran run,pay paid paid,sit sat sat," +
        "speak spoke spoken,lie lay lain,lead led led,read read read,grow grew grown,lose lost lost,fall fell fallen," +
        "send sent sent,build built built,understand understood understood,draw drew drawn,break broke broken," +
        "spend spent spent,cut cut cut,rise rose risen,drive drove driven,buy bought bought,wear wore worn," +
        "choose chose chosen,seek sought sought,throw threw thrown,catch caught caught,deal dealt dealt,win won won," +
        "forget forgot forgotten,lay laid laid,sell sold sold,fight fought fought,eat ate eaten,sleep slept slept," +
        "teach taught taught,ride rode ridden,hide hid hidden,shoot shot shot,swim swam swum,sing sang sung," +
        "drink drank drunk,fly flew flown,forgive forgave forgiven,hang hung hung,hit hit hit,hurt hurt hurt," +
        "put put put,quit quit quit,shut shut shut,sink sank sunk,steal stole stolen,stick stuck stuck," +
        "strike struck struck,swear swore sworn,sweep swept swept,tear tore torn,wake woke woken,bite bit bitten," +
        "blow blew blown,feed fed fed,freeze froze frozen,bet bet bet,bend bent bent,bleed bled bled,dig dug dug," +
        "shake shook shaken,shine shone shone,spin spun spun,spread spread spread,bear bore born,beat beat beaten," +
        "burn burnt burnt,learn learnt learnt,smell smelt smelt,spell spelt spelt,lend lent lent,show showed shown," +
        "prove proved proven,cost cost cost,hide hid hidden,ring rang rung,arise arose arisen,awake awoke awoken," +
        "forbid forbade forbidden,overcome overcame overcome,undertake undertook undertaken,withdraw withdrew withdrawn," +
        "mistake mistook mistaken,upset upset upset,light lit lit,slide slid slid,sell sold sold,dream dreamt dreamt," +
        "kneel knelt knelt,lean leant leant,leap leapt leapt,fit fit fit,split split split,cast cast cast," +
        "broadcast broadcast broadcast,bind bound bound,grind ground ground,wind wound wound,flee fled fled," +
        "sting stung stung,swing swung swung,hang hung hung,weep wept wept,creep crept crept,sew sewed sewn"

/** Base forms that look like an -ed, -ing or -s form: need, bring, focus. */
private val BASE_FORMS_WITH_SUFFIX_SPELLING =
    setOf(
        "need",
        "succeed",
        "proceed",
        "exceed",
        "speed",
        "seed",
        "heed",
        "weed",
        "breed",
        "shed",
        "wed",
        "embed",
        "bed",
        "string",
        "spring",
        "bring",
        "sing",
        "ring",
        "sting",
        "swing",
        "cling",
        "fling",
        "sling",
        "wring",
        "focus",
        "discuss",
        "miss",
        "pass",
        "kiss",
        "guess",
        "press",
        "express",
        "dress",
        "cross",
        "toss",
        "bless",
        "possess",
        "process",
        "address",
        "access",
        "stress",
        "confess",
        "impress",
        "bias",
    )

private class IrregularForms(
    val lemmas: Map<String, String>,
    val pastOnly: Set<String>,
    val ppOnly: Set<String>,
    val both: Set<String>,
)

private val IRREGULAR: IrregularForms by lazy {
    val lemmas = HashMap<String, String>()
    val past = HashSet<String>()
    val pp = HashSet<String>()
    BASE_FORMS_WITH_SUFFIX_SPELLING.forEach { lemmas[it] = it }
    IRREGULAR_VERBS.split(',').forEach { entry ->
        val (base, pastForms, ppForms) = entry.split(' ')
        lemmas.putIfAbsent(base, base)
        pastForms.split('/').forEach {
            past += it
            lemmas.putIfAbsent(it, base)
        }
        ppForms.split('/').forEach {
            pp += it
            lemmas.putIfAbsent(it, base)
        }
    }
    listOf("am", "is", "are").forEach { lemmas[it] = "be" }
    lemmas["being"] = "be"
    lemmas["has"] = "have"
    lemmas["having"] = "have"
    lemmas["does"] = "do"
    lemmas["doing"] = "do"
    lemmas["goes"] = "go"
    lemmas["dying"] = "die"
    lemmas["lying"] = "lie"
    lemmas["tying"] = "tie"
    lemmas["seeing"] = "see"
    IrregularForms(lemmas, past - pp, pp - past, past intersect pp)
}

internal val IRREGULAR_LEMMAS: Map<String, String> get() = IRREGULAR.lemmas
private val IRREGULAR_PAST_ONLY: Set<String> get() = IRREGULAR.pastOnly
private val IRREGULAR_PP_ONLY: Set<String> get() = IRREGULAR.ppOnly
private val IRREGULAR_PAST_AND_PP: Set<String> get() = IRREGULAR.both
