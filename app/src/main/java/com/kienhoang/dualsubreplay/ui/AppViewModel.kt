package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.ImmersionAccumulator
import com.kienhoang.dualsubreplay.data.ImmersionRepository
import com.kienhoang.dualsubreplay.data.ImmersionTimeTracker
import com.kienhoang.dualsubreplay.data.JapaneseDictionaryStore
import com.kienhoang.dualsubreplay.data.JapaneseMorphology
import com.kienhoang.dualsubreplay.data.LearnerData
import com.kienhoang.dualsubreplay.data.immersionLanguage
import com.kienhoang.dualsubreplay.data.initialDailyGoalPromptCompleted
import com.kienhoang.dualsubreplay.data.storedDailyGoalMinutes
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.PageFirstCaptionProvider
import com.kienhoang.dualsubreplay.data.PageTranscriptProblem
import com.kienhoang.dualsubreplay.data.PageTranscriptSource
import com.kienhoang.dualsubreplay.data.PageTranslationSource
import com.kienhoang.dualsubreplay.data.RecentCaptionTracks
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.YouTubeCaptionProvider
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import com.kienhoang.dualsubreplay.data.retireLegacyDownloadJobs
import com.kienhoang.dualsubreplay.data.savedWordFrom
import com.kienhoang.dualsubreplay.data.storedPageTokenMode
import com.kienhoang.dualsubreplay.translation.DEFAULT_TRANSLATION_ENGINE
import com.kienhoang.dualsubreplay.translation.GOOGLE_EARLY_CHECK_POLL_MS
import com.kienhoang.dualsubreplay.translation.GOOGLE_RECHECK_INTERVAL_MS
import com.kienhoang.dualsubreplay.translation.GOOGLE_TRANSLATE_ADDRESS_KEY
import com.kienhoang.dualsubreplay.translation.GOOGLE_WAIT_PREFERENCE
import com.kienhoang.dualsubreplay.translation.googleAddressMemory
import com.kienhoang.dualsubreplay.translation.GoogleSlowException
import com.kienhoang.dualsubreplay.translation.googleSlowDetail
import com.kienhoang.dualsubreplay.translation.GoogleWait
import com.kienhoang.dualsubreplay.translation.GoogleWebTranslator
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import com.kienhoang.dualsubreplay.translation.TranslationEngine
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.awaitWithin
import com.kienhoang.dualsubreplay.translation.googleWaitMs
import com.kienhoang.dualsubreplay.translation.onWifi
import com.kienhoang.dualsubreplay.translation.storedGoogleWait
import com.kienhoang.dualsubreplay.translation.storedTranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.LocalDate
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
    val activeVideoId: String? = null,
    val subtitlePanelVisible: Boolean = true,
    val sourcePreference: String = "auto",
    val targetLanguage: String = "vi",
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
    /**
     * Google failed, so this video translates on the device. The saved engine is unchanged: Google is
     * checked again every [GOOGLE_RECHECK_INTERVAL_MS] and tried again on the next load.
     */
    val onDeviceFallback: Boolean = false,
    /** During [onDeviceFallback], the lines come from YouTube's own translated captions (the device fills gaps). */
    val youTubeTranslationFallback: Boolean = false,
    /** Why Google failed (English diagnostic text, e.g. the HTTP status), shown in the top-right problem details. */
    val onlineTranslationFailureDetail: String? = null,
)

/** [state] translating with Google again, without the fallback and its reasons. */
private fun withGoogleAgain(state: DualSubUiState): DualSubUiState =
    state.copy(onDeviceFallback = false, youTubeTranslationFallback = false, onlineTranslationFailureDetail = null, translationError = null)

/** The engine actually translating right now: Google, unless this video fell back to on-device. */
internal fun DualSubUiState.translatesWithGoogle(): Boolean = translationEngine == TranslationEngine.GOOGLE_WEB && !onDeviceFallback

internal fun DualSubUiState.onlineTranslationSettings() =
    OnlineTranslationSettings(translationEngine, googleWait)

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

/** The language chosen as "learning" at onboarding; see [storedLearningLanguage]. */
private const val LEARNING_LANGUAGE_PREFERENCE = "learning_language"

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

/** After a switch away from Google, how long a row waits for YouTube's own translation before the device translates it. */
private const val YOUTUBE_TRANSLATION_WAIT_MS = 1_000L
internal const val NATURAL_SUBTITLES_PREFERENCE = "enhanced_natural_subtitles"
internal const val WORD_LEARNING_ENABLED_PREFERENCE = "word_learning_mode_enabled"
internal const val WORD_LEARNING_TARGET_PREFERENCE = "word_learning_target"

/** POS colors go on the original line only until the learner picks Translation or Both. */
internal const val DEFAULT_WORD_LEARNING_TARGET = "original"
internal const val TAP_TO_LEARN_PREFERENCE = "tap_to_learn_enabled"
internal const val WORD_LEARNING_ACTIVE_ONLY_PREFERENCE = "word_learning_active_only"
internal const val MIN_FONT_SCALE = 0.8f
internal const val MAX_FONT_SCALE = 2f

/** Subtitle text size multiplier; the slider and stored value share these bounds (issue #88). */
internal fun normalizeFontScale(scale: Float): Float =
    if (scale.isFinite()) scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) else 1f

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

class AppViewModel internal constructor(
    application: Application,
    private val captionProvider: CaptionProvider,
    private val pageTranscripts: PageTranscriptSource = PageTranscriptSource.Shared,
    private val pageTranslations: PageTranslationSource = PageTranslationSource.Shared,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        // Survives Android closing the app in the background, so returning skips the caption download.
        // The YouTube page fetches the track first; the app's own lookup runs alongside as the fallback.
        RecentCaptionTracks(
            PageFirstCaptionProvider(PageTranscriptSource.Shared, YouTubeCaptionProvider()),
            File(application.cacheDir, "recent-caption-tracks"),
        ),
    )

    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val translator =
        OnDeviceTranslator(
            cacheDirectory = File(application.cacheDir, "subtitle-translations"),
        )
    private val googleTranslator =
        GoogleWebTranslator(
            cacheDirectory = File(application.cacheDir, "google-translations"),
            addressMemory = application.googleAddressMemory(GOOGLE_TRANSLATE_ADDRESS_KEY),
        )

    /** Google failed or was too slow on mobile data and has not answered since. */
    @Volatile private var googleFailedOnMobile = false

    /** This load started without Google, so its first check of Google comes as soon as a row plays. */
    private var checkGoogleSoon = false

    /** The language pair whose on-device model was last downloaded ahead of time on Wi-Fi. */
    private var warmedPair: Pair<String, String>? = null

    internal val vocabulary = VocabularyRepository.get(application)
    internal val immersion = ImmersionRepository.get(application)

    init {
        // Japanese word analysis downloads its dictionary here the first time Japanese is shown.
        JapaneseMorphology.useStore(JapaneseDictionaryStore(File(application.filesDir, "japanese-dictionary")))
        // Chinese word analysis reads data packaged in the APK.
        LearnerData.useAssets(application.assets)
    }

    /** Settings → Translation → Languages on this device. */
    internal val languageDownloads =
        LanguageDownloadsController(
            scope = viewModelScope,
            packs =
                LanguagePacksWithJapaneseDictionary(
                    models =
                        object : LanguagePacks {
                            override suspend fun available() = translator.downloadableLanguages()

                            override suspend fun downloaded() = translator.downloadedLanguages()

                            override suspend fun download(code: String) = translator.downloadLanguage(code)

                            override suspend fun remove(code: String) = translator.removeLanguage(code)
                        },
                    dictionaryInstalled = JapaneseMorphology::isDictionaryInstalled,
                    installDictionary = JapaneseMorphology::installDictionary,
                    removeDictionary = JapaneseMorphology::removeDictionary,
                ),
        )

    private val immersionTracker = ImmersionTimeTracker()
    private val immersionAccumulator = ImmersionAccumulator()
    private var loadingJob: Job? = null
    private var translationWarmupJob: Job? = null
    private var loadGeneration = 0L
    private var latestPlaybackSecondMs = 0L
    private val subtitleDirectory by lazy {
        File(application.cacheDir, "subtitle-transcripts").apply {
            // Cached transcripts belong to this ViewModel; discard leftovers after process death.
            deleteRecursively()
            mkdirs()
        }
    }
    private val playbackRequests = MutableStateFlow(CaptionPlaybackRequest())
    private var playbackKnown = false
    private var appVisible = true
    private val liveCaptionTracker = LiveCaptionTracker()
    private val captionHighlightResolver = CaptionHighlightResolver()
    private var liveCaptionProgress: LiveCaptionProgress? = null
    private var playbackSessionId: String? = null
    private val liveTranslationGate = LiveTranslationGate()
    private var liveTranslationJob: Job? = null
    private var rejectedLiveRevision: Long? = null

    private val _state =
        MutableStateFlow(
            DualSubUiState(
                browserUrl =
                    preferences
                        .getString("last_browser_url", YOUTUBE_HOME_URL)
                        ?.let(::trustedEmbeddedUrlOrHome)
                        ?: YOUTUBE_HOME_URL,
                originalVisibility = storedCaptionVisibility(preferences.getString(ORIGINAL_VISIBILITY, null)),
                translatedVisibility = storedCaptionVisibility(preferences.getString(TRANSLATED_VISIBILITY, null)),
                studyPlayMode = storedStudyPlayMode(preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null)),
                fontScale = normalizeFontScale(preferences.getFloat("font_scale", 1f)),
                portraitPanelOffsetFraction =
                    normalizePortraitPanelOffsetFraction(
                        preferences.getFloat(
                            PORTRAIT_PANEL_OFFSET_PREFERENCE,
                            DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                        ),
                    ),
                sourcePreference =
                    storedSourcePreference(
                        preferences.getString("preferred_caption_language", "auto"),
                    ),
                targetLanguage =
                    preferences
                        .getString("target_language", "vi")
                        ?.takeIf(TranslationLanguages::isSupported)
                        ?: "vi",
                onboardingCompleted = preferences.getBoolean("onboarding_completed", false),
                guideCompleted =
                    initialGuideCompleted(
                        preferenceExists = preferences.contains(GUIDE_COMPLETED_PREFERENCE),
                        preferenceValue = preferences.getBoolean(GUIDE_COMPLETED_PREFERENCE, false),
                        onboardingCompleted = preferences.getBoolean("onboarding_completed", false),
                    ),
                dailyGoalPromptCompleted =
                    initialDailyGoalPromptCompleted(
                        preferenceExists = preferences.contains(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE),
                        preferenceValue = preferences.getBoolean(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE, false),
                        guideCompleted =
                            initialGuideCompleted(
                                preferenceExists = preferences.contains(GUIDE_COMPLETED_PREFERENCE),
                                preferenceValue = preferences.getBoolean(GUIDE_COMPLETED_PREFERENCE, false),
                                onboardingCompleted = preferences.getBoolean("onboarding_completed", false),
                            ),
                    ),
                dailyGoalMinutes = storedDailyGoalMinutes(preferences.getInt(DAILY_GOAL_MINUTES_PREFERENCE, 0)),
                landscapeSplitEnabled = preferences.getBoolean("landscape_split_enabled", true),
                originalColorKey =
                    storedSubtitleColorKey(
                        preferences.getString(SUBTITLE_ORIGINAL_COLOR_PREFERENCE, null),
                        DEFAULT_ORIGINAL_COLOR_KEY,
                    ),
                translatedColorKey =
                    storedSubtitleColorKey(
                        preferences.getString(SUBTITLE_TRANSLATED_COLOR_PREFERENCE, null),
                        DEFAULT_TRANSLATED_COLOR_KEY,
                    ),
                highlightColorKey =
                    storedSubtitleColorKey(
                        preferences.getString(SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE, null),
                        DEFAULT_HIGHLIGHT_COLOR_KEY,
                    ),
                wordHighlightEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, true),
                    ),
                customColorsEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE, true),
                    ),
                captionFormat =
                    storedCaptionFormat(
                        preferences.getString(CAPTION_FORMAT_PREFERENCE, null),
                        preferences.getBoolean(SPLIT_LONG_SENTENCES_PREFERENCE, true),
                    ),
                translationPosition = storedTranslationPosition(preferences.getString(TRANSLATION_POSITION_PREFERENCE, null)),
                lockOverlayToVideo =
                    storedFeatureEnabled(
                        preferences.getBoolean(LOCK_OVERLAY_TO_VIDEO_PREFERENCE, false),
                    ),
                preloadModelsEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, true),
                    ),
                naturalSubtitlesEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(NATURAL_SUBTITLES_PREFERENCE, true),
                    ),
                wordLearningEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_LEARNING_ENABLED_PREFERENCE, true),
                    ),
                wordLearningTarget =
                    preferences.getString(
                        WORD_LEARNING_TARGET_PREFERENCE,
                        DEFAULT_WORD_LEARNING_TARGET,
                    ) ?: DEFAULT_WORD_LEARNING_TARGET,
                wordLearningActiveOnly =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, true),
                    ),
                autoPronounce = preferences.getBoolean("auto_pronounce", true),
                tapToLearnEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(TAP_TO_LEARN_PREFERENCE, true),
                    ),
                translationEngine = storedTranslationEngine(preferences.getString(TRANSLATION_ENGINE_PREFERENCE, null)),
                googleWait = storedGoogleWait(preferences.getString(GOOGLE_WAIT_PREFERENCE, null)),
            ),
        )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

    private val visibilityListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ORIGINAL_VISIBILITY || key == TRANSLATED_VISIBILITY || key == STUDY_PLAY_MODE_PREFERENCE || key == null) {
                _state.update {
                    it.copy(
                        originalVisibility = storedCaptionVisibility(preferences.getString(ORIGINAL_VISIBILITY, null)),
                        translatedVisibility = storedCaptionVisibility(preferences.getString(TRANSLATED_VISIBILITY, null)),
                        studyPlayMode = storedStudyPlayMode(preferences.getString(STUDY_PLAY_MODE_PREFERENCE, null)),
                    )
                }
            }
            if (key == PAGE_TOKEN_CAPTIONS_PREFERENCE || key == PAGE_TOKEN_NEEDED_PREFERENCE || key == null) readPageTokenMode()
        }

    private fun readPageTokenMode() {
        pageTranscripts.pageTokenMode = storedPageTokenMode(preferences.all[PAGE_TOKEN_CAPTIONS_PREFERENCE])
        pageTranscripts.tokenRemembered = preferences.getBoolean(PAGE_TOKEN_NEEDED_PREFERENCE, false)
    }

    override fun onCleared() {
        flushImmersion()
        loadingJob?.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(visibilityListener)
        super.onCleared()
    }

    internal fun onWebPlaybackPaused(
        videoId: String,
        paused: Boolean,
    ) {
        if (_state.value.activeVideoId != videoId) return
        if (paused && !_state.value.playbackPaused) flushImmersion()
        _state.update { it.copy(playbackPaused = paused) }
        updatePlaybackRequest()
    }

    internal fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) {
            immersionTracker.reset()
            flushImmersion()
        }
        updatePlaybackRequest()
    }

    /** Credits watched time to the language being learned; see [ImmersionTimeTracker]. */
    private fun trackImmersion(
        current: DualSubUiState,
        videoId: String,
        timeMs: Long,
        seek: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val language = immersionLanguage(current.resolvedSourceLanguage, current.sourcePreference)
        val playing = appVisible && !current.playbackPaused && !seek && language != null
        val credited = immersionTracker.onTick(now, timeMs, playing)
        if (language != null && credited > 0) immersionAccumulator.add(LocalDate.now(), language, videoId, credited)
        if (immersionAccumulator.shouldFlush(now)) flushImmersion()
    }

    private fun flushImmersion() {
        immersion.record(immersionAccumulator.drain(SystemClock.elapsedRealtime()))
    }

    private fun updatePlaybackRequest(seek: Boolean = false) {
        playbackRequests.update {
            CaptionPlaybackRequest(
                // Translation scheduling needs seconds, while karaoke keeps its 33 ms clock.
                timeMs =
                    if (seek || !it.enabled || latestPlaybackSecondMs / 1000 != it.timeMs / 1000) {
                        latestPlaybackSecondMs
                    } else {
                        it.timeMs
                    },
                paused = _state.value.playbackPaused,
                enabled = appVisible && playbackKnown && _state.value.activeVideoId != null,
                seekGeneration = it.seekGeneration + if (seek) 1 else 0,
                translationAttempt = it.translationAttempt,
            )
        }
    }

    init {
        preferences.edit().remove(KARAOKE_TIMING_MODE_PREFERENCE).apply()
        preferences.registerOnSharedPreferenceChangeListener(visibilityListener)
        readPageTokenMode()
        pageTranscripts.onTokenLearned = { needed -> preferences.edit().putBoolean(PAGE_TOKEN_NEEDED_PREFERENCE, needed).apply() }
        viewModelScope.launch {
            try {
                vocabulary.refresh()
                retireLegacyDownloadJobs(application)
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                // The saved-words screen reports storage failures with a retry on reopen.
            }
        }
        if (_state.value.preloadModelsEnabled) {
            val source = _state.value.sourcePreference.takeUnless { it == "auto" } ?: "en"
            warmTranslationModel(sourceLanguage = source, targetLanguage = _state.value.targetLanguage)
        }
        // The page got the full transcript after live subtitles took over: load it.
        viewModelScope.launch {
            pageTranscripts.lateTranscripts.collect { videoId ->
                val current = _state.value
                if (current.activeVideoId == videoId && current.liveFallback && !current.retryingTranscript) {
                    loadVideo(videoId, showPanel = current.subtitlePanelVisible)
                }
            }
        }
    }

    fun acceptSharedText(text: String) {
        val videoId = YouTubeUrlParser.extractVideoId(text) ?: return
        val watchUrl = mobileWatchUrl(videoId)
        preferences.edit().putString("last_browser_url", watchUrl).apply()
        _state.update {
            it.copy(
                browserUrl = watchUrl,
                browserNavigationRequestId = it.browserNavigationRequestId + 1,
            )
        }
        openVideo(videoId)
    }

    fun onYouTubePageChanged(url: String) {
        if (classifyMainFrameUrl(url) != EmbeddedNavigationDecision.YOUTUBE_WEB) return
        if (url != _state.value.browserUrl) {
            preferences.edit().putString("last_browser_url", url).apply()
            _state.update { it.copy(browserUrl = url) }
        }

        val videoId = YouTubeUrlParser.extractVideoId(url)
        if (videoId == null) {
            clearActiveVideo()
        } else {
            openVideo(videoId)
        }
    }

    internal fun onWebPlaybackSecond(
        videoId: String,
        second: Float,
        liveCaption: LiveCaptionSample? = null,
        sessionId: String = "",
    ) {
        val current = _state.value
        if (current.activeVideoId != videoId || !second.isFinite()) return
        val timeMs = (second.coerceAtLeast(0f) * 1_000).toLong()
        val normalizedSessionId = sessionId.takeIf(String::isNotBlank)
        val sessionChanged =
            normalizedSessionId != null &&
                playbackSessionId?.let { it != normalizedSessionId } == true
        if (normalizedSessionId != null) playbackSessionId = normalizedSessionId
        val seek =
            sessionChanged ||
                timeMs + LIVE_CAPTION_BACKWARD_SEEK_RESET_MS < latestPlaybackSecondMs ||
                timeMs > latestPlaybackSecondMs + 2_000L
        if (seek) {
            liveCaptionTracker.reset()
            captionHighlightResolver.reset()
            liveCaptionProgress = null
        }
        trackImmersion(current, videoId, timeMs, seek)
        latestPlaybackSecondMs = timeMs
        playbackKnown = true
        updatePlaybackRequest(seek)
        if (current.liveFallback) {
            updateLiveFallbackPlayback(videoId, liveCaption, seek)
        } else {
            updateTranscriptPlayback(current, liveCaption, timeMs)
        }
    }

    private fun updateLiveFallbackPlayback(
        videoId: String,
        liveCaption: LiveCaptionSample?,
        seek: Boolean,
    ) {
        val current = _state.value
        updateLiveSubtitle(videoId, liveCaption, seek)
        val accepted =
            liveCaption?.takeIf {
                liveTranslationGate.key != null &&
                    liveTranslationKey(it, videoId, current.targetLanguage) == liveTranslationGate.key &&
                    it.revision != rejectedLiveRevision
            }
        liveCaptionProgress = accepted?.let { reconcileLiveCaptionProgress(liveCaptionProgress, it) }
        _state.update {
            it.copy(activeWordIndex = if (it.wordHighlightEnabled) liveCaptionProgress?.activeWordIndex ?: -1 else -1)
        }
    }

    private fun updateTranscriptPlayback(
        current: DualSubUiState,
        liveCaption: LiveCaptionSample?,
        timeMs: Long,
    ) {
        val timedIndex = activeSubtitleIndex(current.segments, timeMs)
        val referenceIndex = if (timedIndex >= 0) timedIndex else nearestSegmentIndex(current.segments, timeMs)
        val livePosition =
            if (shouldCaptureLiveCaptions(current.generatedCaptions, current.wordHighlightEnabled)) {
                val sample =
                    liveCaption?.takeIf {
                        (it.videoId == null || it.videoId == current.activeVideoId) &&
                            (
                                it.languageCode == null ||
                                    it.languageCode.substringBefore('-') == current.resolvedSourceLanguage?.substringBefore('-')
                            )
                    }
                liveCaptionTracker.resolve(sample, current.segments, referenceIndex, timeMs)
            } else {
                null
            }
        val timedWordIndex = activeWordIndex(current.segments, timedIndex, timeMs)
        val position =
            captionHighlightResolver.resolve(
                generatedCaptions = current.generatedCaptions,
                wordHighlightEnabled = current.wordHighlightEnabled,
                timedSegmentIndex = timedIndex,
                timedWordIndex = timedWordIndex,
                livePosition = livePosition,
                playbackTimeMs = timeMs,
            )
        val index = position?.segmentIndex ?: -1
        val wordIndex = position?.wordIndex ?: -1
        if (index != current.currentIndex || wordIndex != current.activeWordIndex) {
            _state.update { it.copy(currentIndex = index, activeWordIndex = wordIndex) }
        }
    }

    private fun updateLiveSubtitle(
        videoId: String,
        sample: LiveCaptionSample?,
        seek: Boolean,
    ) {
        if (seek) rejectedLiveRevision = sample?.revision
        val key = liveTranslationKey(sample?.takeUnless { it.revision == rejectedLiveRevision }, videoId, _state.value.targetLanguage)
        if (!liveTranslationGate.update(key, seek)) return
        liveTranslationJob?.cancel()
        val ticket = liveTranslationGate.generation
        _state.update {
            it.copy(
                liveOriginal = key?.text,
                liveTranslated = null,
                resolvedSourceLanguage = key?.language,
                statusMessage =
                    if (key ==
                        null
                    ) {
                        text(R.string.status_waiting_for_captions)
                    } else {
                        text(R.string.status_translating_live_captions)
                    },
            )
        }
        if (key == null) return
        if (!TranslationLanguages.isSupported(key.language)) {
            _state.update {
                it.copy(
                    statusMessage = text(R.string.status_live_translation_not_supported, languageName(key.language)),
                )
            }
            return
        }
        liveTranslationJob =
            viewModelScope.launch {
                try {
                    val translated =
                        debouncedLiveTranslation(
                            key,
                            isCurrent = {
                                liveTranslationGate.accepts(ticket, key) && _state.value.liveFallback &&
                                    _state.value.activeVideoId == videoId
                            },
                            translate = ::translateText,
                        ) ?: return@launch
                    if (liveTranslationGate.accepts(ticket, key) && _state.value.liveFallback && _state.value.activeVideoId == videoId) {
                        _state.update {
                            it.copy(
                                liveTranslated = translated,
                                statusMessage = text(R.string.status_current_captions_only),
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (liveTranslationGate.accepts(ticket, key)) {
                        _state.update {
                            it.copy(
                                statusMessage = text(R.string.status_live_translation_unavailable),
                            )
                        }
                    }
                }
            }
    }

    /**
     * [language] is "auto", a language code, or a track from the video's list ([trackChoiceCode]).
     * A track is kept for this video only; its language becomes the preference for later videos.
     */
    fun setSourcePreference(language: String) {
        val current = _state.value
        val track = pickedCaptionTrack(language, current.availableSourceLanguages)
        if (language.startsWith(TRACK_CHOICE_PREFIX) && track == null) return
        val normalized =
            track?.let { TranslationLanguages.normalize(it.code) }
                ?: language.takeIf { it == "auto" }
                ?: TranslationLanguages.normalize(language)
        if (!shouldAcceptSourcePreference(normalized, current.availableSourceLanguages)) return
        if (track != null) {
            if (current.sourcePreference == normalized && current.loadedTrackId == track.trackId) return
        } else if (current.sourcePreference == normalized) {
            return
        }
        preferences.edit().putString("preferred_caption_language", normalized).apply()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        _state.update { it.copy(sourcePreference = normalized, pickedTrack = track?.trackId) }
        val videoId = _state.value.activeVideoId ?: return
        loadVideo(videoId, showPanel = true)
    }

    /** Fetches [videoId]'s captions in the picked track or chosen original language, or Auto's pick when none is chosen. */
    private suspend fun fetchCaptionTrack(videoId: String): CaptionTrackResult =
        captionProvider.fetch(
            videoId,
            preferredCaptionLanguages(_state.value.sourcePreference),
            learningLanguage(),
            _state.value.pickedTrack,
        )

    private fun learningLanguage(): String? =
        storedLearningLanguage(
            preferences.getString(LEARNING_LANGUAGE_PREFERENCE, null),
            preferences.getString("preferred_caption_language", null),
        )

    fun setTargetLanguage(language: String) {
        val normalized = TranslationLanguages.normalize(language)
        if (!TranslationLanguages.isSupported(normalized) || _state.value.targetLanguage == normalized) return
        preferences.edit().putString("target_language", normalized).apply()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        _state.update { it.copy(targetLanguage = normalized, liveTranslated = null) }
    }

    fun completeOnboarding(
        nativeLanguage: String,
        learningLanguage: String,
    ) {
        val languages = normalizedOnboardingLanguages(nativeLanguage, learningLanguage) ?: return
        val (native, learning) = languages
        preferences.edit().putString("preferred_caption_language", learning).putString(LEARNING_LANGUAGE_PREFERENCE, learning).apply()
        preferences.edit().putString("target_language", native).apply()
        _state.update { it.copy(sourcePreference = learning, targetLanguage = native) }
        warmTranslationModel(sourceLanguage = learning, targetLanguage = native)
        finishOnboarding()
    }

    fun skipOnboarding() = finishOnboarding()

    private fun finishOnboarding() {
        preferences.edit().putBoolean("onboarding_completed", true).apply()
        _state.update { it.copy(onboardingCompleted = true) }
    }

    private fun warmTranslationModel(
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        translationWarmupJob?.cancel()
        translationWarmupJob =
            viewModelScope.launch {
                try {
                    translator.prepare(sourceLanguage, targetLanguage)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Warm-up is opportunistic. The normal translation path retries
                    // model preparation and surfaces any real failure to the user.
                }
            }
    }

    fun completeGuide() {
        val editor = preferences.edit().putBoolean(GUIDE_COMPLETED_PREFERENCE, true)
        // The goal step follows the guide, even if the app closes before it is answered.
        if (!preferences.contains(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE)) {
            editor.putBoolean(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE, false)
        }
        editor.apply()
        _state.update { it.copy(guideCompleted = true) }
    }

    fun setDailyGoal(minutes: Int) {
        val goal = storedDailyGoalMinutes(minutes)
        preferences.edit().putInt(DAILY_GOAL_MINUTES_PREFERENCE, goal).apply()
        _state.update { it.copy(dailyGoalMinutes = goal) }
    }

    /** Finishes the first-launch goal step; a null goal means the user skipped it. */
    fun completeDailyGoalPrompt(minutes: Int?) {
        if (minutes != null) setDailyGoal(minutes)
        preferences.edit().putBoolean(DAILY_GOAL_PROMPT_COMPLETED_PREFERENCE, true).apply()
        _state.update { it.copy(dailyGoalPromptCompleted = true) }
    }

    fun setFontScale(scale: Float) {
        val safeScale = normalizeFontScale(scale)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
    }

    fun setPortraitPanelOffsetFraction(offsetFraction: Float) {
        val normalized = normalizePortraitPanelOffsetFraction(offsetFraction)
        preferences.edit().putFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, normalized).apply()
        _state.update { it.copy(portraitPanelOffsetFraction = normalized) }
    }

    fun resetPortraitPanelPosition() {
        preferences.edit().remove(PORTRAIT_PANEL_OFFSET_PREFERENCE).apply()
        _state.update {
            it.copy(portraitPanelOffsetFraction = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION)
        }
    }

    fun setLandscapeSplitEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("landscape_split_enabled", enabled).apply()
        _state.update { it.copy(landscapeSplitEnabled = enabled) }
    }

    fun setOriginalSubtitleColor(key: String) =
        setSubtitleColorKey(
            preferenceKey = SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
            key = key,
            fallback = DEFAULT_ORIGINAL_COLOR_KEY,
        )

    fun setTranslatedSubtitleColor(key: String) =
        setSubtitleColorKey(
            preferenceKey = SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
            key = key,
            fallback = DEFAULT_TRANSLATED_COLOR_KEY,
        )

    fun setHighlightColor(key: String) =
        setSubtitleColorKey(
            preferenceKey = SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
            key = key,
            fallback = DEFAULT_HIGHLIGHT_COLOR_KEY,
        )

    fun setWordHighlightEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(WORD_HIGHLIGHT_ENABLED_PREFERENCE, enabled).apply()
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
        _state.update { it.copy(wordHighlightEnabled = enabled, activeWordIndex = -1) }
    }

    fun setCustomColorsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE, enabled).apply()
        _state.update { it.copy(customColorsEnabled = enabled) }
    }

    fun setCaptionFormat(format: CaptionFormat) {
        if (_state.value.captionFormat == format) return
        preferences.edit().putString(CAPTION_FORMAT_PREFERENCE, format.storageValue).apply()
        _state.update { it.copy(captionFormat = format) }
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
    }

    fun setTranslationPosition(position: TranslationPosition) {
        preferences.edit().putString(TRANSLATION_POSITION_PREFERENCE, position.storageValue).apply()
        _state.update { it.copy(translationPosition = position) }
    }

    fun setLockOverlayToVideo(locked: Boolean) {
        preferences.edit().putBoolean(LOCK_OVERLAY_TO_VIDEO_PREFERENCE, locked).apply()
        _state.update { it.copy(lockOverlayToVideo = locked) }
    }

    /** When original or translated captions show; the settings page writes the same preferences. */
    fun setCaptionVisibility(
        original: Boolean,
        visibility: CaptionVisibility,
    ) {
        preferences.edit().putString(if (original) ORIGINAL_VISIBILITY else TRANSLATED_VISIBILITY, visibility.name).apply()
        _state.update { if (original) it.copy(originalVisibility = visibility) else it.copy(translatedVisibility = visibility) }
    }

    /** Opens a YouTube page in the app's one YouTube view, for example a search the assistant offered. */
    fun openYouTubePage(url: String) {
        if (!isYouTubeWebUrl(url)) return
        preferences.edit().putString("last_browser_url", url).apply()
        _state.update { it.copy(browserUrl = url, browserNavigationRequestId = it.browserNavigationRequestId + 1) }
    }

    fun setPreloadModelsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, enabled).apply()
        _state.update { it.copy(preloadModelsEnabled = enabled) }
        if (enabled) {
            val source = _state.value.sourcePreference.takeUnless { it == "auto" } ?: "en"
            warmTranslationModel(sourceLanguage = source, targetLanguage = _state.value.targetLanguage)
        }
    }

    fun setNaturalSubtitlesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(NATURAL_SUBTITLES_PREFERENCE, enabled).apply()
        _state.update { it.copy(naturalSubtitlesEnabled = enabled) }
        val videoId = _state.value.activeVideoId
        if (videoId != null) {
            loadVideo(videoId = videoId, showPanel = _state.value.subtitlePanelVisible)
        }
    }

    fun setWordLearningEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(WORD_LEARNING_ENABLED_PREFERENCE, enabled).apply()
        _state.update { it.copy(wordLearningEnabled = enabled) }
    }

    fun setWordLearningTarget(target: String) {
        preferences.edit().putString(WORD_LEARNING_TARGET_PREFERENCE, target).apply()
        _state.update { it.copy(wordLearningTarget = target) }
    }

    fun setTapToLearnEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(TAP_TO_LEARN_PREFERENCE, enabled).apply()
        _state.update { it.copy(tapToLearnEnabled = enabled) }
    }

    fun setWordLearningActiveOnly(enabled: Boolean) {
        preferences.edit().putBoolean(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, enabled).apply()
        _state.update { it.copy(wordLearningActiveOnly = enabled) }
    }

    fun selectLearningWord(tap: WordTap?) {
        val current = _state.value
        val source = com.kienhoang.dualsubreplay.data.learningSourceLanguage(current.resolvedSourceLanguage, current.sourcePreference)
        _state.update {
            it.copy(
                selectedLearningWord =
                    tap?.let { selected ->
                        com.kienhoang.dualsubreplay.data.learningSelection(
                            if (current.liveFallback) selected.copy(segment = null) else selected,
                            source,
                            current.targetLanguage,
                            current.activeVideoId.takeUnless { current.liveFallback },
                        )
                    },
            )
        }
    }

    fun setAutoPronounce(enabled: Boolean) {
        preferences.edit().putBoolean("auto_pronounce", enabled).apply()
        _state.update { it.copy(autoPronounce = enabled) }
    }

    /** Translates [text] from a subtitle line: original lines into the target language, translated lines back. */
    internal suspend fun translateSubtitleText(
        text: String,
        translated: Boolean,
    ): String {
        val current = _state.value
        val source = com.kienhoang.dualsubreplay.data.learningSourceLanguage(current.resolvedSourceLanguage, current.sourcePreference)
        return if (translated) {
            translateText(current.targetLanguage, source, text)
        } else {
            translateText(source, current.targetLanguage, text)
        }
    }

    internal suspend fun translateSelection(selection: LearningWordSelection): String =
        translateText(selection.wordLanguage, selection.meaningLanguage, selection.token.text)

    internal suspend fun saveWord(
        selection: LearningWordSelection,
        meaning: String,
        online: Boolean,
    ): SavedWord = vocabulary.save(savedWordFrom(selection, meaning, online))

    suspend fun translateWord(word: String): String {
        val current = _state.value
        val source = current.resolvedSourceLanguage ?: current.sourcePreference.takeUnless { it == "auto" } ?: "en"
        return translateText(source, current.targetLanguage, word)
    }

    /** One text through the chosen engine. Subtitle rows use [runStoredTranslation]'s session instead. */
    private suspend fun translateText(
        source: String,
        target: String,
        text: String,
    ): String {
        if (!_state.value.translatesWithGoogle()) return translator.translateSingle(source, target, text)
        val translated =
            try {
                googleTranslator.translate(source, target, text)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fallBackToOnDevice(error)
                null
            }
        // A text Google leaves blank is translated on the device; only a failure moves the video off Google.
        return translated ?: translator.translateSingle(source, target, text)
    }

    /** Settings → Translation → Google Translate (online). Reloads the current video's translations. */
    fun setTranslationEngine(engine: TranslationEngine) {
        preferences.edit().putString(TRANSLATION_ENGINE_PREFERENCE, engine.storageValue).apply()
        // Choosing an engine tries Google first again, even on mobile data.
        googleFailedOnMobile = false
        val changed = _state.value.translationEngine != engine || _state.value.onDeviceFallback
        _state.update { it.copy(translationEngine = engine) }
        val videoId = _state.value.activeVideoId
        if (changed && videoId != null) loadVideo(videoId = videoId, showPanel = _state.value.subtitlePanelVisible)
    }

    /** Settings → Translation → Wait for Google; the next row Google translates uses it. */
    fun setGoogleWait(wait: GoogleWait) {
        preferences.edit().putString(GOOGLE_WAIT_PREFERENCE, wait.storageValue).apply()
        _state.update { it.copy(googleWait = wait) }
    }

    private fun onWifiNow(): Boolean = getApplication<Application>().onWifi()

    /** How long a row waits for Google on the current network. */
    private fun currentGoogleWaitMs(): Long = googleWaitMs(_state.value.googleWait, onWifiNow())

    /**
     * Google failed even after its retries: this video translates on the device now (the translation
     * flow follows [DualSubUiState.onDeviceFallback]) and the top-right icon explains why.
     */
    private fun fallBackToOnDevice(error: Exception) {
        rememberGoogleFailure()
        _state.update { it.copy(onDeviceFallback = true, onlineTranslationFailureDetail = error.message) }
    }

    /** On mobile data, the next load starts without Google until Google answers again (see [startsWithoutGoogle]). */
    private fun rememberGoogleFailure() {
        if (!onWifiNow()) googleFailedOnMobile = true
    }

    /**
     * Right after Google failed on mobile data, a load on mobile data starts without Google, and
     * [recheckGoogle] asks Google as soon as a row plays. On Wi-Fi every load tries Google first.
     */
    private fun startsWithoutGoogle(): Boolean {
        val skip = googleFailedOnMobile && _state.value.translationEngine == TranslationEngine.GOOGLE_WEB && !onWifiNow()
        checkGoogleSoon = skip
        return skip
    }

    /** Every load, of this video again or the next one, tries Google first, unless [startsWithoutGoogle]. */
    private fun resetGoogleFallbackForLoad() {
        val withoutGoogle = startsWithoutGoogle()
        _state.update {
            it.copy(
                onDeviceFallback = withoutGoogle,
                youTubeTranslationFallback = false,
                onlineTranslationFailureDetail = it.onlineTranslationFailureDetail.takeIf { withoutGoogle },
            )
        }
    }

    /** The problem details' "Try Google again", and the periodic check once Google answers again. */
    fun tryGoogleTranslationAgain() {
        googleFailedOnMobile = false
        _state.update(::withGoogleAgain)
    }

    private fun setSubtitleColorKey(
        preferenceKey: String,
        key: String,
        fallback: String,
    ) {
        val normalized = storedSubtitleColorKey(key, fallback)
        val currentKey =
            when (preferenceKey) {
                SUBTITLE_ORIGINAL_COLOR_PREFERENCE -> _state.value.originalColorKey
                SUBTITLE_TRANSLATED_COLOR_PREFERENCE -> _state.value.translatedColorKey
                else -> _state.value.highlightColorKey
            }
        if (currentKey == normalized) return
        preferences.edit().putString(preferenceKey, normalized).apply()
        _state.update { state ->
            when (preferenceKey) {
                SUBTITLE_ORIGINAL_COLOR_PREFERENCE -> state.copy(originalColorKey = normalized)
                SUBTITLE_TRANSLATED_COLOR_PREFERENCE -> state.copy(translatedColorKey = normalized)
                else -> state.copy(highlightColorKey = normalized)
            }
        }
    }

    /**
     * Restores every user-facing setting to its factory default (issue #22).
     * The browser URL is deliberately kept so the session is not disturbed.
     */
    fun resetAllSettings() {
        val editor = preferences.edit()
        RESETTABLE_SETTING_KEYS.forEach(editor::remove)
        editor.apply()
        _state.update { it.copy(autoPronounce = true) }
        latestPlaybackSecondMs = 0L
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
        _state.update { current ->
            current.copy(
                sourcePreference = "auto",
                targetLanguage = "vi",
                fontScale = 1f,
                portraitPanelOffsetFraction = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                landscapeSplitEnabled = true,
                originalColorKey = DEFAULT_ORIGINAL_COLOR_KEY,
                translatedColorKey = DEFAULT_TRANSLATED_COLOR_KEY,
                highlightColorKey = DEFAULT_HIGHLIGHT_COLOR_KEY,
                wordHighlightEnabled = true,
                customColorsEnabled = true,
                originalVisibility = CaptionVisibility.ALWAYS,
                translatedVisibility = CaptionVisibility.ALWAYS,
                studyPlayMode = StudyPlayMode.NORMAL,
                captionFormat = CaptionFormat.SHORT_PHRASES,
                translationPosition = TranslationPosition.BELOW,
                lockOverlayToVideo = false,
                preloadModelsEnabled = true,
                naturalSubtitlesEnabled = true,
                wordLearningEnabled = true,
                wordLearningTarget = DEFAULT_WORD_LEARNING_TARGET,
                wordLearningActiveOnly = true,
                tapToLearnEnabled = true,
                selectedLearningWord = null,
                translationEngine = DEFAULT_TRANSLATION_ENGINE,
                googleWait = GoogleWait.AUTO,
                onDeviceFallback = false,
                youTubeTranslationFallback = false,
                onlineTranslationFailureDetail = null,
            )
        }
        _state.value.activeVideoId?.let { loadVideo(it, showPanel = _state.value.subtitlePanelVisible) }
    }

    /**
     * Retries what failed. While the original captions are showing and only translation stopped,
     * translation starts again from the stored captions; otherwise the video's captions reload.
     */
    fun retryCaptions() {
        val current = _state.value
        val videoId = current.activeVideoId ?: return
        if (onlyTranslationFailed(current)) {
            _state.update {
                it.copy(translationError = null, statusMessage = text(R.string.status_preparing_nearby_translations))
            }
            playbackRequests.update { it.copy(translationAttempt = it.translationAttempt + 1) }
            return
        }
        loadVideo(videoId, showPanel = true)
    }

    fun showSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = true) }

    fun hideSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = false) }

    private fun openVideo(videoId: String) {
        if (_state.value.activeVideoId == videoId) return
        loadVideo(videoId, showPanel = true)
    }

    private fun clearActiveVideo() {
        if (_state.value.activeVideoId == null) return
        immersionTracker.reset()
        flushImmersion()
        loadGeneration += 1
        loadingJob?.cancel()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        latestPlaybackSecondMs = 0L
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        liveCaptionProgress = null
        playbackSessionId = null
        playbackKnown = false
        playbackRequests.value = CaptionPlaybackRequest()
        _state.update {
            it.copy(
                activeVideoId = null,
                liveFallback = false,
                liveOriginal = null,
                liveTranslated = null,
                retryingTranscript = false,
                subtitlePanelVisible = true,
                availableSourceLanguages = emptyList(),
                pickedTrack = null,
                loadedTrackId = "",
                resolvedSourceLanguage = null,
                generatedCaptions = false,
                segments = emptyList(),
                currentIndex = -1,
                activeWordIndex = -1,
                stage = LoadStage.IDLE,
                statusMessage = null,
                errorMessage = null,
            )
        }
    }

    private fun loadVideo(
        videoId: String,
        showPanel: Boolean,
    ) {
        val generation = ++loadGeneration
        loadingJob?.cancel()
        liveCaptionTracker.reset()
        liveCaptionProgress = null
        val preserveLive = _state.value.liveFallback && _state.value.activeVideoId == videoId
        if (!preserveLive) {
            rejectedLiveRevision = null
            liveTranslationGate.reset()
            liveTranslationJob?.cancel()
        }
        // Reloading the same video (language change, retry) keeps tracking the
        // current position so subtitles resume exactly where playback is.
        if (shouldResetPlaybackClock(_state.value.activeVideoId, videoId)) {
            latestPlaybackSecondMs = 0L
            playbackKnown = false
            playbackSessionId = null
            captionHighlightResolver.reset()
        }
        playbackRequests.value = CaptionPlaybackRequest()
        _state.update {
            it.copy(
                liveFallback = preserveLive,
                liveOriginal = if (preserveLive) it.liveOriginal else null,
                liveTranslated = if (preserveLive) it.liveTranslated else null,
                retryingTranscript = preserveLive,
                fullTranscriptProblem = if (preserveLive) it.fullTranscriptProblem else null,
                activeVideoId = videoId,
                playbackPaused = if (playbackKnown) it.playbackPaused else true,
                subtitlePanelVisible = showPanel,
                availableSourceLanguages = emptyList(),
                pickedTrack = if (it.activeVideoId == videoId) it.pickedTrack else null,
                resolvedSourceLanguage = if (preserveLive) it.resolvedSourceLanguage else null,
                generatedCaptions = false,
                segments = emptyList(),
                currentIndex = -1,
                activeWordIndex = -1,
                stage = LoadStage.LOADING_CAPTIONS,
                statusMessage = if (preserveLive) it.statusMessage else text(R.string.status_finding_caption_track),
                errorMessage = null,
            )
        }
        resetGoogleFallbackForLoad()
        updatePlaybackRequest()
        loadingJob =
            viewModelScope.launch {
                var rawStore: SubtitleStore? = null
                try {
                    val natural = _state.value.naturalSubtitlesEnabled
                    val track = withContext(Dispatchers.IO) { persistCaptionTrack(fetchCaptionTrack(videoId), natural) { rawStore = it } }
                    if (!isCurrentLoad(_state.value, videoId, generation)) return@launch
                    liveTranslationGate.reset()
                    liveTranslationJob?.cancel()

                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) return@update current
                        current.copy(
                            liveFallback = false,
                            liveOriginal = null,
                            liveTranslated = null,
                            retryingTranscript = false,
                            fullTranscriptProblem = null,
                            resolvedSourceLanguage = track.languageCode,
                            sourcePreference =
                                resolvedSourcePreference(
                                    current.sourcePreference,
                                    track.languageCode,
                                ),
                            availableSourceLanguages = track.availableLanguages,
                            loadedTrackId = track.trackId,
                            generatedCaptions = track.isGenerated,
                            segments = emptyList(),
                            stage = LoadStage.TRANSLATING,
                            statusMessage = translationStartingMessage(current.targetLanguage),
                        )
                    }

                    followStoredTranslation(checkNotNull(rawStore), videoId, generation, track.languageCode, track.isGenerated, natural)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) return@update current
                        current.copy(
                            stage = LoadStage.READY,
                            liveFallback = true,
                            retryingTranscript = false,
                            fullTranscriptProblem = fullTranscriptProblem(videoId, error),
                            statusMessage =
                                if (current.liveOriginal !=
                                    null
                                ) {
                                    current.statusMessage
                                } else {
                                    text(R.string.status_waiting_for_captions)
                                },
                            errorMessage = null,
                        )
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { rawStore?.close() }
                }
            }
    }

    private suspend fun persistCaptionTrack(
        track: CaptionTrackResult,
        natural: Boolean,
        onStored: (SubtitleStore) -> Unit,
    ): CaptionTrackResult {
        currentCoroutineContext().ensureActive()
        val merged = SubtitleMerger.merge(track.cues, enhancedNaturalFlow = natural)
        if (merged.isEmpty()) throw CaptionUnavailableException("This caption track contains no readable text.")
        onStored(SubtitleStore.create(subtitleDirectory, merged))
        // Do not retain every raw cue across the long-lived translation coroutine.
        return track.copy(cues = emptyList())
    }

    /**
     * Translates the stored track again whenever the format, target language or on-device fallback changes.
     * Every load tries Google again ([loadVideo] clears the fallback), except on mobile data right after
     * Google failed there.
     */
    private suspend fun followStoredTranslation(
        rawStore: SubtitleStore,
        videoId: String,
        generation: Long,
        sourceLanguage: String,
        generated: Boolean,
        natural: Boolean,
    ) = coroutineScope {
        // Google requests a row stopped waiting for keep going here, across engine switches, until the next load.
        val googleRequests = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
        var shown: Pair<CaptionFormat, String>? = null
        _state
            .map { Triple(it.captionFormat, it.targetLanguage, it.onDeviceFallback) }
            .distinctUntilChanged()
            .collectLatest { (format, target) ->
                // Only the engine changed: the rows stay on screen until the other engine's lines replace them.
                val keepRows = shown == (format to target)
                shown = format to target
                runStoredTranslation(rawStore, videoId, generation, sourceLanguage to generated, target, format, natural, googleRequests, keepRows)
            }
    }

    private suspend fun runStoredTranslation(
        rawStore: SubtitleStore,
        videoId: String,
        generation: Long,
        source: Pair<String, Boolean>,
        targetLanguage: String,
        format: CaptionFormat,
        natural: Boolean,
        googleRequests: CoroutineScope,
        keepRows: Boolean,
    ) {
        val sourceLanguage = source.first
        var displayStore: SubtitleStore? = null
        liveCaptionTracker.reset()
        captionHighlightResolver.reset()
        showPreparingSubtitles(keepRows)
        var following = false

        suspend fun follow(
            prefetch: (suspend (List<String>) -> Unit)? = null,
            translate: suspend (String) -> String,
        ) {
            following = true
            translatePlaybackWindow(
                checkNotNull(displayStore),
                playbackRequests,
                translate,
                onTranslationFailure = { error -> showTranslationUnavailable(videoId, generation, error) },
                prefetch = prefetch,
            ) { rows, preparing ->
                publishSubtitleWindow(videoId, generation, rows, preparing)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                displayStore =
                    prepareCaptionDisplayStore(
                        source = rawStore,
                        directory = subtitleDirectory,
                        format = format,
                        natural = natural,
                    )
            }
            val store = checkNotNull(displayStore)
            try {
                if (_state.value.translatesWithGoogle()) {
                    followGoogleTranslation(store, videoId, generation, sourceLanguage to targetLanguage, googleRequests) { prefetch, translate ->
                        follow(prefetch, translate)
                    }
                } else {
                    followOnDevice(store, videoId, generation, source, targetLanguage) { translate -> follow(translate = translate) }
                }
            } catch (error: Exception) {
                if (error is CancellationException || following) throw error
                // The pair cannot be translated at all (an unsupported language): still show the captions.
                follow { throw error }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { current ->
                if (!isCurrentLoad(current, videoId, generation)) {
                    current
                } else {
                    current.copy(
                        stage = LoadStage.ERROR,
                        isDownloadingTranslationModel = false,
                        statusMessage = null,
                        errorMessage = error.message ?: text(R.string.status_subtitles_load_failed),
                    )
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { displayStore?.close() }
        }
    }

    /** Clears the subtitles while the track is prepared again; [keepRows] (only the engine changed) leaves them showing. */
    private fun showPreparingSubtitles(keepRows: Boolean) {
        _state.update {
            if (keepRows) {
                it.copy(errorMessage = null, translationError = null, isDownloadingTranslationModel = false)
            } else {
                it.copy(
                    segments = emptyList(),
                    currentIndex = -1,
                    activeWordIndex = -1,
                    stage = LoadStage.LOADING_CAPTIONS,
                    errorMessage = null,
                    translationError = null,
                    isDownloadingTranslationModel = false,
                    statusMessage = text(R.string.status_preparing_subtitles),
                )
            }
        }
    }

    /**
     * The Google half of [runStoredTranslation]: [follow] translates the playing minute, sending upcoming
     * sentences in one request that rows then read from the cache, while minutes 2 to 5 fill in the
     * background one request at a time. A row waits for Google at most [currentGoogleWaitMs]; then the
     * video moves off Google while the request runs on in [googleRequests] (see [catchUpWithGoogle]).
     */
    private suspend fun followGoogleTranslation(
        store: SubtitleStore,
        videoId: String,
        generation: Long,
        languages: Pair<String, String>,
        googleRequests: CoroutineScope,
        follow: suspend (prefetch: suspend (List<String>) -> Unit, translate: suspend (String) -> String) -> Unit,
    ) = coroutineScope {
        val (source, target) = languages
        launch { prefetchInBackground(store, playbackRequests) { texts -> googleTranslator.translateNextBatch(source, target, texts) } }
        warmModelOnWifi(source, target)
        val waitMs = currentGoogleWaitMs()
        follow({ texts -> awaitWithin(googleRequests, waitMs) { googleTranslator.translateAll(source, target, texts) } }) { text ->
            // A line Google leaves blank on every address is translated on the device; the rest stays on Google.
            awaitWithin(googleRequests, waitMs) { googleTranslator.translate(source, target, text) }
                ?: translateOnDevice(videoId, generation, source, target, text)
        }
    }

    /**
     * The on-device half of [runStoredTranslation]. After a Google failure rows keep what Google already
     * translated, then take YouTube's own translated captions (as LingoTube does) and the device
     * translates the rest, while Google is checked again in the background. Rows wait for YouTube at
     * most [YOUTUBE_TRANSLATION_WAIT_MS], so the switch adds little waiting; a later answer still serves
     * the rows after it.
     */
    private suspend fun followOnDevice(
        store: SubtitleStore,
        videoId: String,
        generation: Long,
        source: Pair<String, Boolean>,
        targetLanguage: String,
        follow: suspend (translate: suspend (String) -> String) -> Unit,
    ) = coroutineScope {
        val sourceLanguage = source.first
        val fallback = _state.value.onDeviceFallback
        val soon = checkGoogleSoon.also { checkGoogleSoon = false }
        if (fallback) launch { recheckGoogle(sourceLanguage, targetLanguage, soon) }
        val fromYouTube = if (fallback) async { youTubeTranslations(store, videoId, generation, source, targetLanguage) } else null
        val youTubeInTime = fromYouTube?.let { answer -> async { withTimeoutOrNull(YOUTUBE_TRANSLATION_WAIT_MS) { answer.await() } } }
        translator.withSession(sourceLanguage, targetLanguage, onDownloadingChange = { downloading ->
            showTranslationModelDownload(videoId, generation, downloading)
        }) { translate ->
            if (fromYouTube == null || youTubeInTime == null) {
                follow(translate)
            } else {
                follow { text ->
                    googleTranslator.cachedTranslation(sourceLanguage, targetLanguage, text)
                        ?: (if (fromYouTube.isCompleted) fromYouTube.await() else youTubeInTime.await())?.get(text)
                        ?: translate(text)
                }
            }
        }
    }

    /** On Wi-Fi, while Google works, the on-device model downloads, so a later switch on mobile data needs no download. */
    private fun warmModelOnWifi(
        source: String,
        target: String,
    ) {
        if (!_state.value.preloadModelsEnabled || warmedPair == source to target || !onWifiNow()) return
        warmedPair = source to target
        warmTranslationModel(sourceLanguage = source, targetLanguage = target)
    }

    /** One text Google left blank, translated on the device without moving the video off Google. */
    private suspend fun translateOnDevice(
        videoId: String,
        generation: Long,
        source: String,
        target: String,
        text: String,
    ): String {
        val onDownloadingChange = { downloading: Boolean -> showTranslationModelDownload(videoId, generation, downloading) }
        return translator.withSession(source, target, onDownloadingChange) { translate -> translate(text) }
    }

    /**
     * YouTube's own translation of this video's caption track, from the page (as LingoTube gets its
     * second line), paired with the texts the rows translate; empty when YouTube has none for it.
     */
    private suspend fun youTubeTranslations(
        store: SubtitleStore,
        videoId: String,
        generation: Long,
        source: Pair<String, Boolean>,
        targetLanguage: String,
    ): Map<String, String> {
        val cues = pageTranslations.translation(videoId, source.first, source.second, targetLanguage)
        val rows =
            try {
                if (cues == null) emptyList() else withContext(Dispatchers.IO) { store.read(0 until store.size) }
            } catch (error: IOException) {
                emptyList()
            }
        val translations = if (cues == null) emptyMap() else youTubeRowTranslations(rows, cues, targetLanguage)
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) current else current.copy(youTubeTranslationFallback = translations.isNotEmpty())
        }
        return translations
    }

    private fun showTranslationModelDownload(
        videoId: String,
        generation: Long,
        downloading: Boolean,
    ) {
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) {
                current
            } else {
                current.copy(
                    isDownloadingTranslationModel = downloading,
                    statusMessage =
                        text(
                            if (downloading) {
                                R.string.status_downloading_translation_model
                            } else {
                                R.string.status_preparing_nearby_translations
                            },
                        ),
                )
            }
        }
    }

    /**
     * While this video translates on the device after a Google failure, asks Google again every
     * [GOOGLE_RECHECK_INTERVAL_MS] during playback with the current row, and goes back to Google once it answers.
     */
    private suspend fun recheckGoogle(
        sourceLanguage: String,
        targetLanguage: String,
        soon: Boolean,
    ) {
        var early = soon
        while (true) {
            delay(if (early) GOOGLE_EARLY_CHECK_POLL_MS else GOOGLE_RECHECK_INTERVAL_MS)
            val current = _state.value
            if (current.playbackPaused) continue
            val probe = current.segments.getOrNull(current.currentIndex)?.originalText ?: continue
            if (googleTranslator.responds(sourceLanguage, targetLanguage, probe)) {
                tryGoogleTranslationAgain()
                return
            }
            early = false
        }
    }

    private fun showTranslationUnavailable(
        videoId: String,
        generation: Long,
        error: Exception,
    ) {
        val reason = translationFailureMessage(error, text(R.string.status_translation_unavailable_retry))
        val status = text(R.string.status_original_captions_only)
        if (_state.value.translatesWithGoogle()) rememberGoogleFailure()
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) return@update current
            // Restarts this video's translation on the device (the translation flow follows onDeviceFallback).
            if (current.translatesWithGoogle()) return@update current.copy(onDeviceFallback = true, onlineTranslationFailureDetail = error.message)
            current.copy(
                isDownloadingTranslationModel = false,
                translationError = reason,
                statusMessage = status,
            )
        }
        if (error is GoogleSlowException) catchUpWithGoogle(videoId, generation, error)
    }

    /**
     * Google's request outlived the row's wait: once it answers, the video goes back to Google and its
     * lines replace the device's; if it fails, its error becomes the reason the top-right icon shows.
     * Meanwhile the reason lists what each Google address did so far.
     */
    private fun catchUpWithGoogle(
        videoId: String,
        generation: Long,
        slow: GoogleSlowException,
    ) {
        val progress =
            viewModelScope.launch {
                googleTranslator.problems.collect { soFar ->
                    if (soFar != null) showGoogleSlowDetail(videoId, generation, googleSlowDetail(slow.waitMs, soFar))
                }
            }
        slow.pending.invokeOnCompletion { cause ->
            progress.cancel()
            if (cause is CancellationException) return@invokeOnCompletion
            if (cause == null) googleFailedOnMobile = false
            _state.update { current ->
                when {
                    !isCurrentLoad(current, videoId, generation) || !current.onDeviceFallback -> current
                    cause == null -> withGoogleAgain(current)
                    else -> current.copy(onlineTranslationFailureDetail = cause.message)
                }
            }
        }
    }

    private fun showGoogleSlowDetail(
        videoId: String,
        generation: Long,
        detail: String,
    ) {
        _state.update { current ->
            val waiting = isCurrentLoad(current, videoId, generation) && current.onDeviceFallback
            if (waiting) current.copy(onlineTranslationFailureDetail = detail) else current
        }
    }

    private fun publishSubtitleWindow(
        videoId: String,
        generation: Long,
        rows: List<SubtitleSegment>,
        preparing: Boolean,
    ) {
        // The window slides forward every few rows during playback. Keep the highlight on the same
        // row instead of recomputing it from timestamps, which can point at the previous sentence.
        val shift = windowShift(_state.value.segments, rows)
        if (shift == null) {
            liveCaptionTracker.reset()
            captionHighlightResolver.reset()
        } else if (shift > 0) {
            liveCaptionTracker.shift(shift)
            captionHighlightResolver.shift(shift)
        }
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) return@update current
            val index = activeSubtitleIndex(rows, latestPlaybackSecondMs)
            val keptIndex =
                when {
                    shift == null -> null
                    current.currentIndex < 0 -> -1
                    else -> (current.currentIndex - shift).takeIf { it in rows.indices }
                }
            current.copy(
                segments = rows,
                currentIndex = keptIndex ?: index,
                activeWordIndex =
                    when {
                        keptIndex != null -> current.activeWordIndex
                        current.wordHighlightEnabled -> activeWordIndex(rows, index, latestPlaybackSecondMs)
                        else -> -1
                    },
                stage = if (preparing) LoadStage.TRANSLATING else LoadStage.READY,
                statusMessage =
                    text(
                        when {
                            current.translationError != null -> R.string.status_original_captions_only
                            current.playbackPaused -> R.string.status_paused_translation_resumes
                            preparing -> R.string.status_preparing_nearby_translations
                            else -> R.string.status_subtitles_ready
                        },
                    ),
            )
        }
    }

    private fun translationStartingMessage(targetLanguage: String): String =
        text(R.string.status_preparing_translation, languageName(targetLanguage))

    /**
     * The application context showing the in-app language. Android 13+ applies it to the
     * application itself; older versions need the wrapped context, which the plain one would miss.
     */
    private fun localizedContext(): Context = AppLanguageSettings.wrap(getApplication<Application>())

    /** A status, error, or other user-facing message the view model writes into its state. */
    private fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): String = localizedContext().getString(id, *args)

    /** Why the page and the app's own download both gave no full transcript of [videoId]. */
    private fun fullTranscriptProblem(
        videoId: String,
        downloadError: Exception,
    ): String {
        val page =
            when (pageTranscripts.problem(videoId)) {
                PageTranscriptProblem.NO_ANSWER -> text(R.string.status_transcript_page_no_answer)
                PageTranscriptProblem.NO_TRACKS -> text(R.string.status_transcript_page_no_tracks)
                PageTranscriptProblem.NO_PLAYER -> text(R.string.status_transcript_page_no_player)
                PageTranscriptProblem.WAITING_FOR_CAPTIONS -> text(R.string.status_transcript_page_waiting)
                PageTranscriptProblem.UNREADABLE -> text(R.string.status_transcript_page_unreadable)
                null -> null
            }
        // The error's own message is English diagnostic text, like Google's failure detail.
        val detail = downloadError.message?.takeIf(String::isNotBlank) ?: downloadError.javaClass.simpleName
        // Where the page looked, also English diagnostic text, so a phone test shows which step failed.
        val pageDetail = pageTranscripts.problemDetail(videoId)?.let { text(R.string.status_transcript_page_detail, it) }
        return listOfNotNull(page, pageDetail, text(R.string.status_transcript_download_failed, detail)).joinToString("\n")
    }

    /** A language's name in the interface language, for use inside [text] messages. */
    private fun languageName(code: String): String = languageDisplayName(code, localizedContext().interfaceLocale())

    private fun mobileWatchUrl(videoId: String): String = "https://m.youtube.com/watch?v=$videoId"

    private fun isCurrentLoad(
        state: DualSubUiState,
        videoId: String,
        generation: Long,
    ): Boolean = generation == loadGeneration && state.activeVideoId == videoId
}
