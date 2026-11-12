package com.kienhoang.dualsubreplay.data

import android.content.Context

/**
 * What a card shows before it is turned (owner pick 2026-11-10 for lion737's Migaku-style cards,
 * spec docs/specs/2026-11-10-anki-card-picture-and-front.md). Everything else goes on the back.
 * The front is the note type's template, so changing it changes cards sent before too, like
 * Anki's own card types.
 */
internal enum class CardFront(
    val storageValue: String,
) {
    /** The word with its sentence: the card every version made before. */
    WORD_AND_SENTENCE("word_sentence"),
    WORD("word"),

    /** The sentence with the word in bold. */
    SENTENCE("sentence"),

    /** Only the line's sound; until the sound is there, the sentence shows instead. */
    LISTENING("listening"),
}

internal fun storedCardFront(value: String?): CardFront =
    CardFront.entries.firstOrNull { it.storageValue == value } ?: CardFront.WORD_AND_SENTENCE

/** What Send to puts on each card: the two sounds, a picture from the video, and the front. */
internal data class CardOptions(
    val audio: Boolean = false,
    val picture: Boolean = false,
    val front: CardFront = CardFront.WORD_AND_SENTENCE,
    /** Card front → Sentence sound on the front (lion737, #81, 2026-11-11): the line's sound plays with the front. */
    val frontSound: Boolean = false,
) {
    /** The line's sound moves to the front; a Listening front has nothing but it already. */
    val soundOnFront: Boolean get() = frontSound && front != CardFront.LISTENING
}

/** One card template of the DualSub Replay audio note type. */
internal data class AnkiCardTemplate(
    val question: String,
    val answer: String,
)

private const val WORD_HTML = "<div class=word>{{Word}}</div>"
private const val SENTENCE_HTML = "<div class=sentence>{{Sentence}}</div>"

/** The note type's CSS can't change once AnkiDroid made it, so the sentence front sizes itself. */
private const val SENTENCE_FRONT_HTML = "<div class=sentence style=\"font-size:26px\">{{Sentence}}</div>"
private const val READING_HTML = "<div class=reading>{{Reading}}</div>"
private const val MEANING_HTML = "<div class=meaning>{{Meaning}}</div>"
private const val TRANSLATION_HTML = "<div class=translation>{{Translation}}</div>"
private const val SOURCE_HTML = "<div class=source>{{Source}}</div>"
private const val WORD_AUDIO = "{{Word audio}}"
private const val SENTENCE_AUDIO = "{{Sentence audio}}"
private const val BACK_START = "{{FrontSide}}<hr id=answer>"

/** The front plays the word's sound, the back the sentence's. */
internal const val ANKI_QUESTION_FORMAT = WORD_HTML + SENTENCE_HTML + WORD_AUDIO
internal const val ANKI_ANSWER_FORMAT = BACK_START + READING_HTML + MEANING_HTML + TRANSLATION_HTML + SENTENCE_AUDIO + SOURCE_HTML

/**
 * The card template for [front]; the picture is in the Source field, so it is on the back of every
 * one. With [frontSound] the line's sound moves from the back to the front (Listening has it there).
 */
internal fun ankiCardTemplate(
    front: CardFront,
    frontSound: Boolean = false,
): AnkiCardTemplate {
    val template = cardTemplateOf(front)
    if (!frontSound || front == CardFront.LISTENING) return template
    return AnkiCardTemplate(template.question + SENTENCE_AUDIO, template.answer.replace(SENTENCE_AUDIO, ""))
}

private fun cardTemplateOf(front: CardFront): AnkiCardTemplate =
    when (front) {
        CardFront.WORD_AND_SENTENCE -> AnkiCardTemplate(ANKI_QUESTION_FORMAT, ANKI_ANSWER_FORMAT)
        CardFront.WORD ->
            AnkiCardTemplate(
                WORD_HTML + WORD_AUDIO,
                BACK_START + READING_HTML + MEANING_HTML + SENTENCE_HTML + TRANSLATION_HTML + SENTENCE_AUDIO + SOURCE_HTML,
            )
        CardFront.SENTENCE ->
            AnkiCardTemplate(
                SENTENCE_FRONT_HTML,
                BACK_START + WORD_HTML + WORD_AUDIO + READING_HTML + MEANING_HTML + TRANSLATION_HTML + SENTENCE_AUDIO + SOURCE_HTML,
            )
        CardFront.LISTENING ->
            AnkiCardTemplate(
                "$SENTENCE_AUDIO{{^Sentence audio}}$SENTENCE_HTML{{/Sentence audio}}",
                BACK_START + "{{#Sentence audio}}$SENTENCE_HTML{{/Sentence audio}}" +
                    WORD_HTML + WORD_AUDIO + READING_HTML + MEANING_HTML + TRANSLATION_HTML + SOURCE_HTML,
            )
    }

/** What a note type's template was last set to: the front's storage value, with [FRONT_SOUND_SUFFIX] for Sentence sound on the front. */
internal fun cardTemplateKey(
    front: CardFront,
    frontSound: Boolean = false,
): String = if (frontSound && front != CardFront.LISTENING) front.storageValue + FRONT_SOUND_SUFFIX else front.storageValue

private const val FRONT_SOUND_SUFFIX = "+sound"

/**
 * Whether the note type's template has to change: it was last set to [applied] (null: never by this
 * version, so the old fixed word-and-sentence front). A template the learner edited in Anki is left
 * alone until they pick another front or turn Sentence sound on the front on or off.
 */
internal fun cardFrontNeedsUpdate(
    applied: String?,
    wanted: CardFront,
    frontSound: Boolean = false,
): Boolean {
    val sound = applied?.endsWith(FRONT_SOUND_SUFFIX) == true
    val appliedKey = cardTemplateKey(storedCardFront(applied?.removeSuffix(FRONT_SOUND_SUFFIX)), sound)
    return appliedKey != cardTemplateKey(wanted, frontSound)
}

/** The field text that shows the media file [name] as a picture; empty for a name that isn't a plain file. */
internal fun ankiPicture(name: String?): String {
    val clean = name?.trim()?.removePrefix("/").orEmpty()
    if (clean.isEmpty() || clean.any { it in "[]/\\\"'<>&" || it.isISOControl() || it.isWhitespace() }) return ""
    return "<img src=\"$clean\" style=\"max-width:100%;max-height:50vh;border-radius:8px\">"
}

/** The card front last set on each note type, so the template changes only when the pick does. */
internal interface CardFrontMemory {
    fun applied(noteType: String): String?

    fun remember(
        noteType: String,
        front: CardFront,
        frontSound: Boolean = false,
    )
}

internal class InMemoryCardFronts : CardFrontMemory {
    private val fronts = HashMap<String, String>()

    override fun applied(noteType: String): String? = fronts[noteType]

    override fun remember(
        noteType: String,
        front: CardFront,
        frontSound: Boolean,
    ) {
        fronts[noteType] = cardTemplateKey(front, frontSound)
    }
}

internal class PreferenceCardFronts(
    context: Context,
    name: String,
) : CardFrontMemory {
    private val preferences = context.applicationContext.getSharedPreferences(name, 0)

    override fun applied(noteType: String): String? = preferences.getString(noteType, null)

    override fun remember(
        noteType: String,
        front: CardFront,
        frontSound: Boolean,
    ) {
        preferences.edit().putString(noteType, cardTemplateKey(front, frontSound)).apply()
    }
}
