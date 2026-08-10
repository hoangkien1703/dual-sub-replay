package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionUnavailableException
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.YouTubeCaptionProvider
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import com.kienhoang.dualsubreplay.translation.OnDeviceTranslator
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoadStage { IDLE, LOADING_CAPTIONS, TRANSLATING, READY, ERROR }

enum class PlayerAspectRatio(
    val label: String,
    val heightOverWidth: Float,
) {
    WIDE_16_9("16:9", 9f / 16f),
    CLASSIC_4_3("4:3", 3f / 4f),
    PORTRAIT_3_4("3:4", 4f / 3f),
    SQUARE_1_1("1:1", 1f),
}

data class DualSubUiState(
    val currentPageUrl: String = YOUTUBE_HOME_URL,
    val navigationRequestId: Long = 0L,
    val videoId: String? = null,
    val subtitlePanelVisible: Boolean = false,
    val sourcePreference: String = "auto",
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val currentIndex: Int = -1,
    val fontScale: Float = 1f,
    val playerAspectRatio: PlayerAspectRatio = PlayerAspectRatio.WIDE_16_9,
    val subtitleAreaFraction: Float = 1f,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

private const val YOUTUBE_HOME_URL = "https://m.youtube.com/"

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val captionProvider: CaptionProvider = YouTubeCaptionProvider()
    private val translator = OnDeviceTranslator()
    private var loadingJob: Job? = null

    private val _state = MutableStateFlow(
        DualSubUiState(
            fontScale = preferences.getFloat("font_scale", 1f),
            playerAspectRatio = preferences.getString("player_aspect_ratio", null)
                ?.let { stored -> PlayerAspectRatio.entries.firstOrNull { it.name == stored } }
                ?: PlayerAspectRatio.WIDE_16_9,
            subtitleAreaFraction = preferences.getFloat("subtitle_area_fraction", 1f)
                .coerceIn(0.35f, 1f),
        ),
    )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

    fun acceptSharedText(text: String) {
        val videoId = YouTubeUrlParser.extractVideoId(text) ?: return
        val url = "https://m.youtube.com/watch?v=$videoId"
        _state.update {
            it.copy(
                currentPageUrl = url,
                navigationRequestId = it.navigationRequestId + 1,
            )
        }
        loadVideo(videoId, url, showPanel = true)
    }

    fun onBrowserUrlChanged(url: String) {
        val videoId = YouTubeUrlParser.extractVideoId(url)
        val current = _state.value
        if (videoId == null) {
            if (current.videoId != null) {
                loadingJob?.cancel()
                _state.update {
                    it.copy(
                        currentPageUrl = url,
                        videoId = null,
                        subtitlePanelVisible = false,
                        segments = emptyList(),
                        currentIndex = -1,
                        stage = LoadStage.IDLE,
                        statusMessage = null,
                        errorMessage = null,
                    )
                }
            } else if (url != current.currentPageUrl) {
                _state.update { it.copy(currentPageUrl = url) }
            }
            return
        }

        if (videoId == current.videoId) {
            if (url != current.currentPageUrl) _state.update { it.copy(currentPageUrl = url) }
            return
        }
        loadVideo(videoId, url, showPanel = true)
    }

    fun setSourcePreference(language: String) {
        if (_state.value.sourcePreference == language) return
        _state.update { it.copy(sourcePreference = language) }
        val current = _state.value
        current.videoId?.let { loadVideo(it, current.currentPageUrl, showPanel = true) }
    }

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
    }

    fun setPlayerAspectRatio(aspectRatio: PlayerAspectRatio) {
        preferences.edit().putString("player_aspect_ratio", aspectRatio.name).apply()
        _state.update { it.copy(playerAspectRatio = aspectRatio) }
    }

    fun setSubtitleAreaFraction(fraction: Float) {
        val safeFraction = fraction.coerceIn(0.35f, 1f)
        preferences.edit().putFloat("subtitle_area_fraction", safeFraction).apply()
        _state.update { it.copy(subtitleAreaFraction = safeFraction) }
    }

    fun retryCaptions() {
        val current = _state.value
        current.videoId?.let { loadVideo(it, current.currentPageUrl, showPanel = true) }
    }

    fun showSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = true) }

    fun hideSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = false) }

    private fun loadVideo(videoId: String, url: String, showPanel: Boolean) {
        preferences.edit().putString("last_url", url).apply()
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    currentPageUrl = url,
                    videoId = videoId,
                    subtitlePanelVisible = showPanel,
                    resolvedSourceLanguage = null,
                    generatedCaptions = false,
                    segments = emptyList(),
                    currentIndex = -1,
                    stage = LoadStage.LOADING_CAPTIONS,
                    statusMessage = "Finding the best caption track…",
                    errorMessage = null,
                )
            }
            try {
                val preferredLanguages = when (_state.value.sourcePreference) {
                    "en" -> listOf("en", "ja")
                    "ja" -> listOf("ja", "en")
                    else -> listOf("en", "ja")
                }
                val track = captionProvider.fetch(videoId, preferredLanguages)
                val merged = SubtitleMerger.merge(track.cues)
                if (merged.isEmpty()) throw CaptionUnavailableException("This caption track contains no readable text.")

                _state.update {
                    it.copy(
                        resolvedSourceLanguage = track.languageCode,
                        generatedCaptions = track.isGenerated,
                        segments = merged,
                        stage = LoadStage.TRANSLATING,
                        statusMessage = "Downloading the translation model and translating…",
                    )
                }

                translator.translateAll(
                    sourceLanguageCode = track.languageCode,
                    texts = merged.map(SubtitleSegment::originalText),
                ) { index, translatedText ->
                    _state.update { current ->
                        current.copy(
                            segments = current.segments.mapIndexed { itemIndex, segment ->
                                if (itemIndex == index) segment.copy(translatedText = translatedText) else segment
                            },
                            statusMessage = "Translating ${index + 1} of ${merged.size}…",
                        )
                    }
                }
                _state.update {
                    it.copy(
                        stage = LoadStage.READY,
                        statusMessage = if (track.isGenerated) "Using auto-generated captions" else "Captions ready",
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        stage = LoadStage.ERROR,
                        statusMessage = null,
                        errorMessage = error.message ?: "The captions could not be loaded.",
                    )
                }
            }
        }
    }

    fun updatePlaybackSecond(second: Float) {
        val timeMs = (second * 1_000).toLong()
        val segments = _state.value.segments
        val index = segments.indexOfLast { timeMs >= it.startMs }
        if (index != _state.value.currentIndex) _state.update { it.copy(currentIndex = index) }
    }
}
