package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AI_REPEAT_FOREVER
import com.kienhoang.dualsubreplay.assistant.AI_STUDY_REPEAT_RANGE
import com.kienhoang.dualsubreplay.data.CardFront
import com.kienhoang.dualsubreplay.data.CardOptions
import com.kienhoang.dualsubreplay.data.CardPlace
import com.kienhoang.dualsubreplay.data.CardReadAloud
import com.kienhoang.dualsubreplay.data.CardSoundFirst
import com.kienhoang.dualsubreplay.data.PageTokenMode
import com.kienhoang.dualsubreplay.data.cardSoundFirstFor
import com.kienhoang.dualsubreplay.data.pickedCardSoundFirst
import com.kienhoang.dualsubreplay.data.storedCardFront
import com.kienhoang.dualsubreplay.data.storedCardPlace
import com.kienhoang.dualsubreplay.data.storedPageTokenMode
import com.kienhoang.dualsubreplay.data.usualWordSoundPlace

internal const val STUDY_PLAY_MODE_PREFERENCE = "study_play_mode"
internal const val STUDY_REPEAT_COUNT_PREFERENCE = "study_repeat_count"

/** Repeat each line → Pause between repeats (ansidalia-lansi, Discussion #257, 2026-11-23): a [RepeatPause] storage value, off by default. */
internal const val STUDY_REPEAT_PAUSE_PREFERENCE = "study_repeat_pause"

/** Study tools → Slow replay on a second tap (25kiuo, Discussion #81, 2026-11-21), off by default. */
internal const val SLOW_REPLAY_PREFERENCE = "slow_replay_second_tap"
internal const val SKIP_SILENCE_PREFERENCE = "skip_silence"
internal const val KNOWN_WORDS_PREFERENCE = "known_words_mode"
internal const val KNOWN_WORDS_AFTER_PREFERENCE = "known_words_after"
internal const val MARK_NEW_WORDS_PREFERENCE = "mark_new_words"
internal const val ANKIDROID_PREFERENCE = "ankidroid_enabled"

/** Where Send to sends words: the last app picked on the button's list or in Settings. */
internal const val SEND_TO_APP_PREFERENCE = "send_to_app"

/** The AnkiConnect deck picked in Settings, or absent for Automatic. */
internal const val ANKICONNECT_DECK_PREFERENCE = "ankiconnect_deck"
internal const val BLUR_BOX_PREFERENCE = "blur_box_enabled"

/** Study tools → Send to → Add audio to cards (owner pick 2026-11-09), on by default. */
internal const val CARD_AUDIO_PREFERENCE = "anki_card_audio"

/** Flashcards & Anki → Add a picture to cards (owner pick 2026-11-10), on by default. */
internal const val CARD_PICTURE_PREFERENCE = "anki_card_picture"

/** Flashcards & Anki → Card front (owner pick 2026-11-10): a [CardFront] storage value. */
internal const val CARD_FRONT_PREFERENCE = "anki_card_front"

/** Flashcards & Anki → Card front → Sentence sound on the front (lion737, #81, 2026-11-11), off by default. */
internal const val CARD_FRONT_SOUND_PREFERENCE = "anki_card_front_sound"

/**
 * Flashcards & Anki → Word sound, Sentence sound and Picture (lion737, #81, 2026-11-12): each a
 * [CardPlace] storage value (Front, Back or Off). They replace Add audio to cards, Sentence sound on
 * the front and Add a picture to cards, which only decide the first value (see [readStudySettings]).
 */
internal const val CARD_WORD_SOUND_PREFERENCE = "anki_card_word_sound"
internal const val CARD_SENTENCE_SOUND_PREFERENCE = "anki_card_sentence_sound"
internal const val CARD_PICTURE_PLACE_PREFERENCE = "anki_card_picture_place"

/** Flashcards & Anki → Play first (lion737, #81, 2026-11-13): a [CardSoundFirst] storage value, the word by default. */
internal const val CARD_SOUND_FIRST_PREFERENCE = "anki_card_sound_first"

/** Flashcards & Anki → Reading (2026-11-16): a [CardPlace] storage value, the back by default. */
internal const val CARD_READING_PLACE_PREFERENCE = "anki_card_reading_place"

/**
 * Flashcards & Anki → Read aloud on the back (lion737, #81, 2026-11-15; owner pick 2026-11-16: all
 * on by default): the meaning in this sentence, the dictionary meaning and the sentence translation.
 */
internal const val CARD_READ_MEANING_PREFERENCE = "anki_card_read_meaning"
internal const val CARD_READ_DICTIONARY_PREFERENCE = "anki_card_read_dictionary"
internal const val CARD_READ_TRANSLATION_PREFERENCE = "anki_card_read_translation"

/** The word card's Kanji section for Japanese words (owner request 2026-10-24), on by default. */
internal const val KANJI_DETAILS_PREFERENCE = "kanji_details"
internal const val PAGE_TOKEN_CAPTIONS_PREFERENCE = "page_token_captions"

/** Automatic page tokens: YouTube wanted a token, so later videos ask with it first. */
internal const val PAGE_TOKEN_NEEDED_PREFERENCE = "page_token_needed"

/** Study tools → Loop button on the playing line (owner request 2026-11-06), off by default. */
internal const val LOOP_BUTTON_PREFERENCE = "line_loop_button"

/**
 * Flashcards & Anki → Also send saved words (owner pick 2026-11-17): Save to Practice on the word card
 * also sends the card to the Send to app. Off by default.
 */
internal const val SEND_ON_SAVE_PREFERENCE = "send_saved_words"

/**
 * Flashcards & Anki → Video example for saved words (owner pick 2026-11-17): was the word card's
 * "Online example" box. On by default, as the box was; each word can still change it in Practice.
 */
internal const val SAVED_WORD_VIDEO_EXAMPLE_PREFERENCE = "saved_word_video_example"

/** The Study tools settings, all reset by Reset all settings. */
internal val STUDY_SETTING_KEYS =
    listOf(
        STUDY_PLAY_MODE_PREFERENCE,
        STUDY_REPEAT_COUNT_PREFERENCE,
        STUDY_REPEAT_PAUSE_PREFERENCE,
        SLOW_REPLAY_PREFERENCE,
        SKIP_SILENCE_PREFERENCE,
        KNOWN_WORDS_PREFERENCE,
        KNOWN_WORDS_AFTER_PREFERENCE,
        MARK_NEW_WORDS_PREFERENCE,
        ANKIDROID_PREFERENCE,
        SEND_TO_APP_PREFERENCE,
        ANKICONNECT_DECK_PREFERENCE,
        CARD_AUDIO_PREFERENCE,
        CARD_PICTURE_PREFERENCE,
        CARD_FRONT_PREFERENCE,
        CARD_FRONT_SOUND_PREFERENCE,
        CARD_WORD_SOUND_PREFERENCE,
        CARD_SENTENCE_SOUND_PREFERENCE,
        CARD_PICTURE_PLACE_PREFERENCE,
        CARD_SOUND_FIRST_PREFERENCE,
        CARD_READING_PLACE_PREFERENCE,
        CARD_READ_MEANING_PREFERENCE,
        CARD_READ_DICTIONARY_PREFERENCE,
        CARD_READ_TRANSLATION_PREFERENCE,
        BLUR_BOX_PREFERENCE,
        LOOP_BUTTON_PREFERENCE,
        KANJI_DETAILS_PREFERENCE,
        PAGE_TOKEN_CAPTIONS_PREFERENCE,
        PAGE_TOKEN_NEEDED_PREFERENCE,
        SEND_ON_SAVE_PREFERENCE,
        SAVED_WORD_VIDEO_EXAMPLE_PREFERENCE,
    )

/** How the video plays around each subtitle line (Settings → Study tools → Play mode). */
enum class StudyPlayMode(
    val storageValue: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    NORMAL("normal", R.string.study_play_mode_normal, R.string.study_play_mode_normal_description),
    PAUSE_AFTER_LINE("pause_after_line", R.string.study_play_mode_pause_after, R.string.study_play_mode_pause_after_description),
    READ_FIRST("read_first", R.string.study_play_mode_read_first, R.string.study_play_mode_read_first_description),
    LISTEN_FIRST("listen_first", R.string.study_play_mode_listen_first, R.string.study_play_mode_listen_first_description),

    /** Lines stay hidden until the learner checks what they typed (owner pick 2026-11-09); see `Dictation.kt`. */
    DICTATION("dictation", R.string.study_play_mode_dictation, R.string.study_play_mode_dictation_description),
    REPEAT_LINE("repeat_line", R.string.study_play_mode_repeat, R.string.study_play_mode_repeat_description),
    NEW_WORD_LINES("new_word_lines", R.string.study_play_mode_new_word, R.string.study_play_mode_new_word_description),
}

/**
 * The pause after each play of a line in Repeat each line, so the learner can say it aloud
 * (shadowing; ansidalia-lansi, Discussion #257, 2026-11-23). One choice instead of separate count,
 * length and "after the last one" settings: [LINE] waits as long as the line took, the usual
 * shadowing gap.
 */
internal enum class RepeatPause(
    val storageValue: String,
    @StringRes val labelRes: Int,
) {
    OFF("off", R.string.study_repeat_pause_off),
    LINE("line", R.string.study_repeat_pause_line),
    TWO_SECONDS("2", R.string.study_repeat_pause_2),
    FIVE_SECONDS("5", R.string.study_repeat_pause_5),
}

internal fun storedRepeatPause(value: String?): RepeatPause =
    RepeatPause.entries.firstOrNull { it.storageValue == value } ?: RepeatPause.OFF

/** [LINE]'s pause stays in this range, so a one-word line still leaves time and a long one never stalls. */
internal val REPEAT_PAUSE_LINE_RANGE_MS = 1_000L..15_000L

/** How long the video waits after a play of a line [lineMs] long, or null for no pause. */
internal fun repeatPauseMs(
    pause: RepeatPause,
    lineMs: Long,
): Long? =
    when (pause) {
        RepeatPause.OFF -> null
        RepeatPause.LINE -> lineMs.coerceIn(REPEAT_PAUSE_LINE_RANGE_MS)
        RepeatPause.TWO_SECONDS -> 2_000L
        RepeatPause.FIVE_SECONDS -> 5_000L
    }

/** What happens in a part with no subtitle line (Settings → Study tools → Skip silence). */
internal enum class SkipSilence(
    val storageValue: String,
    @StringRes val labelRes: Int,
    /** The speed in such a part, or null when it is jumped over (or nothing happens). */
    val rate: Float?,
) {
    OFF("off", R.string.study_skip_silence_off, null),
    DOUBLE("2x", R.string.study_skip_silence_2x, 2f),
    QUADRUPLE("4x", R.string.study_skip_silence_4x, 4f),
    JUMP("jump", R.string.study_skip_silence_jump, null),
}

/**
 * Settings → Study tools → Known words (owner decision 2026-10-20). [AUTO] also counts a word as
 * known once the user saw it in [StudySettings.knownWordsAfter] played lines; [MANUAL] uses only
 * "I know this word"; [OFF] (the default) hides the score, and the word card's first "I know this
 * word" offers once to turn Automatic on.
 */
internal enum class KnownWordsMode(
    val storageValue: String,
    @StringRes val labelRes: Int,
) {
    AUTO("auto", R.string.study_known_words_auto),
    MANUAL("manual", R.string.study_known_words_manual),
    OFF("off", R.string.study_known_words_off),
}

/**
 * The flashcard app the Send to button sends to (owner decision 2026-10-23): AnkiDroid by default,
 * or AnkiConnect, which Yomitan, asbplayer and other tools also use. Its name is a brand name.
 */
internal enum class FlashcardApp(
    val storageValue: String,
    @StringRes val nameRes: Int,
) {
    ANKIDROID("ankidroid", R.string.study_app_ankidroid),
    ANKICONNECT("ankiconnect", R.string.study_app_ankiconnect),
}

internal fun storedFlashcardApp(value: String?): FlashcardApp =
    FlashcardApp.entries.firstOrNull { it.storageValue == value } ?: FlashcardApp.ANKIDROID

/** How many played lines Automatic known words needs to count a word as known. */
internal val KNOWN_WORDS_AFTER_CHOICES = listOf(5, 10, 20)

internal const val DEFAULT_KNOWN_WORDS_AFTER = 10

internal fun storedKnownWordsMode(value: String?): KnownWordsMode =
    KnownWordsMode.entries.firstOrNull { it.storageValue == value } ?: KnownWordsMode.OFF

internal fun storedKnownWordsAfter(value: Int): Int = value.takeIf { it in KNOWN_WORDS_AFTER_CHOICES } ?: DEFAULT_KNOWN_WORDS_AFTER

/** Repeat each line's count that plays a line until the learner taps another line or seeks (2026-11-03). */
internal const val STUDY_REPEAT_FOREVER = 0

/** How many times Repeat each line plays a line; [STUDY_REPEAT_FOREVER] has no limit. */
internal val STUDY_REPEAT_COUNTS = listOf(2, 3, 5, 10, STUDY_REPEAT_FOREVER)

/**
 * Custom picks any count in this range (25kiuo, Discussion #81, 2026-11-17, asked for any number of
 * plays; a slider with − and + was picked over a slider alone, which can't hit an exact number).
 */
internal val STUDY_REPEAT_CUSTOM_RANGE = AI_STUDY_REPEAT_RANGE

internal const val DEFAULT_STUDY_REPEAT_COUNT = 2

internal data class StudySettings(
    val playMode: StudyPlayMode = StudyPlayMode.NORMAL,
    val repeatCount: Int = DEFAULT_STUDY_REPEAT_COUNT,
    val repeatPause: RepeatPause = RepeatPause.OFF,
    /** Tapping the line that is replaying again plays it once more, slower. */
    val slowReplay: Boolean = false,
    val loopButton: Boolean = false,
    val skipSilence: SkipSilence = SkipSilence.OFF,
    val knownWordsMode: KnownWordsMode = KnownWordsMode.OFF,
    val knownWordsAfter: Int = DEFAULT_KNOWN_WORDS_AFTER,
    val markNewWords: Boolean = false,
    val ankiDroid: Boolean = true,
    val sendTo: FlashcardApp = FlashcardApp.ANKIDROID,
    val ankiConnectDeck: String? = null,
    /** Save to Practice on the word card also sends the card to [sendTo]. */
    val sendOnSave: Boolean = false,
    /** New saved words get the Play online example button in Practice. */
    val savedWordVideoExample: Boolean = true,
    /** Where cards carry the word's voice. */
    val cardWordSound: CardPlace = CardPlace.FRONT,
    /** Where cards carry the line's real sound from the video. */
    val cardSentenceSound: CardPlace = CardPlace.BACK,
    /** Where cards carry a still of the line from the video (else the video's thumbnail). */
    val cardPicture: CardPlace = CardPlace.OFF,
    /** The learner's Play first pick; null when never picked, so [cardSoundFirst] follows the front. */
    val cardSoundFirstPicked: CardSoundFirst? = null,
    val cardFront: CardFront = CardFront.WORD_AND_SENTENCE,
    /** Where cards show the word's reading (kana, pinyin, English IPA). */
    val cardReading: CardPlace = CardPlace.BACK,
    /** Which parts of the back Anki reads aloud. */
    val cardReadAloud: CardReadAloud = CardReadAloud(),
    val blurBox: Boolean = false,
    val kanjiDetails: Boolean = true,
    val pageTokenCaptions: PageTokenMode = PageTokenMode.AUTO,
) {
    /** Known words is on (Automatic or Manual): the score, new-word marks and the word card's "I know this word" show. */
    val knownWords: Boolean get() = knownWordsMode != KnownWordsMode.OFF

    /** Which sound plays first on a card side with both. */
    val cardSoundFirst: CardSoundFirst get() = cardSoundFirstFor(cardFront, cardSoundFirstPicked)

    /** What Send to puts on each card. */
    val cardOptions: CardOptions get() =
        CardOptions(
            wordSound = cardWordSound,
            sentenceSound = cardSentenceSound,
            picture = cardPicture,
            front = cardFront,
            soundFirst = cardSoundFirstPicked,
            reading = cardReading,
            readAloud = cardReadAloud,
        )

    /** Pause on lines with one new word needs the known-word list; without it the video plays normally. */
    val effectivePlayMode: StudyPlayMode
        get() = if (playMode == StudyPlayMode.NEW_WORD_LINES && !knownWords) StudyPlayMode.NORMAL else playMode
}

internal fun storedStudyPlayMode(value: String?): StudyPlayMode =
    StudyPlayMode.entries.firstOrNull { it.storageValue == value } ?: StudyPlayMode.NORMAL

internal fun storedSkipSilence(value: String?): SkipSilence =
    SkipSilence.entries.firstOrNull { it.storageValue == value } ?: SkipSilence.OFF

internal fun storedStudyRepeatCount(value: Int): Int =
    value.takeIf { it == STUDY_REPEAT_FOREVER || it in STUDY_REPEAT_CUSTOM_RANGE } ?: DEFAULT_STUDY_REPEAT_COUNT

/** The AI assistant's study_repeat_count ("2" to "99", or "forever") as a stored count. */
internal fun aiStudyRepeatCount(value: String): Int =
    if (value == AI_REPEAT_FOREVER) {
        STUDY_REPEAT_FOREVER
    } else {
        storedStudyRepeatCount(value.toIntOrNull() ?: DEFAULT_STUDY_REPEAT_COUNT)
    }

internal fun readStudySettings(preferences: SharedPreferences): StudySettings =
    StudySettings(
        playMode = storedStudyPlayMode(preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null)),
        repeatCount = storedStudyRepeatCount(preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, DEFAULT_STUDY_REPEAT_COUNT)),
        repeatPause = storedRepeatPause(preferences.getString(STUDY_REPEAT_PAUSE_PREFERENCE, null)),
        slowReplay = preferences.getBoolean(SLOW_REPLAY_PREFERENCE, false),
        loopButton = preferences.getBoolean(LOOP_BUTTON_PREFERENCE, false),
        skipSilence = storedSkipSilence(preferences.getString(SKIP_SILENCE_PREFERENCE, null)),
        knownWordsMode = storedKnownWordsMode(preferences.getString(KNOWN_WORDS_PREFERENCE, null)),
        knownWordsAfter = storedKnownWordsAfter(preferences.getInt(KNOWN_WORDS_AFTER_PREFERENCE, DEFAULT_KNOWN_WORDS_AFTER)),
        markNewWords = preferences.getBoolean(MARK_NEW_WORDS_PREFERENCE, false),
        ankiDroid = preferences.getBoolean(ANKIDROID_PREFERENCE, true),
        sendTo = storedFlashcardApp(preferences.getString(SEND_TO_APP_PREFERENCE, null)),
        ankiConnectDeck = preferences.getString(ANKICONNECT_DECK_PREFERENCE, null)?.takeIf { it.isNotBlank() },
        sendOnSave = preferences.getBoolean(SEND_ON_SAVE_PREFERENCE, false),
        savedWordVideoExample = preferences.getBoolean(SAVED_WORD_VIDEO_EXAMPLE_PREFERENCE, true),
        cardWordSound = storedCardWordSound(preferences),
        cardSentenceSound = storedCardSentenceSound(preferences),
        // Add a picture to cards put it on the back.
        cardPicture =
            storedCardPlace(
                preferences.getString(CARD_PICTURE_PLACE_PREFERENCE, null),
                otherwise = if (preferences.getBoolean(CARD_PICTURE_PREFERENCE, false)) CardPlace.BACK else CardPlace.OFF,
            ),
        cardSoundFirstPicked = pickedCardSoundFirst(preferences.getString(CARD_SOUND_FIRST_PREFERENCE, null)),
        cardFront = storedCardFront(preferences.getString(CARD_FRONT_PREFERENCE, null)),
        cardReading = storedCardPlace(preferences.getString(CARD_READING_PLACE_PREFERENCE, null), otherwise = CardPlace.BACK),
        cardReadAloud =
            CardReadAloud(
                meaningInSentence = preferences.getBoolean(CARD_READ_MEANING_PREFERENCE, true),
                dictionaryMeaning = preferences.getBoolean(CARD_READ_DICTIONARY_PREFERENCE, false),
                translation = preferences.getBoolean(CARD_READ_TRANSLATION_PREFERENCE, false),
            ),
        blurBox = preferences.getBoolean(BLUR_BOX_PREFERENCE, false),
        kanjiDetails = preferences.getBoolean(KANJI_DETAILS_PREFERENCE, true),
        pageTokenCaptions = storedPageTokenMode(preferences.all[PAGE_TOKEN_CAPTIONS_PREFERENCE]),
    )

/** The word's voice: its own setting, else what Add audio to cards and the card front gave before it existed. */
private fun storedCardWordSound(preferences: SharedPreferences): CardPlace =
    storedCardPlace(
        preferences.getString(CARD_WORD_SOUND_PREFERENCE, null),
        otherwise =
            earlierCardWordSound(
                audio = preferences.getBoolean(CARD_AUDIO_PREFERENCE, true),
                front = storedCardFront(preferences.getString(CARD_FRONT_PREFERENCE, null)),
            ),
    )

/** The line's sound: its own setting, else what Add audio to cards and Sentence sound on the front gave. */
private fun storedCardSentenceSound(preferences: SharedPreferences): CardPlace =
    storedCardPlace(
        preferences.getString(CARD_SENTENCE_SOUND_PREFERENCE, null),
        otherwise =
            earlierCardSentenceSound(
                audio = preferences.getBoolean(CARD_AUDIO_PREFERENCE, true),
                frontSound = preferences.getBoolean(CARD_FRONT_SOUND_PREFERENCE, false),
            ),
    )

/** Where the word's voice was before Word sound existed: nowhere without Add audio to cards, else with the word. */
internal fun earlierCardWordSound(
    audio: Boolean,
    front: CardFront,
): CardPlace = if (audio) usualWordSoundPlace(front) else CardPlace.OFF

/** Where the line's sound was before Sentence sound existed. */
internal fun earlierCardSentenceSound(
    audio: Boolean,
    frontSound: Boolean,
): CardPlace =
    when {
        !audio -> CardPlace.OFF
        frontSound -> CardPlace.FRONT
        else -> CardPlace.BACK
    }

internal fun studyPreferences(context: Context): SharedPreferences = context.getSharedPreferences("dual_sub_preferences", 0)

/**
 * Listen first hides subtitles while the video plays: a line set to show always shows only while
 * paused. Never stays never. Dictation does the same outside the subtitle panel (full screen, the
 * overlay); the panel hides each line until it is revealed.
 */
internal fun studyCaptionVisibility(
    stored: CaptionVisibility,
    mode: StudyPlayMode,
): CaptionVisibility = if (mode.hidesWhilePlaying && stored == CaptionVisibility.ALWAYS) CaptionVisibility.PAUSED else stored

/** Listen first and Dictation keep subtitles (and YouTube's own captions) hidden while the video plays. */
internal val StudyPlayMode.hidesWhilePlaying: Boolean
    get() = this == StudyPlayMode.LISTEN_FIRST || this == StudyPlayMode.DICTATION

/** The Study tools settings, following every change to them (from the settings page or the assistant). */
@Composable
internal fun rememberStudySettings(): State<StudySettings> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val settings = remember(preferences) { mutableStateOf(readStudySettings(preferences)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in STUDY_SETTING_KEYS) settings.value = readStudySettings(preferences)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        settings.value = readStudySettings(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings
}
