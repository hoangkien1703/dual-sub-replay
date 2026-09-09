package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.data.AnalyzedToken
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.VocabularyRepository
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.YouTubeCaptionProvider
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import com.kienhoang.dualsubreplay.data.retireLegacyDownloadJobs
import com.kienhoang.dualsubreplay.data.savedWordFrom
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.kienhoang.dualsubreplay.data.activeWordIndex as timedActiveWordIndex

enum class LoadStage { IDLE, LOADING_CAPTIONS, TRANSLATING, READY, ERROR }

data class DualSubUiState(
    val liveFallback: Boolean = false,
    val liveOriginal: String? = null,
    val liveTranslated: String? = null,
    val retryingTranscript: Boolean = false,
    val browserUrl: String = YOUTUBE_HOME_URL,
    val browserNavigationRequestId: Long = 0L,
    val activeVideoId: String? = null,
    val subtitlePanelVisible: Boolean = true,
    val sourcePreference: String = "auto",
    val targetLanguage: String = "vi",
    val onboardingCompleted: Boolean = false,
    val guideCompleted: Boolean = false,
    val availableSourceLanguages: List<CaptionLanguage> = emptyList(),
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val playbackPaused: Boolean = false,
    val originalVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    val translatedVisibility: CaptionVisibility = CaptionVisibility.ALWAYS,
    val currentIndex: Int = -1,
    val activeWordIndex: Int = -1,
    val fontScale: Float = 1f,
    val landscapeSplitEnabled: Boolean = true,
    val originalColorKey: String = DEFAULT_ORIGINAL_COLOR_KEY,
    val translatedColorKey: String = DEFAULT_TRANSLATED_COLOR_KEY,
    val highlightColorKey: String = DEFAULT_HIGHLIGHT_COLOR_KEY,
    val wordHighlightEnabled: Boolean = true,
    val customColorsEnabled: Boolean = true,
    val captionFormat: CaptionFormat = CaptionFormat.SHORT_PHRASES,
    val isDownloadingTranslationModel: Boolean = false,
    val lockOverlayToVideo: Boolean = false,
    val preloadModelsEnabled: Boolean = true,
    val naturalSubtitlesEnabled: Boolean = true,
    val wordLearningEnabled: Boolean = true,
    val wordLearningTarget: String = "both",
    val wordLearningActiveOnly: Boolean = true,
    val tapToLearnEnabled: Boolean = true,
    val selectedLearningWord: LearningWordSelection? = null,
    val autoPronounce: Boolean = true,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

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

internal const val TRANSLATION_PUBLISH_BATCH = 8

/**
 * Picks up to [batchSize] pending indices nearest to [positionIndex] by walking
 * outward from the insertion point, so translation always follows the current
 * playback position even after seeks.
 */
internal fun nearestUntranslatedBatch(
    pendingIndices: List<Int>,
    positionIndex: Int,
    batchSize: Int = TRANSLATION_PUBLISH_BATCH,
): List<Int> {
    if (pendingIndices.isEmpty() || batchSize <= 0) return emptyList()
    var up = pendingIndices.binarySearch(positionIndex)
    if (up < 0) up = -(up + 1)
    var down = up - 1
    val result = ArrayList<Int>(minOf(batchSize, pendingIndices.size))
    while (result.size < batchSize && (up < pendingIndices.size || down >= 0)) {
        val upIndex = if (up < pendingIndices.size) pendingIndices[up] else Int.MAX_VALUE
        val downIndex = if (down >= 0) pendingIndices[down] else Int.MAX_VALUE
        if (abs(upIndex - positionIndex) <= abs(downIndex - positionIndex)) {
            result.add(upIndex)
            up += 1
        } else {
            result.add(downIndex)
            down -= 1
        }
    }
    return result
}

internal const val YOUTUBE_HOME_URL = "https://m.youtube.com/"

internal fun preferredCaptionLanguages(sourcePreference: String): List<String> =
    sourcePreference.takeUnless { it == "auto" }?.let(::listOf).orEmpty()

internal fun resolvedSourcePreference(
    requested: String,
    resolved: String,
): String =
    requested.takeIf {
        it == "auto" || TranslationLanguages.normalize(it) == TranslationLanguages.normalize(resolved)
    } ?: "auto"

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
internal const val SPLIT_LONG_SENTENCES_PREFERENCE = "split_long_sentences"
internal const val LOCK_OVERLAY_TO_VIDEO_PREFERENCE = "lock_overlay_to_video_player"
internal const val PRELOAD_MODELS_ENABLED_PREFERENCE = "preload_translation_models"
internal const val NATURAL_SUBTITLES_PREFERENCE = "enhanced_natural_subtitles"
internal const val WORD_LEARNING_ENABLED_PREFERENCE = "word_learning_mode_enabled"
internal const val WORD_LEARNING_TARGET_PREFERENCE = "word_learning_target"
internal const val TAP_TO_LEARN_PREFERENCE = "tap_to_learn_enabled"
internal const val WORD_LEARNING_ACTIVE_ONLY_PREFERENCE = "word_learning_active_only"

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
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, YouTubeCaptionProvider())

    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val translator = OnDeviceTranslator()
    internal val vocabulary = VocabularyRepository.get(application)
    private var loadingJob: Job? = null
    private var translationWarmupJob: Job? = null
    private var loadGeneration = 0L
    private var latestPlaybackSecondMs = 0L
    private var rawMergedSegments: List<SubtitleSegment> = emptyList()
    private var lastEngineSampleAt = 0L
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
                fontScale = preferences.getFloat("font_scale", 1f),
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
                        "both",
                    ) ?: "both",
                wordLearningActiveOnly =
                    storedFeatureEnabled(
                        preferences.getBoolean(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE, true),
                    ),
                autoPronounce = preferences.getBoolean("auto_pronounce", true),
                tapToLearnEnabled =
                    storedFeatureEnabled(
                        preferences.getBoolean(TAP_TO_LEARN_PREFERENCE, true),
                    ),
            ),
        )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

    private val visibilityListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ORIGINAL_VISIBILITY || key == TRANSLATED_VISIBILITY || key == null) {
                _state.update {
                    it.copy(
                        originalVisibility = storedCaptionVisibility(preferences.getString(ORIGINAL_VISIBILITY, null)),
                        translatedVisibility = storedCaptionVisibility(preferences.getString(TRANSLATED_VISIBILITY, null)),
                    )
                }
            }
        }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(visibilityListener)
        super.onCleared()
    }

    internal fun onWebPlaybackPaused(
        videoId: String,
        paused: Boolean,
    ) {
        _state.update { if (it.activeVideoId == videoId) it.copy(playbackPaused = paused) else it }
    }

    init {
        preferences.edit().remove(KARAOKE_TIMING_MODE_PREFERENCE).apply()
        preferences.registerOnSharedPreferenceChangeListener(visibilityListener)
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
    ) {
        val current = _state.value
        if (current.activeVideoId != videoId || !second.isFinite()) return
        val timeMs = (second.coerceAtLeast(0f) * 1_000).toLong()
        val seek =
            timeMs + LIVE_CAPTION_BACKWARD_SEEK_RESET_MS < latestPlaybackSecondMs ||
                timeMs > latestPlaybackSecondMs + 2_000L
        if (current.liveFallback) {
            latestPlaybackSecondMs = timeMs
            updateLiveFallbackPlayback(videoId, liveCaption, seek)
            return
        }
        if (seek) {
            lastEngineSampleAt = 0L
        }
        latestPlaybackSecondMs = timeMs
        val timedIndex = activeSubtitleIndex(current.segments, timeMs)
        val referenceIndex =
            if (timedIndex >= 0) {
                timedIndex
            } else {
                nearestSegmentIndex(current.segments, timeMs)
            }
        applyEngineCaption(videoId, liveCaption, current, referenceIndex, timedIndex, seek)
    }

    private fun updateLiveFallbackPlayback(videoId: String, liveCaption: LiveCaptionSample?, seek: Boolean) {
        val engineEvent = liveCaption?.activeWordIndex != null
        if (engineEvent || lastEngineSampleAt == 0L || android.os.SystemClock.elapsedRealtime() - lastEngineSampleAt >= 500) {
            updateLiveSubtitle(videoId, liveCaption, seek)
        }
        if (engineEvent) lastEngineSampleAt = android.os.SystemClock.elapsedRealtime()
        if (liveCaption?.activeWordIndex != null) {
            _state.update { it.copy(activeWordIndex = if (it.wordHighlightEnabled && !seek) liveCaption.activeWordIndex else -1) }
        }
    }

    private fun applyEngineCaption(
        videoId: String,
        liveCaption: LiveCaptionSample?,
        current: DualSubUiState,
        referenceIndex: Int,
        timedIndex: Int,
        seek: Boolean,
    ) {
        val engineSample = liveCaption?.takeIf { it.activeWordIndex != null && it.videoId == videoId }
        if (engineSample != null) lastEngineSampleAt = android.os.SystemClock.elapsedRealtime()
        val enginePosition = engineSample?.takeIf { it.present }?.let {
            mapLiveCaptionWord(current.segments, referenceIndex, karaokeTokens(it.text), it.activeWordIndex!!)
        }
        // Playback polling still drives replay/paragraph position. It never
        // reselects a spoken word or overrides an engine heartbeat.
        val engineFresh = lastEngineSampleAt > 0 && android.os.SystemClock.elapsedRealtime() - lastEngineSampleAt < 500
        val index = when {
            enginePosition != null && current.wordHighlightEnabled -> enginePosition.segmentIndex
            engineSample == null && engineFresh && !seek && current.wordHighlightEnabled -> current.currentIndex
            else -> timedIndex
        }
        val wordIndex = when {
            !current.wordHighlightEnabled || seek -> -1
            engineSample != null -> enginePosition?.wordIndex ?: -1
            engineFresh -> current.activeWordIndex
            else -> -1
        }
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
                        "Waiting for YouTube captions and their language. Play the video with captions enabled."
                    } else {
                        "Translating live captions…"
                    },
            )
        }
        if (key == null) return
        if (!TranslationLanguages.isSupported(key.language)) {
            _state.update {
                it.copy(
                    statusMessage = "Live translation is not supported for ${TranslationLanguages.displayName(key.language)}.",
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
                            translate = translator::translateSingle,
                        ) ?: return@launch
                    if (liveTranslationGate.accepts(ticket, key) && _state.value.liveFallback && _state.value.activeVideoId == videoId) {
                        _state.update {
                            it.copy(
                                liveTranslated = translated,
                                statusMessage = "Current captions only; paragraph replay is unavailable.",
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (liveTranslationGate.accepts(ticket, key)) {
                        _state.update {
                            it.copy(
                                statusMessage = "Live translation unavailable. Check the connection for the language model download.",
                            )
                        }
                    }
                }
            }
    }

    fun setSourcePreference(language: String) {
        val normalized = language.takeIf { it == "auto" } ?: TranslationLanguages.normalize(language)
        val current = _state.value
        if (!shouldAcceptSourcePreference(normalized, current.availableSourceLanguages)) return
        if (current.sourcePreference == normalized) return
        preferences.edit().putString("preferred_caption_language", normalized).apply()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        _state.update { it.copy(sourcePreference = normalized) }
        val videoId = _state.value.activeVideoId ?: return
        loadVideo(videoId, showPanel = true)
    }

    fun setTargetLanguage(language: String) {
        val normalized = TranslationLanguages.normalize(language)
        if (!TranslationLanguages.isSupported(normalized) || _state.value.targetLanguage == normalized) return
        preferences.edit().putString("target_language", normalized).apply()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        _state.update { it.copy(targetLanguage = normalized, liveTranslated = null) }
        if (_state.value.activeVideoId != null && _state.value.segments.isNotEmpty()) {
            retranslateCurrentSegments()
        }
    }

    fun completeOnboarding(
        nativeLanguage: String,
        learningLanguage: String,
    ) {
        val languages = normalizedOnboardingLanguages(nativeLanguage, learningLanguage) ?: return
        val (native, learning) = languages
        preferences.edit().putString("preferred_caption_language", learning).apply()
        preferences.edit().putString("target_language", native).apply()
        _state.update { it.copy(sourcePreference = learning, targetLanguage = native) }
        warmTranslationModel(sourceLanguage = learning, targetLanguage = native)
        if (_state.value.activeVideoId != null && _state.value.segments.isNotEmpty()) {
            retranslateCurrentSegments()
        }
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
        preferences.edit().putBoolean(GUIDE_COMPLETED_PREFERENCE, true).apply()
        _state.update { it.copy(guideCompleted = true) }
    }

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
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
        lastEngineSampleAt = 0L
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
        lastEngineSampleAt = 0L
        refreshSplitSegments()
    }

    private fun refreshSplitSegments() {
        if (_state.value.activeVideoId != null && rawMergedSegments.isNotEmpty()) {
            retranslateCurrentSegments()
        }
    }

    fun setLockOverlayToVideo(locked: Boolean) {
        preferences.edit().putBoolean(LOCK_OVERLAY_TO_VIDEO_PREFERENCE, locked).apply()
        _state.update { it.copy(lockOverlayToVideo = locked) }
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
        val source = current.resolvedSourceLanguage ?: current.sourcePreference.takeUnless { it == "auto" } ?: "en"
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

    internal suspend fun translateSelection(selection: LearningWordSelection): String =
        translator.translateSingle(selection.wordLanguage, selection.meaningLanguage, selection.token.text)

    internal suspend fun saveWord(
        selection: LearningWordSelection,
        meaning: String,
        online: Boolean,
    ): SavedWord = vocabulary.save(savedWordFrom(selection, meaning, online))

    suspend fun translateWord(word: String): String {
        val current = _state.value
        val source = current.resolvedSourceLanguage ?: current.sourcePreference.takeUnless { it == "auto" } ?: "en"
        return translator.translateSingle(source, current.targetLanguage, word)
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
        lastEngineSampleAt = 0L
        _state.update { current ->
            current.copy(
                sourcePreference = "auto",
                targetLanguage = "vi",
                fontScale = 1f,
                landscapeSplitEnabled = true,
                originalColorKey = DEFAULT_ORIGINAL_COLOR_KEY,
                translatedColorKey = DEFAULT_TRANSLATED_COLOR_KEY,
                highlightColorKey = DEFAULT_HIGHLIGHT_COLOR_KEY,
                wordHighlightEnabled = true,
                customColorsEnabled = true,
                originalVisibility = CaptionVisibility.ALWAYS,
                translatedVisibility = CaptionVisibility.ALWAYS,
                captionFormat = CaptionFormat.SHORT_PHRASES,
                lockOverlayToVideo = false,
                preloadModelsEnabled = true,
                naturalSubtitlesEnabled = true,
                wordLearningEnabled = true,
                wordLearningTarget = "both",
                wordLearningActiveOnly = true,
                tapToLearnEnabled = true,
                selectedLearningWord = null,
            )
        }
        // "Reset all settings" re-enables sentence splitting, so the currently
        // open video switches back to the default short-chunk presentation.
        refreshSplitSegments()
    }

    fun retryCaptions() {
        val videoId = _state.value.activeVideoId ?: return
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
        loadGeneration += 1
        loadingJob?.cancel()
        liveTranslationJob?.cancel()
        liveTranslationGate.reset()
        latestPlaybackSecondMs = 0L
        lastEngineSampleAt = 0L
        rawMergedSegments = emptyList()
        _state.update {
            it.copy(
                activeVideoId = null,
                liveFallback = false, liveOriginal = null, liveTranslated = null, retryingTranscript = false,
                subtitlePanelVisible = true,
                availableSourceLanguages = emptyList(),
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
        lastEngineSampleAt = 0L
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
        }
        _state.update {
            it.copy(
                liveFallback = preserveLive,
                liveOriginal = if (preserveLive) it.liveOriginal else null,
                liveTranslated = if (preserveLive) it.liveTranslated else null,
                retryingTranscript = preserveLive,
                activeVideoId = videoId,
                playbackPaused = false,
                subtitlePanelVisible = showPanel,
                availableSourceLanguages = emptyList(),
                resolvedSourceLanguage = if (preserveLive) it.resolvedSourceLanguage else null,
                generatedCaptions = false,
                segments = emptyList(),
                currentIndex = -1,
                activeWordIndex = -1,
                stage = LoadStage.LOADING_CAPTIONS,
                statusMessage = if (preserveLive) it.statusMessage else "Finding the best caption track…",
                errorMessage = null,
            )
        }
        loadingJob =
            viewModelScope.launch {
                try {
                    val preferredLanguages = preferredCaptionLanguages(_state.value.sourcePreference)
                    val track = captionProvider.fetch(videoId, preferredLanguages)
                    val merged = mergeCaptionTrack(track)
                    if (merged.isEmpty()) {
                        throw CaptionUnavailableException("This caption track contains no readable text.")
                    }
                    if (!isCurrentLoad(_state.value, videoId, generation)) return@launch
                    val displaySegments = captionDisplaySegments(merged, _state.value.captionFormat, _state.value.naturalSubtitlesEnabled)
                    rawMergedSegments = merged
                    liveTranslationGate.reset()
                    liveTranslationJob?.cancel()

                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) return@update current
                        current.copy(
                            liveFallback = false,
                            liveOriginal = null,
                            liveTranslated = null,
                            retryingTranscript = false,
                            resolvedSourceLanguage = track.languageCode,
                            sourcePreference =
                                resolvedSourcePreference(
                                    current.sourcePreference,
                                    track.languageCode,
                                ),
                            availableSourceLanguages = track.availableLanguages,
                            generatedCaptions = track.isGenerated,
                            segments = displaySegments,
                            stage = LoadStage.TRANSLATING,
                            statusMessage = translationStartingMessage(current.targetLanguage),
                        )
                    }

                    translateSegments(
                        videoId = videoId,
                        generation = generation,
                        sourceLanguage = track.languageCode,
                        targetLanguage = _state.value.targetLanguage,
                        segments = displaySegments,
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { current ->
                        if (!isCurrentLoad(current, videoId, generation)) return@update current
                        current.copy(
                            stage = LoadStage.READY,
                            liveFallback = true,
                            retryingTranscript = false,
                            statusMessage =
                                if (current.liveOriginal !=
                                    null
                                ) {
                                    current.statusMessage
                                } else {
                                    "Waiting for YouTube captions and their language. Play the video with captions enabled."
                                },
                            errorMessage = null,
                        )
                    }
                }
            }
    }

    private fun mergeCaptionTrack(track: com.kienhoang.dualsubreplay.data.CaptionTrackResult) = SubtitleMerger.merge(
        track.cues,
        enhancedNaturalFlow = _state.value.naturalSubtitlesEnabled,
    )

    private fun retranslateCurrentSegments() {
        val current = _state.value
        val videoId = current.activeVideoId ?: return
        val sourceLanguage = current.resolvedSourceLanguage ?: return
        // Re-derive from the raw merged captions so toggling the sentence
        // splitter (issue #25) always starts from un-split text.
        val baseSegments = rawMergedSegments.ifEmpty { current.segments }
        if (baseSegments.isEmpty()) return
        val segments = captionDisplaySegments(baseSegments, current.captionFormat, current.naturalSubtitlesEnabled)

        val generation = ++loadGeneration
        loadingJob?.cancel()
        _state.update {
            val index = activeSubtitleIndex(segments, latestPlaybackSecondMs)
            it.copy(
                segments = segments.map { segment -> segment.copy(translatedText = null) },
                currentIndex = index,
                // The next engine heartbeat remaps the word onto the new caption layout.
                activeWordIndex = -1,
                stage = LoadStage.TRANSLATING,
                statusMessage = translationStartingMessage(it.targetLanguage),
                errorMessage = null,
            )
        }
        loadingJob =
            viewModelScope.launch {
                try {
                    translateSegments(
                        videoId = videoId,
                        generation = generation,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = _state.value.targetLanguage,
                        segments = segments,
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { state ->
                        if (!isCurrentLoad(state, videoId, generation)) return@update state
                        state.copy(
                            stage = LoadStage.ERROR,
                            statusMessage = null,
                            errorMessage = error.message ?: "The subtitles could not be translated.",
                        )
                    }
                }
            }
    }

    private suspend fun translateSegments(
        videoId: String,
        generation: Long,
        sourceLanguage: String,
        targetLanguage: String,
        segments: List<SubtitleSegment>,
    ) {
        translateCaptionUnits(
            translator = translator,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            display = segments,
            playbackTime = { latestPlaybackSecondMs },
            onDownloading = { downloading ->
                _state.update { current ->
                    if (!isCurrentLoad(current, videoId, generation)) return@update current
                    current.copy(
                        isDownloadingTranslationModel = downloading,
                        statusMessage =
                            if (downloading) {
                                "Downloading ${TranslationLanguages.displayName(
                                    targetLanguage,
                                )} model…"
                            } else {
                                current.statusMessage
                            },
                    )
                }
            },
            onProgress = { snapshot, completed, total ->
                _state.update { current ->
                    if (!isCurrentLoad(current, videoId, generation)) return@update current
                    current.copy(
                        segments = snapshot,
                        statusMessage = "Translating $completed of $total…",
                    )
                }
            },
        )
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) return@update current
            current.copy(
                stage = LoadStage.READY,
                statusMessage =
                    if (current.generatedCaptions) {
                        "Using auto-generated captions"
                    } else {
                        "Captions ready"
                    },
            )
        }
    }

    private fun translationStartingMessage(targetLanguage: String): String =
        "Preparing ${TranslationLanguages.displayName(targetLanguage)} translation…"

    private fun mobileWatchUrl(videoId: String): String = "https://m.youtube.com/watch?v=$videoId"

    private fun isCurrentLoad(
        state: DualSubUiState,
        videoId: String,
        generation: Long,
    ): Boolean = generation == loadGeneration && state.activeVideoId == videoId
}
