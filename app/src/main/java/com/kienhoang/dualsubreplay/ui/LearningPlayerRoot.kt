package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val PLAYER_EXPERIENCE_MODE_PREFERENCE = "player_experience_mode"
internal const val AUTO_OVERLAY_FULLSCREEN_PREFERENCE = "auto_overlay_fullscreen"
internal const val AUTO_OVERLAY_LANDSCAPE_PREFERENCE = "auto_overlay_landscape"
internal const val OVERLAY_VERTICAL_POSITION_PREFERENCE = "overlay_vertical_position"
internal const val DEFAULT_OVERLAY_VERTICAL_POSITION = 0.86f

enum class PlayerExperienceMode(val storageValue: String) {
    TRANSCRIPT_PANEL("transcript_panel"),
    SCROLL_FRIENDLY_OVERLAY("scroll_friendly_overlay"),
}

internal fun storedPlayerExperienceMode(raw: String?): PlayerExperienceMode =
    PlayerExperienceMode.entries.firstOrNull { it.storageValue == raw }
        ?: PlayerExperienceMode.TRANSCRIPT_PANEL

internal fun normalizeOverlayVerticalPosition(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT_OVERLAY_VERTICAL_POSITION

/**
 * Slider value 0 = higher and 1 = lower. The default deliberately sits much closer to the
 * bottom like LingoTube while leaving room for YouTube's seek bar and playback controls.
 */
internal fun overlayBottomPaddingDp(position: Float): Int {
    val normalized = normalizeOverlayVerticalPosition(position)
    return (180f - normalized * 160f).roundToInt()
}

internal fun shouldUseAutomaticLandscapeOverlay(
    mode: PlayerExperienceMode,
    autoLandscape: Boolean,
    orientation: Int,
): Boolean = mode == PlayerExperienceMode.TRANSCRIPT_PANEL &&
    autoLandscape &&
    orientation == Configuration.ORIENTATION_LANDSCAPE

internal data class LearningOverlayContent(
    val originalText: String?,
    val translatedText: String?,
    val statusText: String?,
)

internal fun learningOverlayContent(state: DualSubUiState): LearningOverlayContent? {
    if (state.activeVideoId == null) return null
    val active = state.segments.getOrNull(state.currentIndex)
    if (active != null) {
        return LearningOverlayContent(
            originalText = active.originalText,
            translatedText = active.translatedText ?: "Translating…",
            statusText = null,
        )
    }
    val status = state.errorMessage ?: state.statusMessage
        ?: if (state.segments.isNotEmpty()) "Waiting for the next caption…" else null
    return status?.let {
        LearningOverlayContent(originalText = null, translatedText = null, statusText = it)
    }
}

/**
 * Places the portrait overlay near the lower edge of the typical 16:9 mobile YouTube player.
 * The position slider still lets the user move it upward when a video needs more clearance.
 */
internal fun portraitLearningOverlayTopPaddingDp(
    screenWidthDp: Int,
    position: Float = DEFAULT_OVERLAY_VERTICAL_POSITION,
): Int {
    val width = screenWidthDp.coerceAtLeast(0).toFloat()
    val estimatedVideoBottom = 56f + width * 9f / 16f
    val minimumTop = 84f
    val maximumTop = (estimatedVideoBottom - 80f).coerceAtLeast(minimumTop)
    val normalized = normalizeOverlayVerticalPosition(position)
    return (minimumTop + (maximumTop - minimumTop) * normalized)
        .roundToInt()
        .coerceIn(84, 320)
}

/**
 * Keeps the existing single persistent YouTube WebView in composition while offering a second,
 * scroll-friendly presentation mode. No second player or WebView is created.
 */
@Composable
fun LearningPlayerRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val preferences = remember(context) {
        context.getSharedPreferences("dual_sub_preferences", 0)
    }
    var mode by remember {
        mutableStateOf(
            storedPlayerExperienceMode(
                preferences.getString(
                    PLAYER_EXPERIENCE_MODE_PREFERENCE,
                    PlayerExperienceMode.TRANSCRIPT_PANEL.storageValue,
                ),
            ),
        )
    }
    var autoOverlayFullscreen by remember {
        mutableStateOf(preferences.getBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, true))
    }
    var autoOverlayLandscape by remember {
        mutableStateOf(preferences.getBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, true))
    }
    var overlayVerticalPosition by remember {
        mutableStateOf(
            normalizeOverlayVerticalPosition(
                preferences.getFloat(
                    OVERLAY_VERTICAL_POSITION_PREFERENCE,
                    DEFAULT_OVERLAY_VERTICAL_POSITION,
                ),
            ),
        )
    }
    var restoreTranscriptAfterLandscapeOverlay by remember { mutableStateOf(false) }
    var showOverlayBehaviorSettings by remember { mutableStateOf(false) }
    var settingsRequestId by remember { mutableLongStateOf(0L) }

    fun selectMode(newMode: PlayerExperienceMode) {
        mode = newMode
        preferences.edit()
            .putString(PLAYER_EXPERIENCE_MODE_PREFERENCE, newMode.storageValue)
            .apply()
        if (newMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
            viewModel.hideSubtitlePanel()
        } else {
            viewModel.showSubtitlePanel()
        }
    }

    fun requestSubtitleSettings() {
        settingsRequestId += 1L
    }

    fun setAutoOverlayFullscreen(enabled: Boolean) {
        autoOverlayFullscreen = enabled
        preferences.edit().putBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, enabled).apply()
    }

    fun setAutoOverlayLandscape(enabled: Boolean) {
        autoOverlayLandscape = enabled
        preferences.edit().putBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, enabled).apply()
    }

    fun setOverlayVerticalPosition(position: Float) {
        val normalized = normalizeOverlayVerticalPosition(position)
        overlayVerticalPosition = normalized
        preferences.edit().putFloat(OVERLAY_VERTICAL_POSITION_PREFERENCE, normalized).apply()
    }

    val automaticLandscapeOverlay = shouldUseAutomaticLandscapeOverlay(
        mode = mode,
        autoLandscape = autoOverlayLandscape,
        orientation = configuration.orientation,
    )
    val effectiveMode = if (
        mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY || automaticLandscapeOverlay
    ) {
        PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY
    } else {
        PlayerExperienceMode.TRANSCRIPT_PANEL
    }

    // Landscape auto-overlay is temporary. If the transcript was open before rotation, restore it
    // when the phone returns to portrait instead of changing the user's persistent player mode.
    LaunchedEffect(automaticLandscapeOverlay, mode) {
        if (automaticLandscapeOverlay) {
            restoreTranscriptAfterLandscapeOverlay = state.subtitlePanelVisible
            if (state.subtitlePanelVisible) viewModel.hideSubtitlePanel()
        } else if (restoreTranscriptAfterLandscapeOverlay) {
            if (mode == PlayerExperienceMode.TRANSCRIPT_PANEL) viewModel.showSubtitlePanel()
            restoreTranscriptAfterLandscapeOverlay = false
        }
    }

    // Opening another video normally re-opens the transcript panel. Whenever overlay presentation
    // is active we immediately collapse it again so YouTube stays scrollable while captions reload.
    LaunchedEffect(effectiveMode, state.activeVideoId, state.subtitlePanelVisible) {
        if (
            effectiveMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY &&
            state.activeVideoId != null &&
            state.subtitlePanelVisible
        ) {
            viewModel.hideSubtitlePanel()
        }
    }

    val overlayContent = learningOverlayContent(state)
    val bottomPadding = overlayBottomPaddingDp(overlayVerticalPosition).dp

    // SingleYouTubePage only invokes this slot while its fullscreen custom view is open. Keeping
    // the slot non-null guarantees that the fullscreen dialog hides system bars even before
    // captions finish loading or when automatic fullscreen overlay is disabled.
    val fullscreenLearningOverlay: @Composable BoxScope.() -> Unit = {
        HideFullscreenSystemBars()
        if (
            overlayContent != null &&
            (effectiveMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY || autoOverlayFullscreen)
        ) {
            LearningSubtitleOverlay(
                content = overlayContent,
                fontScale = state.fontScale,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding),
                onSettings = { showOverlayBehaviorSettings = true },
                onClose = {
                    when {
                        mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY -> {
                            selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL)
                        }
                        automaticLandscapeOverlay -> {
                            setAutoOverlayLandscape(false)
                            setAutoOverlayFullscreen(false)
                        }
                        else -> setAutoOverlayFullscreen(false)
                    }
                },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        DualSubApp(
            viewModel = viewModel,
            playerMode = effectiveMode,
            onPlayerModeChange = ::selectMode,
            externalSettingsRequestId = settingsRequestId,
            fullscreenLearningOverlay = fullscreenLearningOverlay,
        )

        if (
            state.onboardingCompleted &&
            state.activeVideoId != null &&
            effectiveMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY
        ) {
            overlayContent?.let { content ->
                val overlayModifier = if (
                    configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                ) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 18.dp, end = 18.dp, bottom = bottomPadding)
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            start = 18.dp,
                            end = 18.dp,
                            top = portraitLearningOverlayTopPaddingDp(
                                configuration.screenWidthDp,
                                overlayVerticalPosition,
                            ).dp,
                        )
                }
                LearningSubtitleOverlay(
                    content = content,
                    fontScale = state.fontScale,
                    modifier = overlayModifier,
                    onSettings = { showOverlayBehaviorSettings = true },
                    onClose = {
                        if (mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
                            selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL)
                        } else if (automaticLandscapeOverlay) {
                            setAutoOverlayLandscape(false)
                        }
                    },
                )
            }
        }
    }

    if (showOverlayBehaviorSettings) {
        OverlayBehaviorSettingsDialog(
            autoOverlayFullscreen = autoOverlayFullscreen,
            autoOverlayLandscape = autoOverlayLandscape,
            overlayVerticalPosition = overlayVerticalPosition,
            onAutoOverlayFullscreenChange = ::setAutoOverlayFullscreen,
            onAutoOverlayLandscapeChange = ::setAutoOverlayLandscape,
            onOverlayVerticalPositionChange = ::setOverlayVerticalPosition,
            onOpenSubtitleSettings = {
                showOverlayBehaviorSettings = false
                requestSubtitleSettings()
            },
            onDismiss = { showOverlayBehaviorSettings = false },
        )
    }
}

@Composable
private fun HideFullscreenSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = ViewCompat.getWindowInsetsController(view)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
}

@Composable
internal fun OverlayBehaviorSettingsDialog(
    autoOverlayFullscreen: Boolean,
    autoOverlayLandscape: Boolean,
    overlayVerticalPosition: Float,
    onAutoOverlayFullscreenChange: (Boolean) -> Unit,
    onAutoOverlayLandscapeChange: (Boolean) -> Unit,
    onOverlayVerticalPositionChange: (Float) -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overlay behavior") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Overlay in fullscreen")
                        Text(
                            "Automatically use the compact bilingual overlay whenever YouTube enters fullscreen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoOverlayFullscreen,
                        onCheckedChange = onAutoOverlayFullscreenChange,
                        modifier = Modifier.testTag("auto_overlay_fullscreen_switch"),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Overlay in landscape")
                        Text(
                            "Rotate sideways to replace the transcript panel with the compact overlay automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoOverlayLandscape,
                        onCheckedChange = onAutoOverlayLandscapeChange,
                        modifier = Modifier.testTag("auto_overlay_landscape_switch"),
                    )
                }
                Spacer(Modifier.size(16.dp))
                Text("Overlay position")
                Text(
                    "Move the bilingual caption higher or lower over the video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = normalizeOverlayVerticalPosition(overlayVerticalPosition),
                    onValueChange = onOverlayVerticalPositionChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.testTag("overlay_position_slider"),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Higher",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Lower",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(10.dp))
                TextButton(onClick = onOpenSubtitleSettings) {
                    Text("Open dual-subtitle settings")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun LearningSubtitleOverlay(
    content: LearningOverlayContent,
    fontScale: Float,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        delay(2_500)
        controlsVisible = false
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.90f)
            .widthIn(max = 720.dp)
            .testTag("learning_subtitle_overlay")
            .clickable { controlsVisible = !controlsVisible },
        shape = RoundedCornerShape(10.dp),
        color = Color(0xD7061719),
        contentColor = Color(0xFFF3FAFA),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 14.dp,
                    end = if (controlsVisible) 2.dp else 14.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                content.originalText?.let { original ->
                    Text(
                        text = original,
                        fontSize = (17f * fontScale).sp,
                        lineHeight = (21f * fontScale).sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content.translatedText?.let { translated ->
                    if (content.originalText != null) Spacer(Modifier.size(2.dp))
                    Text(
                        text = translated,
                        fontSize = (14f * fontScale).sp,
                        lineHeight = (18f * fontScale).sp,
                        color = Color(0xFF75E7C1),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content.statusText?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFC9D9DB),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (controlsVisible) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Dual-subtitle settings")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Return to transcript panel")
                }
            }
        }
    }
}
