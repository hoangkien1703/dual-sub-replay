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
import com.kienhoang.dualsubreplay.data.PageTokenMode
import com.kienhoang.dualsubreplay.data.storedPageTokenMode

internal const val STUDY_PLAY_MODE_PREFERENCE = "study_play_mode"
internal const val STUDY_REPEAT_COUNT_PREFERENCE = "study_repeat_count"
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

/** The word card's Kanji section for Japanese words (owner request 2026-10-24), on by default. */
internal const val KANJI_DETAILS_PREFERENCE = "kanji_details"
internal const val PAGE_TOKEN_CAPTIONS_PREFERENCE = "page_token_captions"

/** Automatic page tokens: YouTube wanted a token, so later videos ask with it first. */
internal const val PAGE_TOKEN_NEEDED_PREFERENCE = "page_token_needed"

/** The word card offered to turn Known words on once, after the first "I know this word". */
internal const val KNOWN_WORDS_OFFERED_PREFERENCE = "known_words_offered"

/** The Study tools settings, all reset by Reset all settings. */
internal val STUDY_SETTING_KEYS =
    listOf(
        STUDY_PLAY_MODE_PREFERENCE,
        STUDY_REPEAT_COUNT_PREFERENCE,
        SKIP_SILENCE_PREFERENCE,
        KNOWN_WORDS_PREFERENCE,
        KNOWN_WORDS_AFTER_PREFERENCE,
        MARK_NEW_WORDS_PREFERENCE,
        ANKIDROID_PREFERENCE,
        SEND_TO_APP_PREFERENCE,
        ANKICONNECT_DECK_PREFERENCE,
        BLUR_BOX_PREFERENCE,
        KANJI_DETAILS_PREFERENCE,
        PAGE_TOKEN_CAPTIONS_PREFERENCE,
        PAGE_TOKEN_NEEDED_PREFERENCE,
        KNOWN_WORDS_OFFERED_PREFERENCE,
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
    REPEAT_LINE("repeat_line", R.string.study_play_mode_repeat, R.string.study_play_mode_repeat_description),
    NEW_WORD_LINES("new_word_lines", R.string.study_play_mode_new_word, R.string.study_play_mode_new_word_description),
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

/** How many times Repeat each line plays a line. */
internal val STUDY_REPEAT_COUNTS = listOf(2, 3, 5)

internal const val DEFAULT_STUDY_REPEAT_COUNT = 2

internal data class StudySettings(
    val playMode: StudyPlayMode = StudyPlayMode.NORMAL,
    val repeatCount: Int = DEFAULT_STUDY_REPEAT_COUNT,
    val skipSilence: SkipSilence = SkipSilence.OFF,
    val knownWordsMode: KnownWordsMode = KnownWordsMode.OFF,
    val knownWordsAfter: Int = DEFAULT_KNOWN_WORDS_AFTER,
    val markNewWords: Boolean = false,
    val ankiDroid: Boolean = true,
    val sendTo: FlashcardApp = FlashcardApp.ANKIDROID,
    val ankiConnectDeck: String? = null,
    val blurBox: Boolean = false,
    val kanjiDetails: Boolean = true,
    val pageTokenCaptions: PageTokenMode = PageTokenMode.AUTO,
) {
    /** Known words is on (Automatic or Manual): the score, new-word marks and "I know this word" show. */
    val knownWords: Boolean get() = knownWordsMode != KnownWordsMode.OFF

    /** Pause on lines with one new word needs the known-word list; without it the video plays normally. */
    val effectivePlayMode: StudyPlayMode
        get() = if (playMode == StudyPlayMode.NEW_WORD_LINES && !knownWords) StudyPlayMode.NORMAL else playMode
}

internal fun storedStudyPlayMode(value: String?): StudyPlayMode =
    StudyPlayMode.entries.firstOrNull { it.storageValue == value } ?: StudyPlayMode.NORMAL

internal fun storedSkipSilence(value: String?): SkipSilence =
    SkipSilence.entries.firstOrNull { it.storageValue == value } ?: SkipSilence.OFF

internal fun storedStudyRepeatCount(value: Int): Int = value.takeIf { it in STUDY_REPEAT_COUNTS } ?: DEFAULT_STUDY_REPEAT_COUNT

internal fun readStudySettings(preferences: SharedPreferences): StudySettings =
    StudySettings(
        playMode = storedStudyPlayMode(preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null)),
        repeatCount = storedStudyRepeatCount(preferences.getInt(STUDY_REPEAT_COUNT_PREFERENCE, DEFAULT_STUDY_REPEAT_COUNT)),
        skipSilence = storedSkipSilence(preferences.getString(SKIP_SILENCE_PREFERENCE, null)),
        knownWordsMode = storedKnownWordsMode(preferences.getString(KNOWN_WORDS_PREFERENCE, null)),
        knownWordsAfter = storedKnownWordsAfter(preferences.getInt(KNOWN_WORDS_AFTER_PREFERENCE, DEFAULT_KNOWN_WORDS_AFTER)),
        markNewWords = preferences.getBoolean(MARK_NEW_WORDS_PREFERENCE, false),
        ankiDroid = preferences.getBoolean(ANKIDROID_PREFERENCE, true),
        sendTo = storedFlashcardApp(preferences.getString(SEND_TO_APP_PREFERENCE, null)),
        ankiConnectDeck = preferences.getString(ANKICONNECT_DECK_PREFERENCE, null)?.takeIf { it.isNotBlank() },
        blurBox = preferences.getBoolean(BLUR_BOX_PREFERENCE, false),
        kanjiDetails = preferences.getBoolean(KANJI_DETAILS_PREFERENCE, true),
        pageTokenCaptions = storedPageTokenMode(preferences.all[PAGE_TOKEN_CAPTIONS_PREFERENCE]),
    )

internal fun studyPreferences(context: Context): SharedPreferences = context.getSharedPreferences("dual_sub_preferences", 0)

/**
 * Listen first hides subtitles while the video plays: a line set to show always shows only while
 * paused. Never stays never.
 */
internal fun studyCaptionVisibility(
    stored: CaptionVisibility,
    mode: StudyPlayMode,
): CaptionVisibility = if (mode == StudyPlayMode.LISTEN_FIRST && stored == CaptionVisibility.ALWAYS) CaptionVisibility.PAUSED else stored

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
