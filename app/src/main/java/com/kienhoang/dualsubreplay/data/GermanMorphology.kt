package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.flow.StateFlow

/**
 * German words tagged in context with Apache OpenNLP's German part-of-speech model (Universal
 * Dependencies GSD, bundled in the APK). It loads on a background thread the first time German is
 * shown; until then, or if it failed to load, [analyze] returns null and the caller keeps the
 * words uncolored rather than guessing with English word lists.
 */
internal object GermanMorphology {
    private val tagger = BackgroundLoaded("german-analyzer") { LearnerData.open("de-pos.zip").use(OpenNlpPosTagger::load) }

    /** Verbs, nouns and adjectives of `de-verbs.txt` and `de-words.txt`; loaded apart from the tagger so coloring never waits for it. */
    private val lexicon =
        BackgroundLoaded("german-lexicon") {
            LearnerData.open("de-verbs.txt").use { verbs -> LearnerData.open("de-words.txt").use { loadGermanLexicon(verbs, it) } }
        }

    val revision: StateFlow<Int> get() = tagger.revision

    /** Changes when the lexicon finishes loading, so grammar read before it loaded is read again. */
    val lexiconRevision: StateFlow<Int> get() = lexicon.revision

    fun warmUp() {
        tagger.warmUp()
        lexicon.warmUp()
    }

    /** Loads the tagger and the lexicon and waits, for tests. */
    fun awaitGrammarReady(timeoutMs: Long = 60_000): Boolean = tagger.awaitReady(timeoutMs) && lexicon.awaitReady(timeoutMs)

    /**
     * The last line read. One word card asks for the same line's sentence several times (grammar points, word details,
     * role, conjugation); once the tagger and the lexicon are loaded they never change, so the line is read once.
     */
    @Volatile
    private var lastSentence: GermanSentence? = null

    /** The words of [text] with every lexicon reading, for grammar, or null while the tagger or the lexicon loads. */
    fun sentence(text: String): GermanSentence? {
        lastSentence?.takeIf { it.text == text }?.let { return it }
        val tagger = tagger.get() ?: return null
        val lexicon = lexicon.get() ?: return null
        return germanSentence(tagger, lexicon, text).also { lastSentence = it }
    }

    /** What to tell about the German word between [start] and [end] of [line], or null while data loads or for no listed word. */
    fun wordDetails(
        line: String,
        start: Int,
        end: Int,
    ): GermanWordDetails? = sentence(line)?.let { germanWordDetails(it, start, end) }

    /** What the tapped word does in its sentence (pronoun, adverb, article, ...), or null while data loads or when the line does not say. */
    fun wordRole(
        line: String,
        start: Int,
        end: Int,
    ): GermanWordRole? = sentence(line)?.let { germanWordRole(it, start, end) }

    /** The conjugation tables of the tapped verb, or null while data loads or when the word is not a listed verb. */
    fun conjugation(
        line: String,
        start: Int,
        end: Int,
    ): GermanSelectionConjugation? {
        val lexicon = lexicon.get() ?: return null
        return sentence(line)?.let { germanSelectionConjugation(it, lexicon, start, end) }
    }

    /** The lexicon, or null while it loads. */
    fun lexicon(): GermanLexicon? = lexicon.get()

    fun awaitReady(timeoutMs: Long = 60_000): Boolean = tagger.awaitReady(timeoutMs)

    /** The words of [text] with their parts of speech, or null while the model loads. */
    fun analyze(text: String): List<AnalyzedToken>? = tagger.get()?.let { germanWords(it, text) }
}

/** Words are cut exactly as the spaced-language tokenizer cuts them, so tap and drag selection keep their boundaries. */
internal fun germanWords(
    tagger: OpenNlpPosTagger,
    text: String,
): List<AnalyzedToken> {
    val matches = ENGLISH_TOKEN.findAll(text).toList()
    val tags = tagger.tag(matches.map { it.value.replace('’', '\'') })
    return matches.mapIndexedNotNull { index, match ->
        if (match.groups[1] == null) {
            null
        } else {
            AnalyzedToken(
                text = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                partOfSpeech = germanPartOfSpeech(tags[index]),
            )
        }
    }
}

/** The app's part of speech for a UD tag; articles and other determiners are grammar words. */
internal fun germanPartOfSpeech(tag: String): PartOfSpeech =
    when (tag) {
        "NOUN", "PROPN" -> PartOfSpeech.NOUN
        "VERB", "AUX" -> PartOfSpeech.VERB
        "ADJ" -> PartOfSpeech.ADJECTIVE
        "ADV" -> PartOfSpeech.ADVERB
        "PRON" -> PartOfSpeech.PRONOUN
        "ADP" -> PartOfSpeech.PREPOSITION
        "CCONJ", "SCONJ" -> PartOfSpeech.CONJUNCTION
        "DET", "PART" -> PartOfSpeech.PARTICLE
        else -> PartOfSpeech.OTHER
    }
