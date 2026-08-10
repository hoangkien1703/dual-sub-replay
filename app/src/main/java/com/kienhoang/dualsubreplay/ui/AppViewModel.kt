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

data class DualSubUiState(
    val inputUrl: String = "",
    val videoId: String? = null,
    val sourcePreference: String = "auto",
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val currentIndex: Int = -1,
    val fontScale: Float = 1f,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val captionProvider: CaptionProvider = YouTubeCaptionProvider()
    private val translator = OnDeviceTranslator()
    private var loadingJob: Job? = null

    private val _state = MutableStateFlow(
        DualSubUiState(
            inputUrl = preferences.getString("last_url", "").orEmpty(),
            fontScale = preferences.getFloat("font_scale", 1f),
        ),
    )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

    fun updateInput(value: String) = _state.update { it.copy(inputUrl = value) }

    fun acceptSharedText(text: String) {
        _state.update { it.copy(inputUrl = text) }
        loadVideo()
    }

    fun setSourcePreference(language: String) {
        if (_state.value.sourcePreference == language) return
        _state.update { it.copy(sourcePreference = language) }
        if (_state.value.videoId != null) loadVideo()
    }

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
    }

    fun loadVideo() {
        val input = _state.value.inputUrl
        val videoId = YouTubeUrlParser.extractVideoId(input)
        if (videoId == null) {
            _state.update {
                it.copy(
                    stage = LoadStage.ERROR,
                    errorMessage = "Paste a valid YouTube link or 11-character video ID.",
                )
            }
            return
        }

        preferences.edit().putString("last_url", input).apply()
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    videoId = videoId,
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
