package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.YouTubeCaptionProvider
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoadStage { IDLE, LOADING_CAPTIONS, TRANSLATING, READY, ERROR }

data class DualSubUiState(
    val browserUrl: String = YOUTUBE_HOME_URL,
    val browserNavigationRequestId: Long = 0L,
    val activeVideoId: String? = null,
    val subtitlePanelVisible: Boolean = true,
    val sourcePreference: String = "auto",
    val targetLanguage: String = "vi",
    val availableSourceLanguages: List<CaptionLanguage> = emptyList(),
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val currentIndex: Int = -1,
    val fontScale: Float = 1f,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

internal fun activeSubtitleIndex(segments: List<SubtitleSegment>, timeMs: Long): Int {
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

internal const val YOUTUBE_HOME_URL = "https://m.youtube.com/"

internal fun preferredCaptionLanguages(sourcePreference: String): List<String> =
    sourcePreference.takeUnless { it == "auto" }?.let(::listOf).orEmpty()

internal fun resolvedSourcePreference(requested: String, resolved: String): String =
    requested.takeIf {
        it == "auto" || TranslationLanguages.normalize(it) == TranslationLanguages.normalize(resolved)
    } ?: "auto"

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val captionProvider: CaptionProvider = YouTubeCaptionProvider()
    private val translator = OnDeviceTranslator()
    private var loadingJob: Job? = null
    private var loadGeneration = 0L

    private val _state = MutableStateFlow(
        DualSubUiState(
            browserUrl = preferences.getString("last_browser_url", YOUTUBE_HOME_URL)
                ?.takeIf { it.startsWith("https://") }
                ?: YOUTUBE_HOME_URL,
            fontScale = preferences.getFloat("font_scale", 1f),
            targetLanguage = preferences.getString("target_language", "vi")
                ?.takeIf(TranslationLanguages::isSupported)
                ?: "vi",
        ),
    )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

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
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
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

    fun onWebPlaybackSecond(videoId: String, second: Float) {
        val current = _state.value
        if (current.activeVideoId != videoId || !second.isFinite()) return
        val timeMs = (second.coerceAtLeast(0f) * 1_000).toLong()
        val index = activeSubtitleIndex(current.segments, timeMs)
        if (index != current.currentIndex) _state.update { it.copy(currentIndex = index) }
    }

    fun setSourcePreference(language: String) {
        val normalized = language.takeIf { it == "auto" } ?: TranslationLanguages.normalize(language)
        val current = _state.value
        if (
            normalized != "auto" &&
            current.availableSourceLanguages.none {
                TranslationLanguages.normalize(it.code) == normalized
            }
        ) return
        if (current.sourcePreference == normalized) return
        _state.update { it.copy(sourcePreference = normalized) }
        val videoId = _state.value.activeVideoId ?: return
        loadVideo(videoId, showPanel = true)
    }

    fun setTargetLanguage(language: String) {
        val normalized = TranslationLanguages.normalize(language)
        if (!TranslationLanguages.isSupported(normalized) || _state.value.targetLanguage == normalized) return
        preferences.edit().putString("target_language", normalized).apply()
        _state.update { it.copy(targetLanguage = normalized) }
        if (_state.value.activeVideoId != null && _state.value.segments.isNotEmpty()) {
            retranslateCurrentSegments()
        }
    }

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
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
        _state.update {
            it.copy(
                activeVideoId = null,
                subtitlePanelVisible = true,
                availableSourceLanguages = emptyList(),
                resolvedSourceLanguage = null,
                generatedCaptions = false,
                segments = emptyList(),
                currentIndex = -1,
                stage = LoadStage.IDLE,
                statusMessage = null,
                errorMessage = null,
            )
        }
    }

    private fun loadVideo(videoId: String, showPanel: Boolean) {
        val generation = ++loadGeneration
        loadingJob?.cancel()
        _state.update {
            it.copy(
                activeVideoId = videoId,
                subtitlePanelVisible = showPanel,
                availableSourceLanguages = emptyList(),
                resolvedSourceLanguage = null,
                generatedCaptions = false,
                segments = emptyList(),
                currentIndex = -1,
                stage = LoadStage.LOADING_CAPTIONS,
                statusMessage = "Finding the best caption track…",
                errorMessage = null,
            )
        }
        loadingJob = viewModelScope.launch {
            try {
                val preferredLanguages = preferredCaptionLanguages(_state.value.sourcePreference)
                val track = captionProvider.fetch(videoId, preferredLanguages)
                val merged = SubtitleMerger.merge(track.cues)
                if (merged.isEmpty()) {
                    throw CaptionUnavailableException("This caption track contains no readable text.")
                }
                if (!isCurrentLoad(_state.value, videoId, generation)) return@launch

                _state.update { current ->
                    if (!isCurrentLoad(current, videoId, generation)) return@update current
                    current.copy(
                        resolvedSourceLanguage = track.languageCode,
                        sourcePreference = resolvedSourcePreference(
                            current.sourcePreference,
                            track.languageCode,
                        ),
                        availableSourceLanguages = track.availableLanguages,
                        generatedCaptions = track.isGenerated,
                        segments = merged,
                        stage = LoadStage.TRANSLATING,
                        statusMessage = translationStartingMessage(current.targetLanguage),
                    )
                }

                translateSegments(
                    videoId = videoId,
                    generation = generation,
                    sourceLanguage = track.languageCode,
                    targetLanguage = _state.value.targetLanguage,
                    segments = merged,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { current ->
                    if (!isCurrentLoad(current, videoId, generation)) return@update current
                    current.copy(
                        stage = LoadStage.ERROR,
                        statusMessage = null,
                        errorMessage = error.message ?: "The captions could not be loaded.",
                    )
                }
            }
        }
    }

    private fun retranslateCurrentSegments() {
        val current = _state.value
        val videoId = current.activeVideoId ?: return
        val sourceLanguage = current.resolvedSourceLanguage ?: return
        val segments = current.segments
        if (segments.isEmpty()) return

        val generation = ++loadGeneration
        loadingJob?.cancel()
        _state.update {
            it.copy(
                segments = it.segments.map { segment -> segment.copy(translatedText = null) },
                stage = LoadStage.TRANSLATING,
                statusMessage = translationStartingMessage(it.targetLanguage),
                errorMessage = null,
            )
        }
        loadingJob = viewModelScope.launch {
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
        translator.translateAll(
            sourceLanguageCode = sourceLanguage,
            targetLanguageCode = targetLanguage,
            texts = segments.map(SubtitleSegment::originalText),
        ) { index, translatedText ->
            _state.update { current ->
                if (!isCurrentLoad(current, videoId, generation)) return@update current
                current.copy(
                    segments = current.segments.mapIndexed { itemIndex, segment ->
                        if (itemIndex == index) segment.copy(translatedText = translatedText) else segment
                    },
                    statusMessage = "Translating ${index + 1} of ${segments.size}…",
                )
            }
        }
        _state.update { current ->
            if (!isCurrentLoad(current, videoId, generation)) return@update current
            current.copy(
                stage = LoadStage.READY,
                statusMessage = if (current.generatedCaptions) {
                    "Using auto-generated captions"
                } else {
                    "Captions ready"
                },
            )
        }
    }

    private fun translationStartingMessage(targetLanguage: String): String =
        "Preparing ${TranslationLanguages.displayName(targetLanguage)} translation…"

    private fun mobileWatchUrl(videoId: String): String =
        "https://m.youtube.com/watch?v=$videoId"

    private fun isCurrentLoad(state: DualSubUiState, videoId: String, generation: Long): Boolean =
        generation == loadGeneration && state.activeVideoId == videoId
}
