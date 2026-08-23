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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val PLAYER_EXPERIENCE_MODE_PREFERENCE = "player_experience_mode"

enum class PlayerExperienceMode(val storageValue: String) {
    TRANSCRIPT_PANEL("transcript_panel"),
    SCROLL_FRIENDLY_OVERLAY("scroll_friendly_overlay"),
}

internal fun storedPlayerExperienceMode(raw: String?): PlayerExperienceMode =
    PlayerExperienceMode.entries.firstOrNull { it.storageValue == raw }
        ?: PlayerExperienceMode.TRANSCRIPT_PANEL

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
 * Places the compact overlay inside the lower half of a typical 16:9 mobile YouTube player.
 * It deliberately stays small so the underlying WebView remains usable for scrolling and browsing.
 */
internal fun portraitLearningOverlayTopPaddingDp(screenWidthDp: Int): Int {
    val estimatedVideoBottom = 56f + screenWidthDp.coerceAtLeast(0) * 9f / 16f
    return (estimatedVideoBottom - 112f).roundToInt().coerceIn(104, 280)
}

/**
 * Keeps the existing single persistent YouTube WebView in composition while offering a second,
 * scroll-friendly presentation mode. No second player or WebView is created.
 */
@Composable
fun LearningPlayerRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    fun requestSettings() {
        settingsRequestId += 1L
    }

    // Opening another video normally re-opens the transcript panel. In overlay mode we immediately
    // collapse it again so the YouTube page stays scrollable while captions reload for the new video.
    LaunchedEffect(mode, state.activeVideoId, state.subtitlePanelVisible) {
        if (
            mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY &&
            state.activeVideoId != null &&
            state.subtitlePanelVisible
        ) {
            viewModel.hideSubtitlePanel()
        }
    }

    val overlayContent = learningOverlayContent(state)
    val fullscreenLearningOverlay: (@Composable BoxScope.() -> Unit)? =
        if (mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY && overlayContent != null) {
            {
                LearningSubtitleOverlay(
                    content = overlayContent,
                    fontScale = state.fontScale,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 88.dp),
                    onSettings = ::requestSettings,
                    onClose = { selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL) },
                )
            }
        } else {
            null
        }

    Box(Modifier.fillMaxSize()) {
        DualSubApp(
            viewModel = viewModel,
            playerMode = mode,
            onPlayerModeChange = ::selectMode,
            externalSettingsRequestId = settingsRequestId,
            fullscreenLearningOverlay = fullscreenLearningOverlay,
        )

        if (
            state.onboardingCompleted &&
            state.activeVideoId != null &&
            mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY
        ) {
            overlayContent?.let { content ->
                val configuration = LocalConfiguration.current
                val overlayModifier = if (
                    configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                ) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 18.dp, end = 18.dp, bottom = 82.dp)
                } else {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            start = 18.dp,
                            end = 18.dp,
                            top = portraitLearningOverlayTopPaddingDp(
                                configuration.screenWidthDp,
                            ).dp,
                        )
                }
                LearningSubtitleOverlay(
                    content = content,
                    fontScale = state.fontScale,
                    modifier = overlayModifier,
                    onSettings = ::requestSettings,
                    onClose = { selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL) },
                )
            }
        }
    }
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
