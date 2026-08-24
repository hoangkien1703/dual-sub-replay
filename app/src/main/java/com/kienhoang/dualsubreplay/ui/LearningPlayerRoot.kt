package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val PLAYER_EXPERIENCE_MODE_PREFERENCE = "player_experience_mode"
internal const val AUTO_OVERLAY_FULLSCREEN_PREFERENCE = "auto_overlay_fullscreen"
internal const val AUTO_OVERLAY_LANDSCAPE_PREFERENCE = "auto_overlay_landscape"
internal const val AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE = "auto_avoid_player_controls"
internal const val REMEMBER_OVERLAY_POSITION_PREFERENCE = "remember_overlay_position"
internal const val OVERLAY_VERTICAL_POSITION_PREFERENCE = "overlay_vertical_position"
internal const val DEFAULT_OVERLAY_VERTICAL_POSITION = 0.86f
internal const val PLAYER_CONTROLS_AVOIDANCE_LIFT_DP = 88

enum class PlayerExperienceMode(val storageValue: String) {
    TRANSCRIPT_PANEL("transcript_panel"),
    SCROLL_FRIENDLY_OVERLAY("scroll_friendly_overlay"),
}

internal fun storedPlayerExperienceMode(raw: String?): PlayerExperienceMode =
    PlayerExperienceMode.entries.firstOrNull { it.storageValue == raw }
        ?: PlayerExperienceMode.TRANSCRIPT_PANEL

internal fun normalizeOverlayVerticalPosition(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT_OVERLAY_VERTICAL_POSITION

/** 0 = higher, 1 = lower. */
internal fun overlayBottomPaddingDp(position: Float): Int {
    val normalized = normalizeOverlayVerticalPosition(position)
    return (180f - normalized * 160f).roundToInt()
}

internal fun overlayPositionAfterDrag(
    currentPosition: Float,
    deltaPx: Float,
    dragTravelPx: Float,
): Float {
    if (!deltaPx.isFinite() || !dragTravelPx.isFinite() || dragTravelPx <= 0f) {
        return normalizeOverlayVerticalPosition(currentPosition)
    }
    return normalizeOverlayVerticalPosition(currentPosition + deltaPx / dragTravelPx)
}

internal fun playerControlsAvoidanceLiftDp(
    enabled: Boolean,
    controlsVisible: Boolean,
): Int = if (enabled && controlsVisible) PLAYER_CONTROLS_AVOIDANCE_LIFT_DP else 0

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
    val activeWordIndex: Int = -1,
    val segment: SubtitleSegment? = null,
)

internal fun learningOverlayContent(state: DualSubUiState): LearningOverlayContent? {
    if (state.activeVideoId == null) return null
    val active = state.segments.getOrNull(state.currentIndex)
    if (active != null) {
        return LearningOverlayContent(
  originalText = active.originalText,
  translatedText = active.translatedText ?: "Translating…",
  statusText = null,
  activeWordIndex = if (state.wordHighlightEnabled) state.activeWordIndex else -1,
  segment = active,
        )
    }
    val status = state.errorMessage ?: state.statusMessage
        ?: if (state.segments.isNotEmpty()) "Waiting for the next caption…" else null
    return status?.let {
        LearningOverlayContent(originalText = null, translatedText = null, statusText = it)
    }
}

/** Places the portrait overlay near the lower edge of a typical 16:9 mobile YouTube player. */
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

/** Keeps one persistent YouTube WebView while changing only the learning presentation layer. */
@Composable
fun LearningPlayerRoot(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val youtubeControlsVisible by youtubePlayerControlsVisible.collectAsStateWithLifecycle()
    val youtubeFullscreen by youtubeFullscreenActive.collectAsStateWithLifecycle()
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
    var autoAvoidPlayerControls by remember {
        mutableStateOf(preferences.getBoolean(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE, true))
    }
    var rememberOverlayPosition by remember {
        mutableStateOf(preferences.getBoolean(REMEMBER_OVERLAY_POSITION_PREFERENCE, true))
    }
    var subtitleBoxBackgroundKey by remember {
        mutableStateOf(
            storedSubtitleBoxBackgroundKey(
                preferences.getString(
                    SUBTITLE_BOX_BACKGROUND_PREFERENCE,
                    DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY,
                ),
            ),
        )
    }
    var overlayVerticalPosition by remember {
        mutableStateOf(
  if (rememberOverlayPosition) {
      normalizeOverlayVerticalPosition(
          preferences.getFloat(
              OVERLAY_VERTICAL_POSITION_PREFERENCE,
              DEFAULT_OVERLAY_VERTICAL_POSITION,
          ),
      )
  } else {
      DEFAULT_OVERLAY_VERTICAL_POSITION
  },
        )
    }
    var restoreTranscriptAfterAutomaticOverlay by remember { mutableStateOf(false) }
    var settingsRequestId by remember { mutableLongStateOf(0L) }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
  when (key) {
      PLAYER_EXPERIENCE_MODE_PREFERENCE -> {
          mode = storedPlayerExperienceMode(
              sharedPreferences.getString(key, PlayerExperienceMode.TRANSCRIPT_PANEL.storageValue),
          )
      }
      AUTO_OVERLAY_FULLSCREEN_PREFERENCE -> {
          autoOverlayFullscreen = sharedPreferences.getBoolean(key, true)
      }
      AUTO_OVERLAY_LANDSCAPE_PREFERENCE -> {
          autoOverlayLandscape = sharedPreferences.getBoolean(key, true)
      }
      AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE -> {
          autoAvoidPlayerControls = sharedPreferences.getBoolean(key, true)
      }
      REMEMBER_OVERLAY_POSITION_PREFERENCE -> {
          rememberOverlayPosition = sharedPreferences.getBoolean(key, true)
          if (!rememberOverlayPosition) {
              overlayVerticalPosition = DEFAULT_OVERLAY_VERTICAL_POSITION
          }
      }
      OVERLAY_VERTICAL_POSITION_PREFERENCE -> {
          overlayVerticalPosition = normalizeOverlayVerticalPosition(
              sharedPreferences.getFloat(key, DEFAULT_OVERLAY_VERTICAL_POSITION),
          )
      }
      SUBTITLE_BOX_BACKGROUND_PREFERENCE -> {
          subtitleBoxBackgroundKey = storedSubtitleBoxBackgroundKey(
              sharedPreferences.getString(key, DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY),
          )
      }
  }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

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

    fun updateOverlayPosition(position: Float) {
        overlayVerticalPosition = normalizeOverlayVerticalPosition(position)
    }

    fun commitOverlayPosition() {
        if (rememberOverlayPosition) {
  preferences.edit()
      .putFloat(OVERLAY_VERTICAL_POSITION_PREFERENCE, overlayVerticalPosition)
      .apply()
        }
    }

    val automaticLandscapeOverlay = shouldUseAutomaticLandscapeOverlay(
        mode = mode,
        autoLandscape = autoOverlayLandscape,
        orientation = configuration.orientation,
    )
    val automaticFullscreenOverlay =
        mode == PlayerExperienceMode.TRANSCRIPT_PANEL && autoOverlayFullscreen && youtubeFullscreen
    val automaticPresentationOverlay = automaticLandscapeOverlay || automaticFullscreenOverlay
    val effectiveMode = if (
        mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY || automaticPresentationOverlay
    ) {
        PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY
    } else {
        PlayerExperienceMode.TRANSCRIPT_PANEL
    }

    // Fullscreen and landscape auto-overlay are temporary. Restore the transcript panel only after
    // every automatic overlay condition has ended, so landscape/fullscreen transitions do not flicker.
    LaunchedEffect(automaticPresentationOverlay, mode) {
        if (automaticPresentationOverlay) {
  if (mode == PlayerExperienceMode.TRANSCRIPT_PANEL && state.subtitlePanelVisible) {
      restoreTranscriptAfterAutomaticOverlay = true
  }
  if (state.subtitlePanelVisible) viewModel.hideSubtitlePanel()
        } else if (restoreTranscriptAfterAutomaticOverlay) {
  if (mode == PlayerExperienceMode.TRANSCRIPT_PANEL) viewModel.showSubtitlePanel()
  restoreTranscriptAfterAutomaticOverlay = false
        }
    }

    // Opening another video normally re-opens the transcript panel. While overlay presentation is
    // active, collapse it again so comments and recommendations remain directly scrollable.
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
    val controlsLiftDp = playerControlsAvoidanceLiftDp(
        enabled = autoAvoidPlayerControls,
        controlsVisible = youtubeControlsVisible,
    )
    val bottomPadding = (overlayBottomPaddingDp(overlayVerticalPosition) + controlsLiftDp).dp
    val subtitleBoxBackgroundColor = subtitleBoxBackgroundColor(subtitleBoxBackgroundKey)

    val fullscreenLearningOverlay: @Composable BoxScope.() -> Unit = {
        HideFullscreenSystemBars()
        if (overlayContent != null && effectiveMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
  LearningSubtitleOverlay(
      content = overlayContent,
      fontScale = state.fontScale,
      position = overlayVerticalPosition,
      originalColor = effectiveOriginalColor(state),
      translatedColor = effectiveTranslatedColor(state),
      highlightColor = effectiveHighlightColor(state),
      backgroundColor = subtitleBoxBackgroundColor,
      modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding),
      onPositionChange = ::updateOverlayPosition,
      onPositionChangeFinished = ::commitOverlayPosition,
      onSettings = ::requestSubtitleSettings,
      onClose = {
          if (mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
              selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL)
          } else {
              if (youtubeFullscreen) {
                  preferences.edit().putBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, false).apply()
              }
              if (automaticLandscapeOverlay) {
                  preferences.edit().putBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, false).apply()
              }
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
          val baseTop = portraitLearningOverlayTopPaddingDp(
              configuration.screenWidthDp,
              overlayVerticalPosition,
          )
          Modifier
              .align(Alignment.TopCenter)
              .padding(
                  start = 18.dp,
                  end = 18.dp,
                  top = (baseTop - controlsLiftDp).coerceAtLeast(72).dp,
              )
      }
      LearningSubtitleOverlay(
          content = content,
          fontScale = state.fontScale,
          position = overlayVerticalPosition,
          originalColor = effectiveOriginalColor(state),
          translatedColor = effectiveTranslatedColor(state),
          highlightColor = effectiveHighlightColor(state),
          backgroundColor = subtitleBoxBackgroundColor,
          modifier = overlayModifier,
          onPositionChange = ::updateOverlayPosition,
          onPositionChangeFinished = ::commitOverlayPosition,
          onSettings = ::requestSubtitleSettings,
          onClose = {
              if (mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
                  selectMode(PlayerExperienceMode.TRANSCRIPT_PANEL)
              } else if (automaticLandscapeOverlay) {
                  preferences.edit().putBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, false).apply()
              }
          },
      )
  }
        }
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
internal fun LearningSubtitleOverlay(
    content: LearningOverlayContent,
    fontScale: Float,
    position: Float = DEFAULT_OVERLAY_VERTICAL_POSITION,
    modifier: Modifier = Modifier,
    originalColor: Color = Color.White,
    translatedColor: Color = Color(0xFF75E7C1),
    highlightColor: Color = Color(0xFF75E7C1),
    backgroundColor: Color = subtitleBoxBackgroundColor(DEFAULT_SUBTITLE_BOX_BACKGROUND_KEY),
    onPositionChange: (Float) -> Unit = {},
    onPositionChangeFinished: () -> Unit = {},
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var overlayActionsVisible by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val dragTravelPx = with(density) {
        (configuration.screenHeightDp.dp * 0.45f).toPx()
    }.coerceAtLeast(1f)
    val currentPosition by rememberUpdatedState(position)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnPositionChangeFinished by rememberUpdatedState(onPositionChangeFinished)
    val dragState = rememberDraggableState { delta ->
        currentOnPositionChange(
  overlayPositionAfterDrag(currentPosition, delta, dragTravelPx),
        )
    }

    LaunchedEffect(overlayActionsVisible) {
        if (!overlayActionsVisible) return@LaunchedEffect
        delay(2_500)
        overlayActionsVisible = false
    }

    Surface(
        modifier = modifier
  .fillMaxWidth(0.90f)
  .widthIn(max = 720.dp)
  .testTag("learning_subtitle_overlay")
  .draggable(
      state = dragState,
      orientation = Orientation.Vertical,
      onDragStopped = { currentOnPositionChangeFinished() },
  )
  .clickable { overlayActionsVisible = !overlayActionsVisible },
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        contentColor = Color(0xFFF3FAFA),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
  modifier = Modifier
      .fillMaxWidth()
      .padding(
          start = 14.dp,
          end = if (overlayActionsVisible) 2.dp else 14.dp,
          top = 8.dp,
          bottom = 8.dp,
      ),
  verticalAlignment = Alignment.CenterVertically,
        ) {
  Column(Modifier.weight(1f)) {
      content.originalText?.let { original ->
          val annotated = if (content.activeWordIndex >= 0 && content.segment != null) {
              annotatedSpokenText(
                  segment = content.segment,
                  activeWordIndex = content.activeWordIndex,
                  baseColor = originalColor,
                  highlightColor = highlightColor,
              )
          } else {
              AnnotatedString(original)
          }
          Text(
              text = annotated,
              fontSize = (17f * fontScale).sp,
              lineHeight = (21f * fontScale).sp,
              fontWeight = FontWeight.Medium,
              color = originalColor,
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
              color = translatedColor,
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
  if (overlayActionsVisible) {
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
