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

    val revision: StateFlow<Int> get() = tagger.revision

    fun warmUp() = tagger.warmUp()

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
