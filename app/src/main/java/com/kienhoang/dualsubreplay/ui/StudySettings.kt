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
internal const val KNOWN_WORDS_PREFERENCE = "known_words_enabled"
internal const val MARK_NEW_WORDS_PREFERENCE = "mark_new_words"
internal const val ANKIDROID_PREFERENCE = "ankidroid_enabled"
internal const val BLUR_BOX_PREFERENCE = "blur_box_enabled"
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
        MARK_NEW_WORDS_PREFERENCE,
        ANKIDROID_PREFERENCE,
        BLUR_BOX_PREFERENCE,
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

/** How many times Repeat each line plays a line. */
internal val STUDY_REPEAT_COUNTS = listOf(2, 3, 5)

internal const val DEFAULT_STUDY_REPEAT_COUNT = 2

internal data class StudySettings(
    val playMode: StudyPlayMode = StudyPlayMode.NORMAL,
    val repeatCount: Int = DEFAULT_STUDY_REPEAT_COUNT,
    val skipSilence: SkipSilence = SkipSilence.OFF,
    /** Off until the user turns it on (or accepts the word card's offer after the first known word). */
    val knownWords: Boolean = false,
    val markNewWords: Boolean = false,
    val ankiDroid: Boolean = true,
    val blurBox: Boolean = false,
    val pageTokenCaptions: PageTokenMode = PageTokenMode.AUTO,
) {
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
        knownWords = preferences.getBoolean(KNOWN_WORDS_PREFERENCE, false),
        markNewWords = preferences.getBoolean(MARK_NEW_WORDS_PREFERENCE, false),
        ankiDroid = preferences.getBoolean(ANKIDROID_PREFERENCE, true),
        blurBox = preferences.getBoolean(BLUR_BOX_PREFERENCE, false),
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
