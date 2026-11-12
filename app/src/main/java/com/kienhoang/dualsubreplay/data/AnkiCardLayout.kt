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

/**
 * Where a card carries one of its media (lion737, #81, 2026-11-12): on the front (so also on the
 * back, which shows the front above it), on the back only, or not at all.
 */
internal enum class CardPlace(
    val storageValue: String,
) {
    FRONT("front"),
    BACK("back"),
    OFF("off"),
}

internal fun storedCardPlace(
    value: String?,
    otherwise: CardPlace,
): CardPlace = CardPlace.entries.firstOrNull { it.storageValue == value } ?: otherwise

/** Where the word's voice usually goes for [front]: with the word, so on the back when the front hides the word. */
internal fun usualWordSoundPlace(front: CardFront): CardPlace =
    if (front == CardFront.SENTENCE || front == CardFront.LISTENING) CardPlace.BACK else CardPlace.FRONT

/**
 * What Send to puts on each card, and where (Settings → Flashcards & Anki): the word's voice, the
 * line's real sound from the video, a picture of the line, and the front.
 */
internal data class CardOptions(
    val wordSound: CardPlace = CardPlace.OFF,
    val sentenceSound: CardPlace = CardPlace.OFF,
    val picture: CardPlace = CardPlace.OFF,
    val front: CardFront = CardFront.WORD_AND_SENTENCE,
) {
    val wordAudio: Boolean get() = wordSound != CardPlace.OFF
    val sentenceAudio: Boolean get() = sentenceSound != CardPlace.OFF
    val pictureWanted: Boolean get() = picture != CardPlace.OFF

    /** The note type's template sides; a medium that is off keeps its usual side, as its field stays empty. */
    val sides: CardSides
        get() =
            CardSides(
                wordSoundFront = if (wordAudio) wordSound == CardPlace.FRONT else null,
                sentenceSoundFront = sentenceSound == CardPlace.FRONT,
                pictureFront = picture == CardPlace.FRONT,
            )
}

/**
 * Which media the template shows on the front. [wordSoundFront] null keeps the word's voice where
 * [usualWordSoundPlace] puts it; the line's sound is always on a Listening front.
 */
internal data class CardSides(
    val wordSoundFront: Boolean? = null,
    val sentenceSoundFront: Boolean = false,
    val pictureFront: Boolean = false,
)

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
 * The picture on the front: the Source field holds the picture and the video link, so the front
 * hides the link and the back's Source hides the picture (the back shows the front above it).
 */
private const val PICTURE_FRONT_HTML = "<div class=\"source pf\">{{Source}}</div><style>.pf a,.pf br{display:none}</style>"
private const val SOURCE_LINK_HTML = "<div class=\"source sl\">{{Source}}</div><style>.sl img,.sl br{display:none}</style>"

/**
 * The card template for [front], with [sides] saying which media show on the front. The picture is
 * in the Source field, so without [CardSides.pictureFront] it is on the back of every card.
 */
internal fun ankiCardTemplate(
    front: CardFront,
    sides: CardSides = CardSides(),
): AnkiCardTemplate {
    val wordFront = sides.wordSoundFront ?: (usualWordSoundPlace(front) == CardPlace.FRONT)
    val sentenceFront = sides.sentenceSoundFront || front == CardFront.LISTENING
    val wordSound = { onFront: Boolean -> if (wordFront == onFront) WORD_AUDIO else "" }
    val sentenceSound = { onFront: Boolean -> if (sentenceFront == onFront) SENTENCE_AUDIO else "" }
    val picture = if (sides.pictureFront) PICTURE_FRONT_HTML else ""
    val source = if (sides.pictureFront) SOURCE_LINK_HTML else SOURCE_HTML
    val details = READING_HTML + MEANING_HTML
    return when (front) {
        CardFront.WORD_AND_SENTENCE ->
            AnkiCardTemplate(
                WORD_HTML + SENTENCE_HTML + picture + wordSound(true) + sentenceSound(true),
                BACK_START + wordSound(false) + details + TRANSLATION_HTML + sentenceSound(false) + source,
            )
        CardFront.WORD ->
            AnkiCardTemplate(
                WORD_HTML + picture + wordSound(true) + sentenceSound(true),
                BACK_START + wordSound(false) + details + SENTENCE_HTML + TRANSLATION_HTML + sentenceSound(false) + source,
            )
        CardFront.SENTENCE ->
            AnkiCardTemplate(
                SENTENCE_FRONT_HTML + picture + wordSound(true) + sentenceSound(true),
                BACK_START + WORD_HTML + wordSound(false) + details + TRANSLATION_HTML + sentenceSound(false) + source,
            )
        CardFront.LISTENING ->
            AnkiCardTemplate(
                "$SENTENCE_AUDIO{{^Sentence audio}}$SENTENCE_HTML{{/Sentence audio}}" + picture + wordSound(true),
                BACK_START + "{{#Sentence audio}}$SENTENCE_HTML{{/Sentence audio}}" +
                    WORD_HTML + wordSound(false) + details + TRANSLATION_HTML + source,
            )
    }
}

/**
 * What a note type's template was last set to: the front's storage value, then [FRONT_SOUND_SUFFIX]
 * for the line's sound on the front, a suffix for the word's voice away from its usual side, and
 * [PICTURE_FRONT_SUFFIX] for the picture on the front. Keys stored before the places existed
 * ("word", "word+sound") still name the same template.
 */
internal fun cardTemplateKey(
    front: CardFront,
    sides: CardSides = CardSides(),
): String {
    val usualWordFront = usualWordSoundPlace(front) == CardPlace.FRONT
    val word =
        when (sides.wordSoundFront) {
            null, usualWordFront -> ""
            true -> WORD_FRONT_SUFFIX
            false -> WORD_BACK_SUFFIX
        }
    val sound = if (sides.sentenceSoundFront && front != CardFront.LISTENING) FRONT_SOUND_SUFFIX else ""
    val picture = if (sides.pictureFront) PICTURE_FRONT_SUFFIX else ""
    return front.storageValue + sound + word + picture
}

private const val FRONT_SOUND_SUFFIX = "+sound"
private const val WORD_FRONT_SUFFIX = "+wordfront"
private const val WORD_BACK_SUFFIX = "+wordback"
private const val PICTURE_FRONT_SUFFIX = "+picture"

/**
 * Whether the note type's template has to change: it was last set to [applied] (null: never by this
 * version, so the old fixed word-and-sentence front). A template the learner edited in Anki is left
 * alone until they pick another front or move a sound or the picture.
 */
internal fun cardFrontNeedsUpdate(
    applied: String?,
    wanted: CardFront,
    sides: CardSides = CardSides(),
): Boolean = (applied ?: CardFront.WORD_AND_SENTENCE.storageValue) != cardTemplateKey(wanted, sides)

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
        sides: CardSides = CardSides(),
    )
}

internal class InMemoryCardFronts : CardFrontMemory {
    private val fronts = HashMap<String, String>()

    override fun applied(noteType: String): String? = fronts[noteType]

    override fun remember(
        noteType: String,
        front: CardFront,
        sides: CardSides,
    ) {
        fronts[noteType] = cardTemplateKey(front, sides)
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
        sides: CardSides,
    ) {
        preferences.edit().putString(noteType, cardTemplateKey(front, sides)).apply()
    }
}
