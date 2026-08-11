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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AppDestination {
    data object Browse : AppDestination

    data class Learning(val session: WatchSession) : AppDestination
}

data class WatchSession(
    val videoId: String,
    val canonicalUrl: String,
    val returnBrowseUrl: String,
    val resumeSecond: Float = 0f,
)

enum class LoadStage { IDLE, LOADING_CAPTIONS, TRANSLATING, READY, ERROR }

data class DualSubUiState(
    val browserUrl: String = YOUTUBE_HOME_URL,
    val browserNavigationRequestId: Long = 0L,
    val destination: AppDestination = AppDestination.Browse,
    val subtitlePanelVisible: Boolean = true,
    val sourcePreference: String = "auto",
    val resolvedSourceLanguage: String? = null,
    val generatedCaptions: Boolean = false,
    val segments: List<SubtitleSegment> = emptyList(),
    val currentIndex: Int = -1,
    val fontScale: Float = 1f,
    val stage: LoadStage = LoadStage.IDLE,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val watchSession: WatchSession?
        get() = (destination as? AppDestination.Learning)?.session
}

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

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
    private val captionProvider: CaptionProvider = YouTubeCaptionProvider()
    private val translator = OnDeviceTranslator()
    private var loadingJob: Job? = null
    private var loadGeneration = 0L
    private var latestPlaybackSecond = 0f

    private val _state = MutableStateFlow(
        DualSubUiState(
            browserUrl = preferences.getString("last_browser_url", YOUTUBE_HOME_URL)
                ?.takeIf { it.startsWith("https://") }
                ?: YOUTUBE_HOME_URL,
            fontScale = preferences.getFloat("font_scale", 1f),
        ),
    )
    val state: StateFlow<DualSubUiState> = _state.asStateFlow()

    fun acceptSharedText(text: String) {
        val videoId = YouTubeUrlParser.extractVideoId(text) ?: return
        openLearning(videoId, canonicalWatchUrl(videoId), _state.value.browserUrl)
    }

    fun onBrowseUrlChanged(url: String) {
        if (YouTubeUrlParser.extractVideoId(url) != null) return
        if (url == _state.value.browserUrl) return
        preferences.edit().putString("last_browser_url", url).apply()
        _state.update { it.copy(browserUrl = url) }
    }

    fun onVideoSelected(videoId: String, canonicalUrl: String) {
        val current = _state.value
        val returnUrl = current.watchSession?.returnBrowseUrl ?: current.browserUrl
        openLearning(videoId, canonicalUrl, returnUrl)
    }

    fun exitLearning() {
        if (_state.value.destination !is AppDestination.Learning) return
        loadGeneration += 1
        loadingJob?.cancel()
        latestPlaybackSecond = 0f
        _state.update {
            it.copy(
                destination = AppDestination.Browse,
                subtitlePanelVisible = true,
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

    fun onPlayerVideoId(sourceVideoId: String, videoId: String) {
        val session = _state.value.watchSession ?: return
        if (session.videoId != sourceVideoId) return
        if (videoId == session.videoId) return
        openLearning(videoId, canonicalWatchUrl(videoId), session.returnBrowseUrl)
    }

    fun onPlayerCurrentSecond(videoId: String, second: Float) {
        val current = _state.value
        if (current.watchSession?.videoId != videoId || !second.isFinite()) return
        latestPlaybackSecond = second.coerceAtLeast(0f)
        val timeMs = (latestPlaybackSecond * 1_000).toLong()
        val index = activeSubtitleIndex(current.segments, timeMs)
        if (index != current.currentIndex) _state.update { it.copy(currentIndex = index) }
    }

    fun playbackResumeSecond(videoId: String): Float =
        if (_state.value.watchSession?.videoId == videoId) latestPlaybackSecond else 0f

    fun setSourcePreference(language: String) {
        if (_state.value.sourcePreference == language) return
        _state.update { it.copy(sourcePreference = language) }
        val session = _state.value.watchSession ?: return
        loadVideo(session, showPanel = true)
    }

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat("font_scale", safeScale).apply()
        _state.update { it.copy(fontScale = safeScale) }
    }

    fun retryCaptions() {
        val session = _state.value.watchSession ?: return
        loadVideo(session, showPanel = true)
    }

    fun showSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = true) }

    fun hideSubtitlePanel() = _state.update { it.copy(subtitlePanelVisible = false) }

    private fun openLearning(videoId: String, canonicalUrl: String, returnBrowseUrl: String) {
        val currentSession = _state.value.watchSession
        if (currentSession?.videoId == videoId) return
        latestPlaybackSecond = 0f
        val session = WatchSession(
            videoId = videoId,
            canonicalUrl = canonicalUrl,
            returnBrowseUrl = returnBrowseUrl,
        )
        loadVideo(session, showPanel = true)
    }

    private fun loadVideo(session: WatchSession, showPanel: Boolean) {
        val generation = ++loadGeneration
        loadingJob?.cancel()
        _state.update {
            it.copy(
                destination = AppDestination.Learning(session),
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
        loadingJob = viewModelScope.launch {
            try {
                val preferredLanguages = when (_state.value.sourcePreference) {
                    "en" -> listOf("en", "ja")
                    "ja" -> listOf("ja", "en")
                    else -> listOf("en", "ja")
                }
                val track = captionProvider.fetch(session.videoId, preferredLanguages)
                val merged = SubtitleMerger.merge(track.cues)
                if (merged.isEmpty()) {
                    throw CaptionUnavailableException("This caption track contains no readable text.")
                }
                if (!isCurrentLoad(_state.value, session.videoId, generation)) return@launch

                _state.update { current ->
                    if (!isCurrentLoad(current, session.videoId, generation)) return@update current
                    current.copy(
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
                        if (!isCurrentLoad(current, session.videoId, generation)) return@update current
                        current.copy(
                            segments = current.segments.mapIndexed { itemIndex, segment ->
                                if (itemIndex == index) segment.copy(translatedText = translatedText) else segment
                            },
                            statusMessage = "Translating ${index + 1} of ${merged.size}…",
                        )
                    }
                }
                _state.update { current ->
                    if (!isCurrentLoad(current, session.videoId, generation)) return@update current
                    current.copy(
                        stage = LoadStage.READY,
                        statusMessage = if (track.isGenerated) {
                            "Using auto-generated captions"
                        } else {
                            "Captions ready"
                        },
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { current ->
                    if (!isCurrentLoad(current, session.videoId, generation)) return@update current
                    current.copy(
                        stage = LoadStage.ERROR,
                        statusMessage = null,
                        errorMessage = error.message ?: "The captions could not be loaded.",
                    )
                }
            }
        }
    }

    private fun canonicalWatchUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId"

    private fun isCurrentLoad(state: DualSubUiState, videoId: String, generation: Long): Boolean =
        generation == loadGeneration && state.watchSession?.videoId == videoId
}
