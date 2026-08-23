package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    var showPlayerModeDialog by remember { mutableStateOf(false) }

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

    Box(Modifier.fillMaxSize()) {
        DualSubApp(viewModel)

        if (state.onboardingCompleted && state.activeVideoId != null) {
            when (mode) {
                PlayerExperienceMode.TRANSCRIPT_PANEL -> {
                    SmallFloatingActionButton(
                        onClick = { showPlayerModeDialog = true },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                            .testTag("player_mode_button"),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Player mode")
                    }
                }

                PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY -> {
                    learningOverlayContent(state)?.let { content ->
                        val configuration = LocalConfiguration.current
                        val overlayModifier = if (
                            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        ) {
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, end = 16.dp, bottom = 84.dp)
                        } else {
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = portraitLearningOverlayTopPaddingDp(
                                        configuration.screenWidthDp,
                                    ).dp,
                                )
                        }
                        LearningSubtitleOverlay(
                            content = content,
                            fontScale = state.fontScale,
                            modifier = overlayModifier,
                            onSettings = { showPlayerModeDialog = true },
                            onClose = { selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL) },
                        )
                    }

                    // The underlying DualSubApp intentionally shows its regular "show subtitles"
                    // FAB when the transcript panel is hidden. Cover the same bottom-right slot with
                    // this mode button so taps cannot accidentally reopen the large panel.
                    SmallFloatingActionButton(
                        onClick = { showPlayerModeDialog = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .testTag("player_mode_button"),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Player mode")
                    }
                }
            }
        }
    }

    if (showPlayerModeDialog) {
        PlayerModeDialog(
            selectedMode = mode,
            onModeChange = { selected ->
                selectMode(selected)
                showPlayerModeDialog = false
            },
            onDismiss = { showPlayerModeDialog = false },
        )
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
    Surface(
        modifier = modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 760.dp)
            .testTag("learning_subtitle_overlay"),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xDE061719),
        contentColor = Color(0xFFF3FAFA),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                content.originalText?.let { original ->
                    Text(
                        text = original,
                        fontSize = (18f * fontScale).sp,
                        lineHeight = (22f * fontScale).sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content.translatedText?.let { translated ->
                    if (content.originalText != null) Spacer(Modifier.size(3.dp))
                    Text(
                        text = translated,
                        fontSize = (15f * fontScale).sp,
                        lineHeight = (19f * fontScale).sp,
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
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Player mode settings")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Return to transcript panel")
            }
        }
    }
}

@Composable
internal fun PlayerModeDialog(
    selectedMode: PlayerExperienceMode,
    onModeChange: (PlayerExperienceMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Player mode") },
        text = {
            Column {
                PlayerModeOption(
                    mode = PlayerExperienceMode.TRANSCRIPT_PANEL,
                    selectedMode = selectedMode,
                    title = "Transcript panel",
                    description = "Full dual-subtitle timeline with paragraph replay.",
                    onModeChange = onModeChange,
                )
                HorizontalDivider()
                PlayerModeOption(
                    mode = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
                    selectedMode = selectedMode,
                    title = "Scroll-friendly overlay",
                    description = "Keep YouTube scrollable so you can read comments and choose another video while the current dual subtitle stays visible.",
                    onModeChange = onModeChange,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PlayerModeOption(
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
            .padding(vertical = 10.dp)
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
