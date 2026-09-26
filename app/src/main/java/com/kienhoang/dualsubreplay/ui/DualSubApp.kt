package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.kienhoang.dualsubreplay.data.WordTap
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun DualSubApp(
    viewModel: AppViewModel,
    playerMode: PlayerExperienceMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
    effectivePlayerMode: PlayerExperienceMode = playerMode,
    onPlayerModeChange: (PlayerExperienceMode) -> Unit = {},
    externalSettingsRequestId: Long = 0L,
    fullscreenLearningOverlay: (@Composable BoxScope.() -> Unit)? = null,
    onNavigationVisibilityChange: (Boolean) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.setAppVisible(true)
        onStopOrDispose { viewModel.setAppVisible(false) }
    }
    val savedWords by viewModel.vocabulary.words.collectAsStateWithLifecycle()
    val webController = rememberYouTubeWebController()
    val pronouncer = rememberWordPronouncer()
    var showVocabulary by remember { mutableStateOf(false) }

    DualSubTheme {
        if (!state.onboardingCompleted) {
            LanguageSetupScreen(
                onComplete = viewModel::completeOnboarding,
                onSkip = viewModel::skipOnboarding,
            )
        } else if (!state.guideCompleted) {
            GuideScreen(onFinish = viewModel::completeGuide)
        } else {
            DualSubExperience(
                state = state,
                webController = webController,
                onVocabulary = { viewModel.selectLearningWord(null); webController.pause(); showVocabulary = true },
                onAutoPronounceChange = viewModel::setAutoPronounce,
                playerMode = playerMode,
                effectivePlayerMode = effectivePlayerMode,
                onPlayerModeChange = onPlayerModeChange,
                externalSettingsRequestId = externalSettingsRequestId,
                fullscreenLearningOverlay = fullscreenLearningOverlay,
                onNavigationVisibilityChange = onNavigationVisibilityChange,
                onPageChanged = viewModel::onYouTubePageChanged,
                onPlaybackSecond = viewModel::onWebPlaybackSecond,
                onPlaybackPaused = viewModel::onWebPlaybackPaused,
                onShowSubtitles = viewModel::showSubtitlePanel,
                onHideSubtitles = viewModel::hideSubtitlePanel,
                onRetry = viewModel::retryCaptions,
                onSourceChange = viewModel::setSourcePreference,
                onTargetChange = viewModel::setTargetLanguage,
                onFontScaleChange = viewModel::setFontScale,
                onPortraitPanelOffsetFractionChange = viewModel::setPortraitPanelOffsetFraction,
                onResetPortraitPanelPosition = viewModel::resetPortraitPanelPosition,
                onLandscapeSplitChange = viewModel::setLandscapeSplitEnabled,
                onOriginalColorChange = viewModel::setOriginalSubtitleColor,
                onTranslatedColorChange = viewModel::setTranslatedSubtitleColor,
                onHighlightColorChange = viewModel::setHighlightColor,
                onWordHighlightChange = viewModel::setWordHighlightEnabled,
                onCustomColorsChange = viewModel::setCustomColorsEnabled,
                onCaptionFormatChange = viewModel::setCaptionFormat,
                lockOverlayToVideo = state.lockOverlayToVideo,
                onLockOverlayToVideoChange = viewModel::setLockOverlayToVideo,
                preloadModelsEnabled = state.preloadModelsEnabled,
                onPreloadModelsChange = viewModel::setPreloadModelsEnabled,
                naturalSubtitlesEnabled = state.naturalSubtitlesEnabled,
                onNaturalSubtitlesChange = viewModel::setNaturalSubtitlesEnabled,
                wordLearningEnabled = state.wordLearningEnabled,
                onWordLearningChange = viewModel::setWordLearningEnabled,
                wordLearningTarget = state.wordLearningTarget,
                onWordLearningTargetChange = viewModel::setWordLearningTarget,
                wordLearningActiveOnly = state.wordLearningActiveOnly,
                onWordLearningActiveOnlyChange = viewModel::setWordLearningActiveOnly,
                tapToLearnEnabled = state.tapToLearnEnabled,
                onTapToLearnChange = viewModel::setTapToLearnEnabled,
                onWordClick = viewModel::selectLearningWord,
                onResetSettings = viewModel::resetAllSettings,
            )
        }
        state.selectedLearningWord?.let { selection ->
            androidx.compose.runtime.key(selection) {
                androidx.compose.runtime.DisposableEffect(selection) {
                    onDispose { pronouncer.stop() }
                }
                WordLearningDialog(
                    selection = selection,
                    existingWord = savedWords.firstOrNull { it.id == com.kienhoang.dualsubreplay.data.savedWordFrom(selection, "", false).id },
                    autoPronounce = state.autoPronounce,
                    onTranslateWord = { viewModel.translateSelection(selection) },
                    onSave = { meaning, online -> viewModel.saveWord(selection, meaning, online); Unit },
                    onSpeak = { webController.pause(); pronouncer.speak(selection.token.text, selection.wordLanguage) },
                    speechMessage = pronouncer.message,
                    onSpeechSettings = if (pronouncer.showSpeechSettings) pronouncer::openSpeechSettings else null,
                    onDismiss = { viewModel.selectLearningWord(null) },
                )
            }
        }
        if (showVocabulary) SavedWordsScreen(
            repository = viewModel.vocabulary,
            onOnline = { word ->
                pronouncer.stop()
                if (state.activeVideoId != word.videoId) {
                    viewModel.acceptSharedText("https://www.youtube.com/watch?v=${word.videoId}")
                }
                webController.replayClip(word)
            },
            onPause = { webController.pause() },
            onDismiss = { showVocabulary = false },
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun DualSubExperience(
    state: DualSubUiState,
    webController: YouTubeWebController,
    onVocabulary: () -> Unit,
    onAutoPronounceChange: (Boolean) -> Unit,
    playerMode: PlayerExperienceMode,
    effectivePlayerMode: PlayerExperienceMode = playerMode,
    onPlayerModeChange: (PlayerExperienceMode) -> Unit,
    externalSettingsRequestId: Long,
    fullscreenLearningOverlay: (@Composable BoxScope.() -> Unit)?,
    onNavigationVisibilityChange: (Boolean) -> Unit,
    onPageChanged: (String) -> Unit,
    onPlaybackSecond: (String, Float, LiveCaptionSample?, String) -> Unit,
    onPlaybackPaused: (String, Boolean) -> Unit,
    onShowSubtitles: () -> Unit,
    onHideSubtitles: () -> Unit,
    onRetry: () -> Unit,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onPortraitPanelOffsetFractionChange: (Float) -> Unit,
    onResetPortraitPanelPosition: () -> Unit,
    onLandscapeSplitChange: (Boolean) -> Unit,
    onOriginalColorChange: (String) -> Unit,
    onTranslatedColorChange: (String) -> Unit,
    onHighlightColorChange: (String) -> Unit,
    onWordHighlightChange: (Boolean) -> Unit,
    onCustomColorsChange: (Boolean) -> Unit,
    onCaptionFormatChange: (CaptionFormat) -> Unit,
    lockOverlayToVideo: Boolean = false,
    onLockOverlayToVideoChange: (Boolean) -> Unit = {},
    preloadModelsEnabled: Boolean = true,
    onPreloadModelsChange: (Boolean) -> Unit = {},
    naturalSubtitlesEnabled: Boolean = true,
    onNaturalSubtitlesChange: (Boolean) -> Unit = {},
    wordLearningEnabled: Boolean = true,
    onWordLearningChange: (Boolean) -> Unit = {},
    wordLearningTarget: String = "both",
    onWordLearningTargetChange: (String) -> Unit = {},
    wordLearningActiveOnly: Boolean = true,
    onWordLearningActiveOnlyChange: (Boolean) -> Unit = {},
    tapToLearnEnabled: Boolean = true,
    onTapToLearnChange: (Boolean) -> Unit = {},
    onWordClick: (WordTap) -> Unit = {},
    onResetSettings: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val layoutPreferences =
        remember(context) {
            context.getSharedPreferences("dual_sub_preferences", 0)
        }
    var landscapeVideoFraction by remember {
        mutableFloatStateOf(
            normalizeLandscapeVideoFraction(
                layoutPreferences.getFloat(
                    LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
                    DEFAULT_LANDSCAPE_VIDEO_FRACTION,
                ),
            ),
        )
    }
    var splitContainerWidthPx by remember { mutableFloatStateOf(0f) }
    val splitDragState =
        rememberDraggableState { delta ->
            if (splitContainerWidthPx <= 0f) return@rememberDraggableState
            landscapeVideoFraction =
                normalizeLandscapeVideoFraction(
                    landscapeVideoFraction + delta / splitContainerWidthPx,
                )
        }
    val nativeDialogVisible by youtubeNativeDialogVisible.collectAsStateWithLifecycle()
    val tracksVisible = state.showOriginal() || state.showTranslation()
    val sideBySide =
        tracksVisible && !nativeDialogVisible &&
            shouldUseLandscapeSplit(
                splitEnabled = state.landscapeSplitEnabled,
                subtitlePanelVisible = state.subtitlePanelVisible,
                hasActiveVideo = state.activeVideoId != null,
                orientation = configuration.orientation,
            )
    val contentInsets =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            WindowInsets(0, 0, 0, 0)
        } else {
            WindowInsets.safeDrawing
        }
    val liveCaptionCaptureEnabled = shouldCaptureCaptionsForPresentation(state, effectivePlayerMode)

    LaunchedEffect(externalSettingsRequestId) {
        if (externalSettingsRequestId > 0L) showSettings = true
    }

    AppNavigation(onPractice = onVocabulary, onSettings = {
        showSettings = true
    }, onVisibilityChange = onNavigationVisibilityChange) { menuButton ->
        Scaffold(contentWindowInsets = contentInsets, topBar = {
            Surface {
                Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement = Arrangement.Start) {
                    menuButton()
                }
            }
        }) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                // Single call site on purpose: branching around SingleYouTubePage would
                // leave composition and destroy the persistent WebView on every rotation.
                Row(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { splitContainerWidthPx = it.width.toFloat() },
                ) {
                    SingleYouTubePage(
                        initialUrl = state.browserUrl,
                        navigationRequestId = state.browserNavigationRequestId,
                        controller = webController,
                        onPageChanged = onPageChanged,
                        onPlaybackSecond = onPlaybackSecond,
                        onPlaybackPaused = onPlaybackPaused,
                        liveCaptionCaptureEnabled = liveCaptionCaptureEnabled,
                        suppressPageCaptions = shouldSuppressNativeCaptions(state, liveCaptionCaptureEnabled, effectivePlayerMode),
                        captionTrackTarget = captionTrackTarget(state),
                        fullscreenOverlay = fullscreenLearningOverlay,
                        modifier =
                            Modifier
                                .weight(if (sideBySide) landscapeVideoFraction else 1f)
                                .fillMaxHeight()
                                .testTag("youtube_web_app"),
                    )

                    if (sideBySide) {
                        LandscapeSplitDivider(
                            videoFraction = landscapeVideoFraction,
                            dragState = splitDragState,
                            onDragStopped = {
                                layoutPreferences
                                    .edit()
                                    .putFloat(
                                        LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
                                        landscapeVideoFraction,
                                    ).apply()
                            },
                        )
                        SideSubtitlePanel(
                            state = state,
                            modifier =
                                Modifier
                                    .weight(1f - landscapeVideoFraction)
                                    .fillMaxHeight()
                                    .testTag("subtitle_timeline"),
                            onHide = onHideSubtitles,
                            onSettings = { showSettings = true },
                            onRetry = onRetry,
                            onWordClick = onWordClick,
                            onReplay = { segment ->
                                webController.replayFrom(segment.startMs / 1_000f)
                            },
                        )
                    }
                }

                if (tracksVisible && !nativeDialogVisible && !sideBySide && state.activeVideoId != null && state.subtitlePanelVisible) {
                    val panelContent: @Composable (Modifier) -> Unit = { panelModifier ->
                        SubtitlePanel(
                            state = state,
                            modifier = panelModifier.testTag("subtitle_timeline"),
                            onHide = onHideSubtitles,
                            onSettings = { showSettings = true },
                            onRetry = onRetry,
                            onWordClick = onWordClick,
                            onReplay = { segment ->
                                webController.replayFrom(segment.startMs / 1_000f)
                            },
                        )
                    }
                    if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                        PortraitSubtitlePanelLayout(
                            baselineHeightFraction =
                                portraitSubtitlePanelHeightFraction(
                                    screenWidthDp = configuration.screenWidthDp,
                                    screenHeightDp = configuration.screenHeightDp,
                                ),
                            offsetFraction = state.portraitPanelOffsetFraction,
                            content = panelContent,
                        )
                    } else {
                        panelContent(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(0.60f),
                        )
                    }
                } else if (
                    !nativeDialogVisible && !sideBySide &&
                    state.activeVideoId != null &&
                    effectivePlayerMode == PlayerExperienceMode.TRANSCRIPT_PANEL
                ) {
                    MovableSubtitleFab(onClick = { if (tracksVisible) onShowSubtitles() else showSettings = true })
                }
            }
        }
    }
    if (showSettings) {
        SubtitleSettingsDialog(
            autoPronounce = state.autoPronounce,
            onAutoPronounceChange = onAutoPronounceChange,
            sourcePreference = state.sourcePreference,
            targetLanguage = state.targetLanguage,
            availableSourceLanguages = state.availableSourceLanguages,
            fontScale = state.fontScale,
            portraitPanelOffsetFraction = state.portraitPanelOffsetFraction,
            onPortraitPanelOffsetFractionChange = onPortraitPanelOffsetFractionChange,
            onResetPortraitPanelPosition = onResetPortraitPanelPosition,
            landscapeSplitEnabled = state.landscapeSplitEnabled,
            playerMode = playerMode,
            originalColorKey = state.originalColorKey,
            translatedColorKey = state.translatedColorKey,
            highlightColorKey = state.highlightColorKey,
            wordHighlightEnabled = state.wordHighlightEnabled,
            customColorsEnabled = state.customColorsEnabled,
            captionFormat = state.captionFormat,
            lockOverlayToVideo = state.lockOverlayToVideo,
            onLockOverlayToVideoChange = onLockOverlayToVideoChange,
            preloadModelsEnabled = state.preloadModelsEnabled,
            onPreloadModelsChange = onPreloadModelsChange,
            naturalSubtitlesEnabled = state.naturalSubtitlesEnabled,
            onNaturalSubtitlesChange = onNaturalSubtitlesChange,
            wordLearningEnabled = state.wordLearningEnabled,
            onWordLearningChange = onWordLearningChange,
            wordLearningTarget = state.wordLearningTarget,
            onWordLearningTargetChange = onWordLearningTargetChange,
            wordLearningActiveOnly = state.wordLearningActiveOnly,
            onWordLearningActiveOnlyChange = onWordLearningActiveOnlyChange,
            tapToLearnEnabled = state.tapToLearnEnabled,
            onTapToLearnChange = onTapToLearnChange,
            onSourceChange = onSourceChange,
            onTargetChange = onTargetChange,
            onFontScaleChange = onFontScaleChange,
            onLandscapeSplitChange = onLandscapeSplitChange,
            onPlayerModeChange = onPlayerModeChange,
            onOriginalColorChange = onOriginalColorChange,
            onTranslatedColorChange = onTranslatedColorChange,
            onHighlightColorChange = onHighlightColorChange,
            onWordHighlightChange = onWordHighlightChange,
            onCustomColorsChange = onCustomColorsChange,
            onCaptionFormatChange = onCaptionFormatChange,
            onResetSettings = {
                showSettings = false
                onResetSettings()
            },
            onDismiss = { showSettings = false },
        )
    }
}

internal fun portraitSubtitlePanelHeightFraction(
    screenWidthDp: Int,
    screenHeightDp: Int,
): Float {
    val safeHeight = screenHeightDp.coerceAtLeast(1).toFloat()
    val estimatedVideoBottom = 56f + screenWidthDp.coerceAtLeast(0) * 9f / 16f
    val desiredPanelTop = estimatedVideoBottom + 6f
    return ((safeHeight - desiredPanelTop) / safeHeight).coerceIn(0.60f, 0.78f)
}

@Composable
private fun SubtitlePanel(
    state: DualSubUiState,
    modifier: Modifier,
    onHide: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onWordClick: (WordTap) -> Unit = {},
    onReplay: (SubtitleSegment) -> Unit,
) {
    var panelOffsetY by remember { mutableFloatStateOf(0f) }
    var panelHeightPx by remember { mutableFloatStateOf(0f) }
    val minimumDismissDistancePx = with(LocalDensity.current) { 72.dp.toPx() }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta ->
        val maximum = panelHeightPx.takeIf { it > 0f } ?: Float.MAX_VALUE
        panelOffsetY = (panelOffsetY + delta).coerceIn(0f, maximum)
    }
    val headerDragModifier = Modifier
        .testTag("subtitle_panel_drag_handle")
        .draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                val hide = shouldHideSubtitlePanel(
                    dragOffsetPx = panelOffsetY,
                    panelHeightPx = panelHeightPx,
                    velocityPxPerSecond = velocity,
                    minimumDistancePx = minimumDismissDistancePx,
                )
                scope.launch {
                    val target = if (hide) panelHeightPx.coerceAtLeast(panelOffsetY) else 0f
                    animate(
                        initialValue = panelOffsetY,
                        targetValue = target,
                        animationSpec = tween(durationMillis = if (hide) 160 else 220),
                    ) { value, _ -> panelOffsetY = value }
                    if (hide) onHide()
                }
            },
        )

    Surface(
        modifier = modifier
            .onSizeChanged { panelHeightPx = it.height.toFloat() }
            .offset { IntOffset(0, panelOffsetY.roundToInt()) }
            .shadow(18.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = Color(0xFF061719),
        contentColor = Color(0xFFF3FAFA),
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(headerDragModifier) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp),
                        shape = CircleShape,
                        color = Color(0xFF51686B),
                    ) {}
                }

                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ClosedCaption,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = sourceDescription(state),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xFFF3FAFA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.statusMessage ?: "Tap a paragraph to replay it",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB7CED1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Subtitle settings",
                            tint = Color(0xFFE5F2F3),
                        )
                    }
                    IconButton(onClick = onHide) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Hide dual subtitles",
                            tint = Color(0xFFE5F2F3),
                        )
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF244044))

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.liveFallback -> LiveSubtitlePanel(state, onRetry, onWordClick)
                    state.errorMessage != null -> CompactErrorPanel(state.errorMessage, onRetry)
                    state.segments.isEmpty() -> CompactLoadingPanel(state.statusMessage ?: "Loading captions…")
                    else -> SubtitleTimeline(state, onWordClick = onWordClick, onReplay = onReplay)
                }
            }
        }
    }
}

internal fun shouldHideSubtitlePanel(
    dragOffsetPx: Float,
    panelHeightPx: Float,
    velocityPxPerSecond: Float,
    minimumDistancePx: Float,
): Boolean {
    val distanceThreshold = max(minimumDistancePx, panelHeightPx * 0.18f)
    return dragOffsetPx >= distanceThreshold || velocityPxPerSecond >= 1_500f
}

/**
 * Landscape split keeps the persistent WebView beside the dual-subtitle panel.
 * The divider is user-resizable and the chosen video width is remembered.
 */
internal fun shouldUseLandscapeSplit(
    splitEnabled: Boolean,
    subtitlePanelVisible: Boolean,
    hasActiveVideo: Boolean,
    orientation: Int,
): Boolean = splitEnabled &&
    subtitlePanelVisible &&
    hasActiveVideo &&
    orientation == Configuration.ORIENTATION_LANDSCAPE

internal const val LANDSCAPE_VIDEO_FRACTION_PREFERENCE = "landscape_video_fraction"
internal const val DEFAULT_LANDSCAPE_VIDEO_FRACTION = 0.75f
internal const val MIN_LANDSCAPE_VIDEO_FRACTION = 0.65f
internal const val MAX_LANDSCAPE_VIDEO_FRACTION = 0.85f

internal fun normalizeLandscapeVideoFraction(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_LANDSCAPE_VIDEO_FRACTION, MAX_LANDSCAPE_VIDEO_FRACTION)
    } else {
        DEFAULT_LANDSCAPE_VIDEO_FRACTION
    }

@Composable
private fun LandscapeSplitDivider(
    videoFraction: Float,
    dragState: androidx.compose.foundation.gestures.DraggableState,
    onDragStopped: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .semantics {
                stateDescription = "Video ${(videoFraction * 100).roundToInt()} percent"
            }
            .testTag("landscape_split_divider")
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = { onDragStopped() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(3.dp).height(64.dp),
            shape = CircleShape,
            color = Color(0xFF607477),
        ) {}
    }
}

internal const val SIDE_PANEL_SWIPE_VELOCITY_THRESHOLD = 1_500f

internal fun shouldCollapseSidePanel(
    dragOffsetPx: Float,
    velocityPxPerSecond: Float,
    minimumDistancePx: Float,
): Boolean = dragOffsetPx >= minimumDistancePx ||
    velocityPxPerSecond >= SIDE_PANEL_SWIPE_VELOCITY_THRESHOLD

@Composable
private fun SideSubtitlePanel(
    state: DualSubUiState,
    modifier: Modifier,
    onHide: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onWordClick: (WordTap) -> Unit = {},
    onReplay: (SubtitleSegment) -> Unit,
) {
    var panelOffsetX by remember { mutableFloatStateOf(0f) }
    var panelWidthPx by remember { mutableFloatStateOf(0f) }
    val minimumDismissDistancePx = with(LocalDensity.current) { 72.dp.toPx() }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta ->
        val maximum = panelWidthPx.takeIf { it > 0f } ?: Float.MAX_VALUE
        panelOffsetX = (panelOffsetX + delta).coerceIn(0f, maximum)
    }
    val headerDragModifier = Modifier
        .draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            onDragStopped = { velocity ->
                val hide = shouldCollapseSidePanel(
                    dragOffsetPx = panelOffsetX,
                    velocityPxPerSecond = velocity,
                    minimumDistancePx = minimumDismissDistancePx,
                )
                scope.launch {
                    val target = if (hide) panelWidthPx.coerceAtLeast(panelOffsetX) else 0f
                    animate(
                        initialValue = panelOffsetX,
                        targetValue = target,
                        animationSpec = tween(durationMillis = if (hide) 160 else 220),
                    ) { value, _ -> panelOffsetX = value }
                    if (hide) onHide()
                }
            },
        )

    Surface(
        modifier = modifier
            .onSizeChanged { panelWidthPx = it.width.toFloat() }
            .offset { IntOffset(panelOffsetX.roundToInt(), 0) },
        shape = RectangleShape,
        color = Color(0xFF061719),
        contentColor = Color(0xFFF3FAFA),
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(headerDragModifier) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp).padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ClosedCaption,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = sourceDescription(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFF3FAFA),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.statusMessage ?: "Tap a paragraph to replay it",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB7CED1),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Subtitle settings",
                            tint = Color(0xFFE5F2F3),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onHide, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Hide dual subtitles",
                            tint = Color(0xFFE5F2F3),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF244044))

            when {
                state.liveFallback -> LiveSubtitlePanel(state, onRetry, onWordClick)
                state.errorMessage != null -> CompactErrorPanel(state.errorMessage, onRetry)
                state.segments.isEmpty() -> CompactLoadingPanel(state.statusMessage ?: "Loading captions…")
                else -> SubtitleTimeline(state, onWordClick = onWordClick, onReplay = onReplay)
            }
        }
    }
}

@Composable
private fun SubtitleTimeline(
    state: DualSubUiState,
    onWordClick: (WordTap) -> Unit = {},
    onReplay: (SubtitleSegment) -> Unit,
) {
    // When the panel is recreated after being closed, start the lazy list at the
    // current transcript row instead of item 0. This prevents long videos from
    // visibly walking through hundreds of rows before catching up.
    val initialItemIndex = state.currentIndex
        .coerceAtLeast(0)
        .coerceAtMost(state.segments.lastIndex.coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialItemIndex)
    val previousIndex = remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.currentIndex, state.segments.firstOrNull()?.id) {
        val target = state.currentIndex
        if (target < 0) return@LaunchedEffect
        // IDs remain global when the local window shifts or jumps hours ahead.
        val globalIndex = state.segments.getOrNull(target)?.id?.toInt() ?: return@LaunchedEffect
        val lastIndex = previousIndex.intValue
        previousIndex.intValue = globalIndex
        if (listState.isScrollInProgress) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
        }
        if (listState.isScrollInProgress) return@LaunchedEffect
        val visibleItemIndices = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.map { it.index }
        }.first { it.isNotEmpty() }

        when {
            // First placement after opening/reopening should always be an instant
            // jump. Animating from row 0 is expensive and looks like a full-list
            // scroll on long videos.
            lastIndex < 0 -> {
                listState.scrollToItem(target)
            }
            shouldFollowPlaybackSeek(lastIndex, globalIndex) -> {
                if (abs(globalIndex - lastIndex) > SUBTITLE_INSTANT_SCROLL_DISTANCE) {
                    listState.scrollToItem(target)
                } else {
                    listState.animateScrollToItem(target)
                }
            }
            shouldPromoteActiveSubtitle(target, visibleItemIndices) -> {
                listState.animateScrollToItem(target)
            }
        }
    }

    LaunchedEffect(listState, state.currentIndex) {
        var previousViewportHeight = 0
        var activeWasVisible = false
        snapshotFlow {
            listState.layoutInfo.viewportSize.height to
                listState.layoutInfo.visibleItemsInfo.map { it.index }
        }.collect { (viewportHeight, visibleItemIndices) ->
            val target = state.currentIndex
            val viewportShrank = previousViewportHeight > 0 && viewportHeight < previousViewportHeight
            val shouldRestoreActiveRow =
                viewportShrank && activeWasVisible && target >= 0 &&
                    target !in visibleItemIndices && !listState.isScrollInProgress
            previousViewportHeight = viewportHeight
            activeWasVisible = target in visibleItemIndices
            if (shouldRestoreActiveRow) {
                listState.scrollToItem(target)
                activeWasVisible = true
            }
        }
    }

    val showOriginal = state.showOriginal()
    val showTranslation = state.showTranslation()
    val activeWordIndex = if (state.wordHighlightEnabled) state.activeWordIndex else -1
    val originalColor = effectiveOriginalColor(state)
    val translatedColor = effectiveTranslatedColor(state)
    val highlightColor = effectiveHighlightColor(state)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        itemsIndexed(state.segments, key = { _, segment -> segment.id }) { index, segment ->
            val active = index == state.currentIndex
            CompactSubtitleCard(
                segment = segment,
                showOriginal = showOriginal,
                showTranslation = showTranslation,
                active = active,
                fontScale = state.fontScale,
                onReplay = { onReplay(segment) },
                // Only the active row draws the spoken word. Giving the others a constant lets them
                // skip recomposition each time the highlighted word moves.
                activeWordIndex = if (active) activeWordIndex else -1,
                originalColor = originalColor,
                translatedColor = translatedColor,
                highlightColor = highlightColor,
                wordLearningEnabled = state.wordLearningEnabled,
                wordLearningTarget = state.wordLearningTarget,
                wordLearningActiveOnly = state.wordLearningActiveOnly,
                tapToLearnEnabled = state.tapToLearnEnabled,
                resolvedSourceLanguage = state.resolvedSourceLanguage ?: state.sourcePreference,
                targetLanguage = state.targetLanguage,
                isDownloadingTranslationModel = state.isDownloadingTranslationModel,
                onWordClick = onWordClick,
            )
        }
    }
}

/**
 * Keeps earlier paragraphs on screen until the active paragraph reaches the
 * bottom-most visible slot. Seeking past the viewport also promotes the active
 * paragraph so playback can recover without leaving it off-screen.
 */
internal fun shouldPromoteActiveSubtitle(
    currentIndex: Int,
    visibleItemIndices: List<Int>,
): Boolean {
    if (currentIndex < 0 || visibleItemIndices.isEmpty()) return false
    return currentIndex >= visibleItemIndices.last()
}

internal const val SUBTITLE_INSTANT_SCROLL_DISTANCE = 40

/**
 * Detects a playback jump (seek bar drag, rewind, replay tap) so the timeline
 * follows the active paragraph immediately instead of waiting for it to reach
 * the bottom of the panel.
 */
internal fun shouldFollowPlaybackSeek(previousIndex: Int, currentIndex: Int): Boolean {
    if (previousIndex < 0 || currentIndex < 0) return false
    return currentIndex < previousIndex || currentIndex - previousIndex > 1
}

@Composable
private fun CompactLoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFFB7CED1))
        }
    }
}

@Composable
private fun CompactErrorPanel(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text("Retry captions")
            }
        }
    }
}

internal data class LanguageChoice(val code: String, val label: String)

@Composable
internal fun LanguagePickerDialog(
    title: String,
    choices: List<LanguageChoice>,
    selectedCode: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onChoice: (LanguageChoice) -> Unit,
    onDismiss: () -> Unit,
    testTagPrefix: String,
) {
    val filteredChoices = choices.filter { choice ->
        searchQuery.isBlank() ||
            choice.label.contains(searchQuery.trim(), ignoreCase = true) ||
            choice.code.contains(searchQuery.trim(), ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth().testTag("language_search"),
                    label = { Text("Search languages") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                val listMaxHeight = minOf(360.dp, LocalConfiguration.current.screenHeightDp.dp * 0.45f)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight)) {
                    itemsIndexed(filteredChoices, key = { _, choice -> choice.code }) { _, choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChoice(choice) }
                                .testTag("language_option_${testTagPrefix}_${choice.code}")
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(choice.label, modifier = Modifier.weight(1f))
                            if (
                                TranslationLanguages.normalize(choice.code) ==
                                TranslationLanguages.normalize(selectedCode)
                            ) {
                                Text("Selected", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

private fun sourceDescription(state: DualSubUiState): String {
    val source = state.resolvedSourceLanguage?.let { resolved ->
        state.availableSourceLanguages.firstOrNull {
            TranslationLanguages.normalize(it.code) == TranslationLanguages.normalize(resolved)
        }?.name ?: TranslationLanguages.displayName(resolved)
    }?.let { captionTrackLabel(it, state.generatedCaptions) } ?: "Finding captions"
    return "$source  →  ${TranslationLanguages.displayName(state.targetLanguage)}"
}

/** YouTube already names generated tracks "English (auto-generated)", so only add the marker when it is missing. */
internal fun captionTrackLabel(
    name: String,
    generated: Boolean,
): String = if (generated && !name.contains("auto-generated", ignoreCase = true)) "$name (auto-generated)" else name
