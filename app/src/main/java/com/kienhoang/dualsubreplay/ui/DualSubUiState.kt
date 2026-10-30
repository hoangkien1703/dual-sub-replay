package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.aiTranslationModel
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.translation.GOOGLE_RECHECK_INTERVAL_MS
import com.kienhoang.dualsubreplay.translation.GoogleWait
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.launch
import com.kienhoang.dualsubreplay.data.activeWordIndex as timedActiveWordIndex

enum class LoadStage { IDLE, LOADING_CAPTIONS, TRANSLATING, READY, ERROR }

data class DualSubUiState(
    val liveFallback: Boolean = false,
    val liveOriginal: String? = null,
    val liveTranslated: String? = null,
    val retryingTranscript: Boolean = false,
    /** Why the full transcript is missing while live subtitles stand in, or null. */
    val fullTranscriptProblem: String? = null,
    val browserUrl: String = YOUTUBE_HOME_URL,
    val browserNavigationRequestId: Long = 0L,
    /** The menu's YouTube tabs; the open one is shown in the one YouTube page, none shows an empty page. */
    val browserTabs: BrowserTabs = BrowserTabs(),
    val activeVideoId: String? = null,
    val subtitlePanelVisible: Boolean = true,
    val sourcePreference: String = "auto",
    val targetLanguage: String = "vi",
    /** Recent "Translate to" picks, newest first; see [recentLanguagesToShow]. */
    val recentTargetLanguages: List<String> = emptyList(),
    val onboardingCompleted: Boolean = false,
    val guideCompleted: Boolean = false,
    val dailyGoalPromptCompleted: Boolean = false,
    val dailyGoalMinutes: Int = 0,
    val availableSourceLanguages: List<CaptionLanguage> = emptyList(),
    /**
     * The caption track the user picked for [activeVideoId] (its `vssId`), or null to let the
     * language decide. Another video starts from the language again.
     */
    val pickedTrack: String? = null,
    /** The id of the caption track the transcript came from; counts only while [availableSourceLanguages] lists it. */
    val loadedTrackId: String = "",
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val playbackPaused: Boolean = false,
    /** Settings → Study tools → Play mode; Listen first shows subtitles only while paused. */
    val studyPlayMode: StudyPlayMode = StudyPlayMode.NORMAL,
    val originalVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    val translatedVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    val currentIndex: Int = -1,
    val activeWordIndex: Int = -1,
    val fontScale: Float = 1f,
    val portraitPanelOffsetFraction: Float = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
    val landscapeSplitEnabled: Boolean = true,
    val originalColorKey: String = DEFAULT_ORIGINAL_COLOR_KEY,
    val translatedColorKey: String = DEFAULT_TRANSLATED_COLOR_KEY,
    val highlightColorKey: String = DEFAULT_HIGHLIGHT_COLOR_KEY,
    val wordHighlightEnabled: Boolean = true,
    val customColorsEnabled: Boolean = true,
    val captionFormat: CaptionFormat = CaptionFormat.SHORT_PHRASES,
    val translationPosition: TranslationPosition = TranslationPosition.BELOW,
    val isDownloadingTranslationModel: Boolean = false,
    val lockOverlayToVideo: Boolean = false,
    val preloadModelsEnabled: Boolean = true,
    val naturalSubtitlesEnabled: Boolean = true,
    val hideSoundCues: Boolean = true,
    val wordLearningEnabled: Boolean = true,
    val wordLearningTarget: String = DEFAULT_WORD_LEARNING_TARGET,
    val wordLearningActiveOnly: Boolean = true,
    val tapToLearnEnabled: Boolean = true,
    val selectedLearningWord: LearningWordSelection? = null,
    val autoPronounce: Boolean = true,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    /** Why translation stopped while the original captions keep playing, or null. */
    val translationError: String? = null,
    val translationEngine: TranslationEngine = TranslationEngine.ON_DEVICE,
    /** Settings → Translation → Wait for Google: how long a row waits for Google before translating without it. */
    val googleWait: GoogleWait = GoogleWait.AUTO,
    /** Settings → Translation → Translate subtitles with an AI choice: the AI assistant's service translates, Google fills the gaps. */
    val aiTranslation: Boolean = false,
    /** With [aiTranslation], rows wait for the AI instead of showing Google's lines first. */
    val aiTranslationOnly: Boolean = false,
    /** Settings → Translation → AI model for translation; empty uses the AI assistant's model. */
    val aiTranslationModel: String = "",
    /**
     * Google failed, so this video translates on the device. The saved engine is unchanged: Google is
     * checked again every [GOOGLE_RECHECK_INTERVAL_MS] and tried again on the next load.
     */
    val onDeviceFallback: Boolean = false,
    /** During [onDeviceFallback], the lines come from YouTube's own translated captions (the device fills gaps). */
    val youTubeTranslationFallback: Boolean = false,
    /** Why Google failed (English diagnostic text, e.g. the HTTP status), shown in the top-right problem details. */
    val onlineTranslationFailureDetail: String? = null,
    /** Why the AI translator's last request failed while [aiTranslation] is on; Google's lines show meanwhile. */
    val aiTranslationProblem: String? = null,
)

/** [state] translating with Google again, without the fallback and its reasons. */
internal fun withGoogleAgain(state: DualSubUiState): DualSubUiState =
    state.copy(
        onDeviceFallback = false,
        youTubeTranslationFallback = false,
        onlineTranslationFailureDetail = null,
        translationError = null,
        aiTranslationProblem = null,
    )

/** The engine actually translating right now: Google, unless this video fell back to on-device. */
internal fun DualSubUiState.translatesWithGoogle(): Boolean = translationEngine == TranslationEngine.GOOGLE_WEB && !onDeviceFallback

internal fun DualSubUiState.onlineTranslationSettings() =
    OnlineTranslationSettings(translationEngine, googleWait, aiTranslation, aiTranslationOnly, aiTranslationModel)

/**
 * A source-language choice is only rejected when the video's track list is
 * known AND clearly does not contain the requested language. While a load is
 * still in flight (empty list) every valid selection must be accepted so
 * changing the subtitle language mid-video always takes effect (issue #20).
 */
internal fun shouldAcceptSourcePreference(
    requested: String,
    availableSourceLanguages: List<CaptionLanguage>,
): Boolean {
    val normalized = TranslationLanguages.normalize(requested)
    if (normalized == "auto") return true
    if (availableSourceLanguages.isEmpty()) return true
    return availableSourceLanguages.any {
        TranslationLanguages.normalize(it.code) == normalized
    }
}

/** Playback tracking only restarts from zero when a different video loads. */
internal fun shouldResetPlaybackClock(
    previousVideoId: String?,
    newVideoId: String,
): Boolean = previousVideoId != newVideoId

internal fun activeSubtitleIndex(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): Int {
    var low = 0
    var high = segments.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (segments[middle].startMs <= timeMs) {
            candidate = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return candidate.takeIf { it >= 0 && timeMs < segments[it].endMs } ?: -1
}

internal fun nearestSegmentIndex(
    segments: List<SubtitleSegment>,
    timeMs: Long,
): Int {
    var low = 0
    var high = segments.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (segments[middle].startMs <= timeMs) {
            candidate = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return candidate.coerceAtLeast(0)
}

/** Word currently being spoken inside [segmentIndex]; -1 when none is tracked. */
internal fun activeWordIndex(
    segments: List<SubtitleSegment>,
    segmentIndex: Int,
    timeMs: Long,
): Int =
    segments
        .getOrNull(segmentIndex)
        ?.takeIf { segment -> segment.startMs <= timeMs && timeMs < segment.endMs }
        ?.let { segment -> timedActiveWordIndex(segment.words, timeMs) }
        ?: -1

internal const val YOUTUBE_HOME_URL = "https://m.youtube.com/"

internal fun preferredCaptionLanguages(sourcePreference: String): List<String> =
    sourcePreference.takeUnless { it == "auto" }?.let(::listOf).orEmpty()

/** The start of a source choice that names one caption track instead of a language. */
internal const val TRACK_CHOICE_PREFIX = "track:"

/** The source choice for [language]'s own track, or its language code when the track is unknown. */
internal fun trackChoiceCode(language: CaptionLanguage): String =
    if (language.trackId.isBlank()) language.code else TRACK_CHOICE_PREFIX + language.trackId

/** The track a source choice names, if it names one the video has. */
internal fun pickedCaptionTrack(
    choice: String,
    availableSourceLanguages: List<CaptionLanguage>,
): CaptionLanguage? =
    choice
        .takeIf { it.startsWith(TRACK_CHOICE_PREFIX) }
        ?.removePrefix(TRACK_CHOICE_PREFIX)
        ?.let { id -> availableSourceLanguages.firstOrNull { it.trackId == id } }

/** What the source list shows as chosen: Auto, the loaded track, or the chosen language while loading. */
internal fun sourceChoiceSelection(state: DualSubUiState): String =
    when {
        state.sourcePreference == "auto" -> "auto"
        state.loadedTrackId.isNotBlank() && state.availableSourceLanguages.any { it.trackId == state.loadedTrackId } ->
            TRACK_CHOICE_PREFIX + state.loadedTrackId
        else -> state.sourcePreference
    }

internal fun resolvedSourcePreference(
    requested: String,
    resolved: String,
): String =
    requested.takeIf {
        it == "auto" || TranslationLanguages.normalize(it) == TranslationLanguages.normalize(resolved)
    } ?: "auto"

/**
 * The language the user studies, which Auto uses when a video gives no clearer sign of its spoken
 * language: the onboarding choice, or else the caption language they last picked by hand. Unlike
 * the source preference it never falls back to Auto after a video without that language.
 */
internal fun storedLearningLanguage(
    learning: String?,
    preferredCaption: String?,
): String? =
    listOf(learning, preferredCaption).firstNotNullOfOrNull { raw ->
        raw?.takeIf { it != "auto" }?.let(::normalizeSupportedLanguage)
    }

internal fun storedSourcePreference(raw: String?): String =
    raw
        ?.takeIf { it != "auto" }
        ?.let(::normalizeSupportedLanguage)
        ?: "auto"

private fun normalizeSupportedLanguage(code: String): String? {
    val normalized = TranslationLanguages.normalize(code)
    return normalized.takeIf(TranslationLanguages::isSupported)
}

internal fun normalizedOnboardingLanguages(
    nativeLanguage: String,
    learningLanguage: String,
): Pair<String, String>? {
    val native =
        TranslationLanguages.normalize(nativeLanguage).takeIf(TranslationLanguages::isSupported)
            ?: return null
    val learning =
        TranslationLanguages.normalize(learningLanguage).takeIf(TranslationLanguages::isSupported)
            ?: return null
    return native to learning
}

internal const val GUIDE_COMPLETED_PREFERENCE = "guide_completed"
internal const val DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE = "daily_goal_prompt_completed"
internal const val DAILY_GOAL_MINUTES_PREFERENCE = "daily_goal_minutes"
internal const val SPLIT_LONG_SENTENCES_PREFERENCE = "split_long_sentences"
internal const val LOCK_OVERLAY_TO_VIDEO_PREFERENCE = "lock_overlay_to_video_player"
internal const val PRELOAD_MODELS_ENABLED_PREFERENCE = "preload_translation_models"

internal const val NATURAL_SUBTITLES_PREFERENCE = "enhanced_natural_subtitles"
internal const val HIDE_SOUND_CUES_PREFERENCE = "hide_sound_cues"
internal const val WORD_LEARNING_ENABLED_PREFERENCE = "word_learning_mode_enabled"
internal const val WORD_LEARNING_TARGET_PREFERENCE = "word_learning_target"

/** POS colors go on the original line only until the learner picks Translation or Both. */
internal const val DEFAULT_WORD_LEARNING_TARGET = "original"
internal const val TAP_TO_LEARN_PREFERENCE = "tap_to_learn_enabled"
internal const val WORD_LEARNING_ACTIVE_ONLY_PREFERENCE = "word_learning_active_only"
internal const val MIN_FONT_SCALE = 0.8f
internal const val MAX_FONT_SCALE = 2f

/** Subtitle text size multiplier; the slider and stored value share these bounds (issue #88). */
internal fun normalizeFontScale(scale: Float): Float = if (scale.isFinite()) scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) else 1f

/**
 * The "guide_completed" preference only exists after the first-launch guide has
 * been finished once, so a missing preference means: show the guide to
 * brand-new users while treating users who onboarded before the guide existed
 * as already having seen it.
 */
internal fun initialGuideCompleted(
    preferenceExists: Boolean,
    preferenceValue: Boolean,
    onboardingCompleted: Boolean,
): Boolean = if (preferenceExists) preferenceValue else onboardingCompleted
