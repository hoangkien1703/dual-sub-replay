package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.CaptionLanguage
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
    onPlayerModeChange: (PlayerExperienceMode) -> Unit = {},
    externalSettingsRequestId: Long = 0L,
    fullscreenLearningOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DualSubTheme {
        if (!state.onboardingCompleted) {
            LanguageSetupScreen(
                onComplete = viewModel::completeOnboarding,
                onSkip = viewModel::skipOnboarding,
            )
        } else {
            DualSubExperience(
                state = state,
                playerMode = playerMode,
                onPlayerModeChange = onPlayerModeChange,
                externalSettingsRequestId = externalSettingsRequestId,
                fullscreenLearningOverlay = fullscreenLearningOverlay,
                onPageChanged = viewModel::onYouTubePageChanged,
                onPlaybackSecond = viewModel::onWebPlaybackSecond,
                onShowSubtitles = viewModel::showSubtitlePanel,
                onHideSubtitles = viewModel::hideSubtitlePanel,
                onRetry = viewModel::retryCaptions,
                onSourceChange = viewModel::setSourcePreference,
                onTargetChange = viewModel::setTargetLanguage,
                onFontScaleChange = viewModel::setFontScale,
                onLandscapeSplitChange = viewModel::setLandscapeSplitEnabled,
            )
        }
    }
}

@Composable
private fun DualSubExperience(
    state: DualSubUiState,
    playerMode: PlayerExperienceMode,
    onPlayerModeChange: (PlayerExperienceMode) -> Unit,
    externalSettingsRequestId: Long,
    fullscreenLearningOverlay: (@Composable BoxScope.() -> Unit)?,
    onPageChanged: (String) -> Unit,
    onPlaybackSecond: (String, Float) -> Unit,
    onShowSubtitles: () -> Unit,
    onHideSubtitles: () -> Unit,
    onRetry: () -> Unit,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLandscapeSplitChange: (Boolean) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val webController = rememberYouTubeWebController()
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val layoutPreferences = remember(context) {
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
    val splitDragState = rememberDraggableState { delta ->
        if (splitContainerWidthPx <= 0f) return@rememberDraggableState
        landscapeVideoFraction = normalizeLandscapeVideoFraction(
            landscapeVideoFraction + delta / splitContainerWidthPx,
        )
    }
    val sideBySide = shouldUseLandscapeSplit(
        splitEnabled = state.landscapeSplitEnabled,
        subtitlePanelVisible = state.subtitlePanelVisible,
        hasActiveVideo = state.activeVideoId != null,
        orientation = configuration.orientation,
    )
    val contentInsets = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        WindowInsets(0, 0, 0, 0)
    } else {
        WindowInsets.safeDrawing
    }

    LaunchedEffect(externalSettingsRequestId) {
        if (externalSettingsRequestId > 0L) showSettings = true
    }

    Scaffold(contentWindowInsets = contentInsets) { innerPadding ->
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
                    suppressPageCaptions = playerMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
                    fullscreenOverlay = fullscreenLearningOverlay,
                    modifier = Modifier
                        .weight(if (sideBySide) landscapeVideoFraction else 1f)
                        .fillMaxHeight()
                        .testTag("youtube_web_app"),
                )

                if (sideBySide) {
                    LandscapeSplitDivider(
                        videoFraction = landscapeVideoFraction,
                        dragState = splitDragState,
                        onDragStopped = {
                            layoutPreferences.edit()
                                .putFloat(
                                    LANDSCAPE_VIDEO_FRACTION_PREFERENCE,
                                    landscapeVideoFraction,
                                )
                                .apply()
                        },
                    )
                    SideSubtitlePanel(
                        state = state,
                        modifier = Modifier
                            .weight(1f - landscapeVideoFraction)
                            .fillMaxHeight()
                            .testTag("subtitle_timeline"),
                        onHide = onHideSubtitles,
                        onSettings = { showSettings = true },
                        onRetry = onRetry,
                        onReplay = { segment ->
                            webController.replayFrom(segment.startMs / 1_000f)
                        },
                    )
                }
            }

            if (!sideBySide && state.activeVideoId != null && state.subtitlePanelVisible) {
                SubtitlePanel(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.60f)
                        .testTag("subtitle_timeline"),
                    onHide = onHideSubtitles,
                    onSettings = { showSettings = true },
                    onRetry = onRetry,
                    onReplay = { segment ->
                        webController.replayFrom(segment.startMs / 1_000f)
                    },
                )
            } else if (
                !sideBySide &&
                state.activeVideoId != null &&
                playerMode == PlayerExperienceMode.TRANSCRIPT_PANEL
            ) {
                SmallFloatingActionButton(
                    onClick = onShowSubtitles,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .testTag("show_subtitles"),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.ClosedCaption, contentDescription = "Show dual subtitles")
                }
            }
        }
    }

    if (showSettings) {
        SubtitleSettingsDialog(
            sourcePreference = state.sourcePreference,
            targetLanguage = state.targetLanguage,
            availableSourceLanguages = state.availableSourceLanguages,
            fontScale = state.fontScale,
            landscapeSplitEnabled = state.landscapeSplitEnabled,
            playerMode = playerMode,
            onSourceChange = onSourceChange,
            onTargetChange = onTargetChange,
            onFontScaleChange = onFontScaleChange,
            onLandscapeSplitChange = onLandscapeSplitChange,
            onPlayerModeChange = onPlayerModeChange,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun SubtitlePanel(
    state: DualSubUiState,
    modifier: Modifier,
    onHide: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
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
                    Modifier
                        .padding(top = 6.dp)
                        .size(width = 42.dp, height = 4.dp)
                        .align(Alignment.CenterHorizontally),
                ) {
                    Surface(Modifier.fillMaxSize(), shape = CircleShape, color = Color(0xFF607477)) {}
                }

                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.ClosedCaption,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
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

            when {
                state.errorMessage != null -> CompactErrorPanel(state.errorMessage, onRetry)
                state.segments.isEmpty() -> CompactLoadingPanel(state.statusMessage ?: "Loading captions…")
                else -> SubtitleTimeline(state, onReplay)
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
                state.errorMessage != null -> CompactErrorPanel(state.errorMessage, onRetry)
                state.segments.isEmpty() -> CompactLoadingPanel(state.statusMessage ?: "Loading captions…")
                else -> SubtitleTimeline(state, onReplay)
            }
        }
    }
}

@Composable
private fun SubtitleTimeline(state: DualSubUiState, onReplay: (SubtitleSegment) -> Unit) {
    val listState = rememberLazyListState()
    val previousIndex = remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.currentIndex) {
        val target = state.currentIndex
        if (target < 0) return@LaunchedEffect
        val lastIndex = previousIndex.intValue
        previousIndex.intValue = target
        if (listState.isScrollInProgress) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
        }
        if (listState.isScrollInProgress) return@LaunchedEffect
        val visibleItemIndices = snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.map { it.index }
        }.first { it.isNotEmpty() }

        when {
            shouldFollowPlaybackSeek(lastIndex, target) -> {
                if (abs(target - lastIndex) > SUBTITLE_INSTANT_SCROLL_DISTANCE) {
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        itemsIndexed(state.segments, key = { _, segment -> segment.id }) { index, segment ->
            CompactSubtitleCard(
                segment = segment,
                active = index == state.currentIndex,
                fontScale = state.fontScale,
                onReplay = { onReplay(segment) },
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
internal fun CompactSubtitleCard(
    segment: SubtitleSegment,
    active: Boolean,
    fontScale: Float,
    onReplay: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (active) "Active subtitle" else "Subtitle" }
            .clickable(onClick = onReplay),
        border = BorderStroke(
            width = if (active) 2.dp else 1.dp,
            color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF183034),
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (active) Color(0xFF0A2B30) else Color(0xFF081D20),
            contentColor = Color(0xFFF3FAFA),
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary else Color(0xFF24383B),
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Replay this paragraph",
                        modifier = Modifier.size(22.dp),
                        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.size(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = segment.originalText,
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (22 * fontScale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = Color(0xFFF3FAFA),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = segment.translatedText ?: "Translating…",
                    fontSize = (14 * fontScale).sp,
                    lineHeight = (18 * fontScale).sp,
                    color = Color(0xFF9EDCE4),
                )
            }
        }
    }
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

@Composable
internal fun SubtitleSettingsDialog(
    sourcePreference: String,
    targetLanguage: String,
    availableSourceLanguages: List<CaptionLanguage>,
    fontScale: Float,
    landscapeSplitEnabled: Boolean,
    playerMode: PlayerExperienceMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLandscapeSplitChange: (Boolean) -> Unit,
    onPlayerModeChange: (PlayerExperienceMode) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var pickerMode by remember { mutableStateOf<LanguagePickerMode?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("dual_sub_preferences", 0)
    }
    var autoOverlayFullscreen by remember {
        mutableStateOf(preferences.getBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, true))
    }
    var autoOverlayLandscape by remember {
        mutableStateOf(preferences.getBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, true))
    }
    var autoAvoidPlayerControls by remember {
        mutableStateOf(preferences.getBoolean(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE, true))
    }
    var rememberOverlayPosition by remember {
        mutableStateOf(preferences.getBoolean(REMEMBER_OVERLAY_POSITION_PREFERENCE, true))
    }
    val sourceChoices = listOf(LanguageChoice("auto", "Auto (recommended)")) +
        availableSourceLanguages.map { LanguageChoice(it.code, it.name) }
    val targetChoices = TranslationLanguages.all.map { LanguageChoice(it.code, it.name) }

    fun setBooleanPreference(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    val activePicker = pickerMode
    if (activePicker != null) {
        val choices = if (activePicker == LanguagePickerMode.SOURCE) sourceChoices else targetChoices
        LanguagePickerDialog(
  title = if (activePicker == LanguagePickerMode.SOURCE) {
      "Original caption language"
  } else {
      "Translate to"
  },
  choices = choices,
  selectedCode = if (activePicker == LanguagePickerMode.SOURCE) sourcePreference else targetLanguage,
  searchQuery = searchQuery,
  onSearchQueryChange = { searchQuery = it },
  onChoice = { choice ->
      if (activePicker == LanguagePickerMode.SOURCE) {
          onSourceChange(choice.code)
      } else {
          onTargetChange(choice.code)
      }
      pickerMode = null
      searchQuery = ""
  },
  onDismiss = {
      pickerMode = null
      searchQuery = ""
  },
  testTagPrefix = activePicker.name.lowercase(),
        )
    } else {
        val sourceLabel = if (sourcePreference == "auto") {
  "Auto (recommended)"
        } else {
  sourceChoices.firstOrNull {
      TranslationLanguages.normalize(it.code) == TranslationLanguages.normalize(sourcePreference)
  }?.label ?: TranslationLanguages.displayName(sourcePreference)
        }
        AlertDialog(
  onDismissRequest = onDismiss,
  title = { Text("Dual-subtitle settings") },
  text = {
      val bodyMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.68f
      Column(
          modifier = Modifier
              .heightIn(max = bodyMaxHeight)
              .verticalScroll(rememberScrollState()),
      ) {
          Text("Captions", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(8.dp))
          Text("Original language")
          Spacer(Modifier.height(6.dp))
          OutlinedButton(
              onClick = {
                  pickerMode = LanguagePickerMode.SOURCE
                  searchQuery = ""
              },
              modifier = Modifier.fillMaxWidth().testTag("source_language_picker"),
          ) {
              Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          Spacer(Modifier.height(12.dp))
          Text("Translate to")
          Spacer(Modifier.height(6.dp))
          OutlinedButton(
              onClick = {
                  pickerMode = LanguagePickerMode.TARGET
                  searchQuery = ""
              },
              modifier = Modifier.fillMaxWidth().testTag("target_language_picker"),
          ) {
              Text(TranslationLanguages.displayName(targetLanguage))
          }
          Text(
              "A language model downloads only when it is needed.",
              modifier = Modifier.padding(top = 6.dp),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(12.dp))
          Text("Text size: ${(fontScale * 100).toInt()}%")
          Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = 0.8f..1.5f)

          Spacer(Modifier.height(14.dp))
          HorizontalDivider()
          Spacer(Modifier.height(14.dp))
          Text("Default view", style = MaterialTheme.typography.titleSmall)
          PlayerModeSettingsOption(
              mode = PlayerExperienceMode.TRANSCRIPT_PANEL,
              selectedMode = playerMode,
              title = "Transcript panel",
              description = "Full dual-subtitle timeline with paragraph replay.",
              onModeChange = onPlayerModeChange,
          )
          HorizontalDivider()
          PlayerModeSettingsOption(
              mode = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
              selectedMode = playerMode,
              title = "Scroll-friendly overlay",
              description = "Compact bilingual captions while YouTube stays scrollable for comments and recommendations.",
              onModeChange = onPlayerModeChange,
          )

          Spacer(Modifier.height(14.dp))
          HorizontalDivider()
          Spacer(Modifier.height(14.dp))
          Text("Fullscreen & landscape", style = MaterialTheme.typography.titleSmall)
          SettingsSwitchRow(
              title = "Use overlay in fullscreen",
              description = "Show the compact dual-subtitle overlay automatically when YouTube enters fullscreen.",
              checked = autoOverlayFullscreen,
              onCheckedChange = {
                  autoOverlayFullscreen = it
                  setBooleanPreference(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, it)
              },
              testTag = "auto_overlay_fullscreen_switch",
          )
          SettingsSwitchRow(
              title = "Use overlay when rotated sideways",
              description = "Temporarily replace the transcript panel with the compact overlay in landscape.",
              checked = autoOverlayLandscape,
              onCheckedChange = {
                  autoOverlayLandscape = it
                  setBooleanPreference(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, it)
              },
              testTag = "auto_overlay_landscape_switch",
          )

          Spacer(Modifier.height(14.dp))
          HorizontalDivider()
          Spacer(Modifier.height(14.dp))
          Text("Overlay", style = MaterialTheme.typography.titleSmall)
          SettingsSwitchRow(
              title = "Automatically avoid video controls",
              description = "Move subtitles upward while YouTube's seek bar and playback controls are visible.",
              checked = autoAvoidPlayerControls,
              onCheckedChange = {
                  autoAvoidPlayerControls = it
                  setBooleanPreference(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE, it)
              },
              testTag = "auto_avoid_player_controls_switch",
          )
          SettingsSwitchRow(
              title = "Remember dragged position",
              description = "Save where you drag the subtitle overlay and reuse that position later.",
              checked = rememberOverlayPosition,
              onCheckedChange = { enabled ->
                  rememberOverlayPosition = enabled
                  val editor = preferences.edit()
                      .putBoolean(REMEMBER_OVERLAY_POSITION_PREFERENCE, enabled)
                  if (!enabled) editor.remove(OVERLAY_VERTICAL_POSITION_PREFERENCE)
                  editor.apply()
              },
              testTag = "remember_overlay_position_switch",
          )
          Text(
              "Drag the subtitle box up or down directly on the video to reposition it.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(8.dp))
          OutlinedButton(
              onClick = {
                  preferences.edit()
                      .putFloat(
                          OVERLAY_VERTICAL_POSITION_PREFERENCE,
                          DEFAULT_OVERLAY_VERTICAL_POSITION,
                      )
                      .apply()
              },
              modifier = Modifier.fillMaxWidth().testTag("reset_overlay_position"),
          ) {
              Text("Reset overlay position")
          }

          Spacer(Modifier.height(14.dp))
          HorizontalDivider()
          Spacer(Modifier.height(14.dp))
          Text("Transcript mode", style = MaterialTheme.typography.titleSmall)
          SettingsSwitchRow(
              title = "Landscape split view",
              description = "When automatic landscape overlay is off, place the transcript beside the video and drag the divider to resize it.",
              checked = landscapeSplitEnabled,
              onCheckedChange = onLandscapeSplitChange,
              testTag = "landscape_split_switch",
          )
          Text(
              "Swipe the transcript header down (or right in split view) to hide it. Captions keep tracking while hidden.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
      }
  },
  confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
  Text(title)
  Text(
      description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
        }
        Switch(
  checked = checked,
  onCheckedChange = onCheckedChange,
  modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun PlayerModeSettingsOption(
    mode: PlayerExperienceMode,
    selectedMode: PlayerExperienceMode,
    title: String,
    description: String,
    onModeChange: (PlayerExperienceMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onModeChange(mode) }
            .padding(vertical = 8.dp)
            .testTag("player_mode_${mode.storageValue}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selectedMode == mode,
            onClick = { onModeChange(mode) },
        )
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class LanguagePickerMode { SOURCE, TARGET }

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
    } ?: "Finding captions"
    val generated = if (state.generatedCaptions) " (auto-generated)" else ""
    return "$source$generated  →  ${TranslationLanguages.displayName(state.targetLanguage)}"
}
