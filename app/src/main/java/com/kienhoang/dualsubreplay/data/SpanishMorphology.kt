package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Spanish words tagged in context with OpenNLP's Spanish part-of-speech model, with verb
 * conjugations and dictionary forms from Wiktionary ([SpanishVerbs], [SpanishWords]; all bundled in
 * the APK). The data loads on a background thread the first time Spanish is shown; until then
 * [analyze] and [morphemes] return null and callers use their heuristic.
 */
internal object SpanishMorphology {
    private val analyzer = BackgroundLoaded("spanish-analyzer", ::loadSpanishAnalyzer)

    val revision: StateFlow<Int> get() = analyzer.revision

    fun warmUp() = analyzer.warmUp()

    fun awaitReady(timeoutMs: Long = 60_000): Boolean = analyzer.awaitReady(timeoutMs)

    /** Words of [text] with their parts of speech and dictionary forms, or null while the data loads. */
    fun analyze(text: String): List<AnalyzedToken>? = analyzer.get()?.let { spanishWords(it, text) }

    /** The pieces grammar is read from (del, al and attached pronouns split), or null while the data loads. */
    fun morphemes(text: String): List<Morpheme>? = analyzer.get()?.let { spanishMorphemes(it, text) }

    /** How the verb between [start] and [end] of [line] is conjugated, or null for no verb or while the data loads. */
    fun verbDetails(
        line: String,
        start: Int,
        end: Int,
    ): SpanishVerbDetails? = analyzer.get()?.let { spanishVerbDetails(it, line, start, end) }
}

/** What Spanish analysis needs: the tagger, the verbs and the other words. */
internal class SpanishAnalyzer(
    val tagger: OpenNlpPosTagger,
    val verbs: SpanishVerbs,
    val words: SpanishWords,
)

internal fun loadSpanishAnalyzer(): SpanishAnalyzer =
    SpanishAnalyzer(
        LearnerData.open("es-pos.zip").use(OpenNlpPosTagger::load),
        LearnerData.open("es-verbs.txt").use(SpanishVerbs::load),
        LearnerData.open("es-words.txt").use(SpanishWords::load),
    )

/** A tapped verb's readings in this sentence (likeliest first) and the pronouns attached to it (dámelo: me, lo). */
internal data class SpanishVerbDetails(
    val readings: List<SpanishVerbReading>,
    val attachedPronouns: List<String>,
)

/** A word or punctuation mark of a Spanish line and its UD tag (`ADP+DET` for del, `VERB+PRON` for dime). */
internal data class TaggedSpanishToken(
    val text: String,
    val start: Int,
    val tag: String,
    val isWord: Boolean,
)

/**
 * One piece of a token: its lowercase text, where it starts, its UD tag, dictionary form and, for a
 * verb, every reading in the order this sentence makes likeliest.
 */
internal data class SpanishPiece(
    val text: String,
    val start: Int,
    val tag: String,
    val lemma: String,
    val readings: List<SpanishVerbReading> = emptyList(),
    val token: Int,
)

private val SPANISH_TOKEN = Regex("([\\p{L}\\p{M}\\p{N}_]+(?:['’-]+[\\p{L}\\p{M}\\p{N}_]+)*)|[^\\s\\p{L}\\p{M}\\p{N}_]")

internal fun tagSpanish(
    tagger: OpenNlpPosTagger,
    text: String,
): List<TaggedSpanishToken> {
    val matches = SPANISH_TOKEN.findAll(text).toList()
    val tags = tagger.tag(matches.map { it.value })
    return matches.mapIndexed { index, match -> TaggedSpanishToken(match.value, match.range.first, tags[index], match.groups[1] != null) }
}

/** Words colored by their first piece's tag, so dámelo shows as a verb and its card says dar. */
internal fun spanishWords(
    analyzer: SpanishAnalyzer,
    text: String,
): List<AnalyzedToken> {
    val tokens = tagSpanish(analyzer.tagger, text)
    val pieces = spanishPiecesOf(analyzer, tokens)
    return tokens.mapIndexedNotNull { index, token ->
        if (!token.isWord) return@mapIndexedNotNull null
        val parts = pieces.filter { it.token == index }
        val head = parts.firstOrNull()
        val lower = lowercaseSpanish(token.text)
        val verb = parts.firstOrNull { it.tag == "VERB" || it.tag == "AUX" }
        val shown = if (verb != null && parts.drop(1).all { it.tag == "PRON" }) verb else head.takeIf { parts.size == 1 }
        AnalyzedToken(
            text = token.text,
            startIndex = token.start,
            endIndex = token.start + token.text.length,
            partOfSpeech = spanishPartOfSpeech(head?.tag ?: token.tag.substringBefore('+'), lower),
            baseForm = shown?.lemma?.takeIf { it != lower && it != shown.text && it.isNotEmpty() },
        )
    }
}

internal fun spanishMorphemes(
    analyzer: SpanishAnalyzer,
    text: String,
): List<Morpheme> = spanishPiecesOf(analyzer, tagSpanish(analyzer.tagger, text)).map(::spanishMorpheme)

/**
 * A piece as grammar patterns read it: lowercase surface, UD tag ([Morpheme.pos]), the tenses of
 * its readings joined by `|` ([Morpheme.conjugationForm]: PRES, PRET, IMPF, FUT, COND, SUBJ_PRES,
 * SUBJ_IMPF, CMD, INF, GER, PART), their persons the same way ([Morpheme.detail]: 1S … 3P, VOS) and
 * its dictionary form.
 */
internal fun spanishMorpheme(piece: SpanishPiece): Morpheme =
    Morpheme(
        surface = piece.text,
        start = piece.start,
        pos = piece.tag,
        detail = piece.readings.takeIf { it.isNotEmpty() }?.joinToString("|") { it.person.code } ?: "*",
        conjugationForm = piece.readings.takeIf { it.isNotEmpty() }?.joinToString("|") { it.tense.code } ?: "*",
        baseForm = piece.lemma,
    )

internal fun spanishVerbDetails(
    analyzer: SpanishAnalyzer,
    line: String,
    start: Int,
    end: Int,
): SpanishVerbDetails? {
    val pieces = spanishPiecesOf(analyzer, tagSpanish(analyzer.tagger, line))
    val inside = pieces.filter { it.start in start until end }
    val verb = inside.firstOrNull { it.readings.isNotEmpty() && (it.tag == "VERB" || it.tag == "AUX") } ?: return null
    val attached = pieces.filter { it.token == verb.token && it.start > verb.start }.map { it.text }
    return SpanishVerbDetails(verb.readings, attached)
}

/** The app's part of speech for a UD tag; articles and other determiners are grammar words, except este, ese and aquel. */
internal fun spanishPartOfSpeech(
    tag: String,
    lowerWord: String,
): PartOfSpeech =
    when (tag) {
        "NOUN", "PROPN" -> PartOfSpeech.NOUN
        "VERB", "AUX" -> PartOfSpeech.VERB
        "ADJ" -> PartOfSpeech.ADJECTIVE
        "ADV" -> PartOfSpeech.ADVERB
        "PRON" -> PartOfSpeech.PRONOUN
        "DET" -> if (plainSpelling(lowerWord) in SPANISH_DEMONSTRATIVES) PartOfSpeech.PRONOUN else PartOfSpeech.PARTICLE
        "ADP" -> PartOfSpeech.PREPOSITION
        "CCONJ", "SCONJ" -> PartOfSpeech.CONJUNCTION
        else -> PartOfSpeech.OTHER
    }

private val SPANISH_DEMONSTRATIVES =
    setOf("este", "esta", "estos", "estas", "ese", "esa", "esos", "esas", "aquel", "aquella", "aquellos", "aquellas")

/** Lowercase that keeps the length, so offsets still point into the original line. */
private fun lowercaseSpanish(text: String): String = text.lowercase(Locale.ROOT).takeIf { it.length == text.length } ?: text

/** Pronouns that attach to the end of an infinitive, gerund or command (dámelo, decírselo, cállate). */
private val ENCLITICS = listOf("nos", "les", "los", "las", "me", "te", "se", "os", "lo", "la", "le")

/** Words the model often gets wrong at the start of a line or as names. */
private val FIXED_TAGS =
    mapOf(
        "sí" to "ADV",
        "vos" to "PRON",
        "usted" to "PRON",
        "ustedes" to "PRON",
        "conmigo" to "PRON",
        "contigo" to "PRON",
        "hola" to "INTJ",
    )

private fun isFinite(reading: SpanishVerbReading) =
    reading.tense != SpanishTense.INFINITIVE && reading.tense != SpanishTense.GERUND && reading.tense != SpanishTense.PARTICIPLE

/** The tags the model gives words it doesn't know (comí as a preposition); such a word that can only be a verb form is a verb. */
private val GUESSED_TAGS = setOf("NOUN", "ADJ", "DET", "PRON", "PROPN", "VERB+PRON", "ADP", "CCONJ", "SCONJ", "ADV", "INTJ")

private const val SENTENCE_STARTS = ".!?¡¿:;…\"«“—-"

internal fun spanishPiecesOf(
    analyzer: SpanishAnalyzer,
    tokens: List<TaggedSpanishToken>,
): List<SpanishPiece> {
    val pieces = mutableListOf<SpanishPiece>()
    tokens.forEachIndexed { index, token ->
        val lower = lowercaseSpanish(token.text)
        when {
            !token.isWord -> pieces += SpanishPiece(lower, token.start, token.tag.substringBefore('+'), lower, token = index)
            lower in CONTRACTIONS -> {
                // The article keeps its own letter (de + l) so offsets stay right; its dictionary form is el.
                val cut = lower.length - 1
                pieces += SpanishPiece(lower.substring(0, cut), token.start, "ADP", lower.substring(0, cut), token = index)
                pieces += SpanishPiece(lower.substring(cut), token.start + cut, "DET", "el", token = index)
            }
            else -> {
                val sentenceStart = index == 0 || tokens[index - 1].text.all { it in SENTENCE_STARTS }
                pieces += wordPieces(analyzer, token, index, lower, sentenceStart, pieces.lastOrNull())
            }
        }
    }
    return orderReadingsInContext(pieces)
}

/**
 * The tag a word gets: a fixed one for words the model often gets wrong, and for a word the model
 * calls a verb that is no verb form at all (azúcar, ah), its part of speech in the word lists.
 */
private fun pieceTag(
    analyzer: SpanishAnalyzer,
    lower: String,
    modelTag: String,
): String {
    FIXED_TAGS[lower]?.let { return it }
    // A capitalized interjection at the start of a line (Eh, Vale) is not a name.
    if (modelTag == "PROPN" && analyzer.words.isInterjection(lower)) return "INTJ"
    if (modelTag != "VERB" && modelTag != "AUX") return modelTag
    if (analyzer.verbs.readings(lower).isNotEmpty() || splitEnclitics(analyzer.verbs, lower) != null) return modelTag
    return analyzer.words.tagOf(lower) ?: modelTag
}

/**
 * Whether the word lists have [lower] as a word of its own with this tag; after cuando, que, si, no
 * or a pronoun, a word that can be a finite verb is one instead (cuando llegues).
 */
private fun listedAsOtherWord(
    words: SpanishWords,
    lower: String,
    tag: String,
    previous: SpanishPiece?,
    readings: List<SpanishVerbReading>,
): Boolean {
    val verbContext = previous?.let { it.tag == "SCONJ" || it.tag == "PRON" || it.text == "no" } == true
    return otherLemma(words, lower, tag) != null && !(verbContext && readings.any(::isFinite))
}

/** The pieces of one word: a verb (with any attached pronouns split off) or another word. */
private fun wordPieces(
    analyzer: SpanishAnalyzer,
    token: TaggedSpanishToken,
    index: Int,
    lower: String,
    sentenceStart: Boolean,
    previous: SpanishPiece?,
): List<SpanishPiece> {
    val tag = pieceTag(analyzer, lower, token.tag)
    val properName = tag == "PROPN" && !sentenceStart
    val ownReadings = if (properName) emptyList() else analyzer.verbs.readings(lower)
    val listedOtherwise = listedAsOtherWord(analyzer.words, lower, tag, previous, ownReadings)
    val clitics = if (properName) null else splitEnclitics(analyzer.verbs, lower)
    val taggedVerb = tag.startsWith("VERB") || tag == "AUX"
    val verbTag = if (tag == "AUX") "AUX" else "VERB"
    val guessed = tag.substringBefore('+') in GUESSED_TAGS && !listedOtherwise && ownReadings.isNotEmpty() && !properName
    return when {
        // A verb with pronouns attached; the model tags many of them as names or nouns.
        clitics != null && (taggedVerb || !listedOtherwise || ownReadings.isEmpty()) -> {
            val (verb, readings, attached) = clitics
            var at = token.start + verb.length
            listOf(SpanishPiece(verb, token.start, verbTag, readings.first().lemma, readings, index)) +
                attached.map { pronoun -> SpanishPiece(pronoun, at, "PRON", pronoun, token = index).also { at += pronoun.length } }
        }
        tag == "VERB" || tag == "AUX" || guessed ->
            listOf(SpanishPiece(lower, token.start, verbTag, ownReadings.firstOrNull()?.lemma ?: lower, ownReadings, index))
        else -> listOf(otherWordPiece(analyzer.words, token, index, lower, tag.substringBefore('+'), ownReadings))
    }
}

/** A word that is not a verb; a participle used as an adjective (cansada) keeps its participle reading for estar + participio. */
private fun otherWordPiece(
    words: SpanishWords,
    token: TaggedSpanishToken,
    index: Int,
    lower: String,
    tag: String,
    readings: List<SpanishVerbReading>,
): SpanishPiece {
    val participle = if (tag == "ADJ") readings.filter { it.tense == SpanishTense.PARTICIPLE } else emptyList()
    return SpanishPiece(lower, token.start, tag, otherLemma(words, lower, tag) ?: lower, participle, index)
}

/** del = de + el, al = a + el. */
private val CONTRACTIONS = setOf("del", "al")

/** The dictionary form of a word that is not a verb, by its tag, or null when the word lists have no such word. */
private fun otherLemma(
    words: SpanishWords,
    word: String,
    tag: String,
): String? =
    when (tag.substringBefore('+')) {
        "NOUN" -> words.lemma(word, 'n')
        "ADJ" -> words.lemma(word, 'a') ?: words.lemma(word, 'n')
        "DET", "PRON", "NUM" -> words.lemma(word, 'd')
        "PROPN" -> words.lemma(word, 'n') ?: words.lemma(word, 'a')
        "ADP", "CCONJ", "SCONJ", "ADV", "INTJ" -> word.takeIf(words::isFunctionWord)
        else -> null
    }

/** Readings that can carry attached pronouns: infinitives, gerunds and commands (also usted and nosotros commands). */
private fun takesEnclitics(reading: SpanishVerbReading): Boolean =
    when (reading.tense) {
        SpanishTense.INFINITIVE, SpanishTense.GERUND, SpanishTense.IMPERATIVE -> true
        SpanishTense.PRESENT_SUBJUNCTIVE ->
            reading.person == SpanishPerson.EL || reading.person == SpanishPerson.NOSOTROS || reading.person == SpanishPerson.ELLOS
        else -> false
    }

/**
 * A verb with one or two pronouns attached: the verb, its readings and the pronouns, or null.
 * The accent written to keep the stress (dámelo, diciéndole) comes off the verb; vámonos and
 * sentaos also lost the -s and -d of vamos and sentad.
 */
private fun splitEnclitics(
    verbs: SpanishVerbs,
    word: String,
): Triple<String, List<SpanishVerbReading>, List<String>>? {
    for (last in ENCLITICS) {
        if (!word.endsWith(last) || word.length <= last.length + 1) continue
        val beforeLast = word.dropLast(last.length)
        val options = mutableListOf(beforeLast to listOf(last))
        ENCLITICS.firstOrNull { beforeLast.endsWith(it) && beforeLast.length > it.length + 1 }?.let {
            options.add(0, beforeLast.dropLast(it.length) to listOf(it, last))
        }
        for ((verb, attached) in options) {
            val readings = encliticHost(verbs, verb, attached.first())
            if (readings.isNotEmpty()) return Triple(verb, readings, attached)
        }
    }
    return null
}

private fun encliticHost(
    verbs: SpanishVerbs,
    verb: String,
    firstPronoun: String,
): List<SpanishVerbReading> {
    // The accent is usually only there because of the pronouns (dá|melo = da), so that spelling comes first.
    val spellings = linkedSetOf(plainSpellingKeepingInfinitive(verb), verb)
    if (firstPronoun == "nos") spellings += plainSpelling(verb) + "s"
    if (firstPronoun == "os") spellings += plainSpelling(verb) + "d"
    // Vámonos: the present vamos is also the "let's go" command.
    if (firstPronoun == "nos" &&
        plainSpelling(verb) == "vamo"
    ) {
        return listOf(SpanishVerbReading("ir", SpanishTense.IMPERATIVE, SpanishPerson.NOSOTROS))
    }
    return spellings.flatMap { spelling -> verbs.readings(spelling).filter(::takesEnclitics) }.distinct()
}

/** Without the stress accent, except the í of oír, reír and freír infinitives (oírlo). */
private fun plainSpellingKeepingInfinitive(verb: String): String =
    if (verb.endsWith("ír")) plainSpelling(verb.dropLast(2)) + "ír" else plainSpelling(verb)

/** Words after fue, fui, fueron… that make it "went" (ir) rather than "was" (ser). */
private val WENT_TO = setOf("a", "al", "hacia", "hasta", "para", "allí", "allá", "ahí", "aquí", "acá", "lejos", "adonde")

/**
 * Puts the likeliest reading first: ir rather than ser before a / al / hacia (fue a casa), a command
 * rather than the present at the start of a sentence that is not a question (¡Habla más alto!), and
 * the present rather than a command anywhere else (No sé si viene).
 */
private fun orderReadingsInContext(pieces: List<SpanishPiece>): List<SpanishPiece> =
    pieces.mapIndexed { index, piece ->
        if (piece.readings.size < 2) return@mapIndexed piece
        val next = pieces.getOrNull(index + 1)?.takeIf { it.token != piece.token || piece.text != it.text }
        var readings = piece.readings
        val lemmas = readings.map { it.lemma }.distinct()
        if ("ir" in lemmas && "ser" in lemmas) {
            val went = next?.text in WENT_TO
            readings = readings.sortedBy { if ((it.lemma == "ir") == went) 0 else 1 }
        }
        if ("ir" in lemmas && "ver" in lemmas && piece.text == "ve") {
            val go = next?.text in WENT_TO || startsCommand(pieces, index)
            readings = readings.sortedBy { if ((it.lemma == "ir") == go) 0 else 1 }
        }
        if (readings.any { it.tense == SpanishTense.IMPERATIVE }) {
            // A command only starts a sentence; elsewhere (no sé, ella habla) the other readings come first.
            val command = startsCommand(pieces, index)
            readings = readings.sortedBy { if ((it.tense == SpanishTense.IMPERATIVE) == command) 0 else 1 }
        }
        piece.copy(readings = readings, lemma = readings.first().lemma)
    }

/** A verb that starts a sentence (after ¡, a name and comma, or "por favor,") of a line that asks nothing. */
private fun startsCommand(
    pieces: List<SpanishPiece>,
    index: Int,
): Boolean {
    val before = pieces.take(index).dropLastWhile { it.tag == "PRON" && it.token == pieces[index].token }
    val sentence = before.indexOfLast { it.tag == "PUNCT" && it.text in setOf(".", "!", "?", "¡", "¿", "…") }
    val lead = before.drop(sentence + 1)
    if (lead.any { it.text == "¿" } || before.lastOrNull()?.text == "¿") return false
    val startsHere =
        lead.isEmpty() || lead.all { it.tag == "PROPN" || it.tag == "INTJ" || it.text == "," || it.text in setOf("por", "favor") }
    val rest = pieces.drop(index + 1)
    val asks = rest.takeWhile { it.text != "." && it.text != "!" }.any { it.text == "?" }
    return startsHere && !asks
}
