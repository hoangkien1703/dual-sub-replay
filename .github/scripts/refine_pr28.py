from pathlib import Path

def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))

branch_file = Path("app/src/main/java/com/kienhoang/dualsubreplay/ui/MovableSubtitleFab.kt")
branch_file.write_text(r'''package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE = "collapsed_cc_horizontal_position"
internal const val COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE = "collapsed_cc_vertical_position"
internal const val DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION = 1f
internal const val DEFAULT_COLLAPSED_CC_VERTICAL_POSITION = 1f
private const val COLLAPSED_CC_MARGIN_DP = 16

internal fun normalizeControlPosition(value: Float, fallback: Float = 1f): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else fallback.coerceIn(0f, 1f)

internal fun controlOffsetPx(
    position: Float,
    parentSizePx: Int,
    controlSizePx: Int,
    marginPx: Int,
): Int {
    val safeMargin = marginPx.coerceAtLeast(0)
    val travel = (parentSizePx - controlSizePx - safeMargin * 2).coerceAtLeast(0)
    return safeMargin + (travel * normalizeControlPosition(position)).roundToInt()
}

internal fun controlPositionFromOffsetPx(
    offsetPx: Float,
    parentSizePx: Int,
    controlSizePx: Int,
    marginPx: Int,
): Float {
    val safeMargin = marginPx.coerceAtLeast(0)
    val travel = (parentSizePx - controlSizePx - safeMargin * 2).coerceAtLeast(0)
    if (!offsetPx.isFinite() || travel <= 0) return 0f
    return ((offsetPx - safeMargin) / travel.toFloat()).coerceIn(0f, 1f)
}

/**
 * Collapsed CC control that follows the finger directly in pixel space.
 *
 * Normalized coordinates are only converted/saved at the end of a drag, avoiding
 * repeated layout-scale conversions while the pointer is moving.
 */
@Composable
internal fun MovableSubtitleFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    autoDimAfterMillis: Long? = null,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("dual_sub_preferences", 0)
    }
    var movableEnabled by remember {
        mutableStateOf(preferences.getBoolean(MOVABLE_OVERLAY_PREFERENCE, true))
    }
    var rememberPosition by remember {
        mutableStateOf(preferences.getBoolean(REMEMBER_OVERLAY_POSITION_PREFERENCE, true))
    }
    var horizontalPosition by remember {
        mutableFloatStateOf(
            if (rememberPosition) {
                normalizeControlPosition(
                    preferences.getFloat(
                        COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE,
                        DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION,
                    ),
                )
            } else {
                DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION
            },
        )
    }
    var verticalPosition by remember {
        mutableFloatStateOf(
            if (rememberPosition) {
                normalizeControlPosition(
                    preferences.getFloat(
                        COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE,
                        DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
                    ),
                )
            } else {
                DEFAULT_COLLAPSED_CC_VERTICAL_POSITION
            },
        )
    }
    var offsetXPx by remember { mutableFloatStateOf(0f) }
    var offsetYPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var interactionGeneration by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.32f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "collapsedCcAlpha",
    )
    val animatedBlur by animateDpAsState(
        targetValue = if (dimmed) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "collapsedCcBlur",
    )

    fun noteInteraction() {
        dimmed = false
        interactionGeneration += 1
    }

    LaunchedEffect(autoDimAfterMillis, interactionGeneration, isDragging) {
        dimmed = false
        val timeout = autoDimAfterMillis
        if (!isDragging && timeout != null && timeout > 0L) {
            delay(timeout)
            dimmed = true
        }
    }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                MOVABLE_OVERLAY_PREFERENCE -> {
                    movableEnabled = sharedPreferences.getBoolean(key, true)
                }
                REMEMBER_OVERLAY_POSITION_PREFERENCE -> {
                    rememberPosition = sharedPreferences.getBoolean(key, true)
                    if (!rememberPosition) {
                        horizontalPosition = DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION
                        verticalPosition = DEFAULT_COLLAPSED_CC_VERTICAL_POSITION
                    }
                }
                COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE -> {
                    horizontalPosition = normalizeControlPosition(
                        sharedPreferences.getFloat(key, DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION),
                    )
                }
                COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE -> {
                    verticalPosition = normalizeControlPosition(
                        sharedPreferences.getFloat(key, DEFAULT_COLLAPSED_CC_VERTICAL_POSITION),
                    )
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parentWidthPx = with(density) { maxWidth.toPx() }.roundToInt()
        val parentHeightPx = with(density) { maxHeight.toPx() }.roundToInt()
        val marginPx = with(density) { COLLAPSED_CC_MARGIN_DP.dp.toPx() }.roundToInt()
        var controlWidthPx by remember { mutableIntStateOf(0) }
        var controlHeightPx by remember { mutableIntStateOf(0) }

        val minX = marginPx.toFloat()
        val minY = marginPx.toFloat()
        val maxX = (parentWidthPx - controlWidthPx - marginPx).coerceAtLeast(marginPx).toFloat()
        val maxY = (parentHeightPx - controlHeightPx - marginPx).coerceAtLeast(marginPx).toFloat()

        LaunchedEffect(
            parentWidthPx,
            parentHeightPx,
            controlWidthPx,
            controlHeightPx,
            horizontalPosition,
            verticalPosition,
        ) {
            if (!isDragging && controlWidthPx > 0 && controlHeightPx > 0) {
                offsetXPx = controlOffsetPx(
                    horizontalPosition,
                    parentWidthPx,
                    controlWidthPx,
                    marginPx,
                ).toFloat()
                offsetYPx = controlOffsetPx(
                    verticalPosition,
                    parentHeightPx,
                    controlHeightPx,
                    marginPx,
                ).toFloat()
            }
        }

        fun commitPosition() {
            horizontalPosition = controlPositionFromOffsetPx(
                offsetXPx,
                parentWidthPx,
                controlWidthPx,
                marginPx,
            )
            verticalPosition = controlPositionFromOffsetPx(
                offsetYPx,
                parentHeightPx,
                controlHeightPx,
                marginPx,
            )
            if (rememberPosition) {
                preferences.edit()
                    .putFloat(COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE, horizontalPosition)
                    .putFloat(COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE, verticalPosition)
                    .apply()
            }
        }

        SmallFloatingActionButton(
            onClick = {
                noteInteraction()
                onClick()
            },
            modifier = Modifier
                .graphicsLayer {
                    translationX = offsetXPx
                    translationY = offsetYPx
                    alpha = animatedAlpha
                }
                .blur(animatedBlur)
                .onSizeChanged {
                    controlWidthPx = it.width
                    controlHeightPx = it.height
                }
                .pointerInput(movableEnabled, parentWidthPx, parentHeightPx, controlWidthPx, controlHeightPx) {
                    if (movableEnabled) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                noteInteraction()
                            },
                            onDragEnd = {
                                commitPosition()
                                isDragging = false
                                noteInteraction()
                            },
                            onDragCancel = {
                                commitPosition()
                                isDragging = false
                                noteInteraction()
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            offsetXPx = (offsetXPx + dragAmount.x).coerceIn(minX, maxX)
                            offsetYPx = (offsetYPx + dragAmount.y).coerceIn(minY, maxY)
                        }
                    }
                }
                .testTag("show_subtitles"),
            shape = androidx.compose.foundation.shape.CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Default.ClosedCaption, contentDescription = "Show dual subtitles")
        }
    }
}
''')

learning = Path("app/src/main/java/com/kienhoang/dualsubreplay/ui/LearningPlayerRoot.kt")
replace_once(
    learning,
    '''internal const val SUBTITLE_BOX_SWIPE_DISMISS_VELOCITY_PX_PER_SECOND = 1_500f
internal const val SUBTITLE_BOX_MINIMUM_SWIPE_DISTANCE_DP = 72
internal const val FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP = 88
''',
    '''internal const val FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP = 88
''',
)
replace_once(
    learning,
    '''/**
 * A quick downward flick closes the subtitle box in portrait (issue #26):
 * either a deliberate drag past [minimumDistancePx] or any fast fling.
 */
internal fun shouldDismissSubtitleBox(
    dragOffsetPx: Float,
    velocityPxPerSecond: Float,
    minimumDistancePx: Float,
): Boolean {
    if (!dragOffsetPx.isFinite() || dragOffsetPx <= 0f) return false
    return dragOffsetPx >= minimumDistancePx ||
        velocityPxPerSecond >= SUBTITLE_BOX_SWIPE_DISMISS_VELOCITY_PX_PER_SECOND && dragOffsetPx > 24f
}

''',
    '''internal fun fullscreenOverlayDragTravelDp(screenHeightDp: Int): Int =
    (screenHeightDp.coerceAtLeast(240) - FULLSCREEN_OVERLAY_ESTIMATED_HEIGHT_DP).coerceAtLeast(60)

''',
)
replace_once(
    learning,
    '''    var restoreTranscriptAfterAutomaticOverlay by remember { mutableStateOf(false) }
    var settingsRequestId by remember { mutableLongStateOf(0L) }
''',
    '''    var restoreTranscriptAfterAutomaticOverlay by remember { mutableStateOf(false) }
    var fullscreenOverlayHiddenByUser by remember { mutableStateOf(false) }
    var settingsRequestId by remember { mutableLongStateOf(0L) }
''',
)
replace_once(
    learning,
    '''    // Opening another video normally re-opens the transcript panel. While overlay presentation is
''',
    '''    LaunchedEffect(youtubeFullscreen) {
        if (!youtubeFullscreen) fullscreenOverlayHiddenByUser = false
    }

    // Opening another video normally re-opens the transcript panel. While overlay presentation is
''',
)
old_fullscreen = '''    val fullscreenLearningOverlay: @Composable BoxScope.() -> Unit = {
        HideFullscreenSystemBars()
        if (overlayContent != null && effectiveMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
  LearningSubtitleOverlay(
      content = overlayContent,
      fontScale = state.fontScale,
      position = overlayVerticalPosition,
      orientation = configuration.orientation,
      movableEnabled = movableSubtitleBox,
      horizontalPosition = overlayHorizontalPosition,
      originalColor = effectiveOriginalColor(state),
      translatedColor = effectiveTranslatedColor(state),
      highlightColor = effectiveHighlightColor(state),
      backgroundColor = subtitleBoxBackgroundColor,
      modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(start = 20.dp, end = 20.dp, bottom = fullscreenBottomPadding),
      onPositionChange = ::updateOverlayPosition,
      onHorizontalPositionChange = ::updateOverlayHorizontalPosition,
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
'''
new_fullscreen = '''    val fullscreenLearningOverlay: @Composable BoxScope.() -> Unit = {
        HideFullscreenSystemBars()
        if (overlayContent != null && effectiveMode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) {
            if (fullscreenOverlayHiddenByUser) {
                MovableSubtitleFab(
                    onClick = { fullscreenOverlayHiddenByUser = false },
                    modifier = Modifier.fillMaxSize(),
                    autoDimAfterMillis = 1_000L,
                )
            } else {
                LearningSubtitleOverlay(
                    content = overlayContent,
                    fontScale = state.fontScale,
                    position = overlayVerticalPosition,
                    orientation = configuration.orientation,
                    movableEnabled = movableSubtitleBox,
                    horizontalPosition = overlayHorizontalPosition,
                    isFullscreen = true,
                    originalColor = effectiveOriginalColor(state),
                    translatedColor = effectiveTranslatedColor(state),
                    highlightColor = effectiveHighlightColor(state),
                    backgroundColor = subtitleBoxBackgroundColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = fullscreenBottomPadding),
                    onPositionChange = ::updateOverlayPosition,
                    onHorizontalPositionChange = ::updateOverlayHorizontalPosition,
                    onPositionChangeFinished = ::commitOverlayPosition,
                    onSettings = ::requestSubtitleSettings,
                    onClose = { fullscreenOverlayHiddenByUser = true },
                )
            }
        }
    }
'''
replace_once(learning, old_fullscreen, new_fullscreen)

replace_once(
    learning,
    '''    horizontalPosition: Float = DEFAULT_OVERLAY_HORIZONTAL_POSITION,
    originalColor: Color = Color.White,
''',
    '''    horizontalPosition: Float = DEFAULT_OVERLAY_HORIZONTAL_POSITION,
    isFullscreen: Boolean = false,
    originalColor: Color = Color.White,
''',
)
replace_once(
    learning,
    '''    val dragTravelPx = with(density) {
        (configuration.screenHeightDp.dp * 0.45f).toPx()
    }.coerceAtLeast(1f)
    val minimumSwipeDistancePx = with(density) {
        SUBTITLE_BOX_MINIMUM_SWIPE_DISTANCE_DP.dp.toPx()
    }
    val currentPosition by rememberUpdatedState(position)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnHorizontalPositionChange by rememberUpdatedState(onHorizontalPositionChange)
    val currentOnPositionChangeFinished by rememberUpdatedState(onPositionChangeFinished)
    val currentOnClose by rememberUpdatedState(onClose)
    var downwardDragDistance by remember { mutableFloatStateOf(0f) }
''',
    '''    val dragTravelPx = with(density) {
        if (isFullscreen) {
            fullscreenOverlayDragTravelDp(configuration.screenHeightDp).dp.toPx()
        } else {
            (configuration.screenHeightDp.dp * 0.45f).toPx()
        }
    }.coerceAtLeast(1f)
    val currentPosition by rememberUpdatedState(position)
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnHorizontalPositionChange by rememberUpdatedState(onHorizontalPositionChange)
    val currentOnPositionChangeFinished by rememberUpdatedState(onPositionChangeFinished)
''',
)
replace_once(
    learning,
    '''    val horizontalDragTravelPx = with(density) {
        configuration.screenWidthDp.dp.toPx()
    }.coerceAtLeast(1f)
    val currentHorizontalPosition by rememberUpdatedState(horizontalPosition)

    val verticalDragState = rememberDraggableState { delta ->
        // Only net downward travel counts toward the swipe-to-close gesture.
        downwardDragDistance = (downwardDragDistance + delta).coerceAtLeast(0f)
        currentOnPositionChange(
            overlayPositionAfterDrag(currentPosition, delta, dragTravelPx),
        )
    }
''',
    '''    val horizontalDragTravelPx =
        (parentWidthPx - selfWidthPx).coerceAtLeast(1).toFloat()
    val currentHorizontalPosition by rememberUpdatedState(horizontalPosition)

    val verticalDragState = rememberDraggableState { delta ->
        currentOnPositionChange(
            overlayPositionAfterDrag(currentPosition, delta, dragTravelPx),
        )
    }
''',
)
replace_once(
    learning,
    '''  .draggable(
      state = verticalDragState,
      orientation = Orientation.Vertical,
      enabled = movableEnabled,
      onDragStarted = { downwardDragDistance = 0f },
      onDragStopped = { velocity ->
          val dismissed = orientation == Configuration.ORIENTATION_PORTRAIT &&
              shouldDismissSubtitleBox(
                  dragOffsetPx = downwardDragDistance,
                  velocityPxPerSecond = velocity,
                  minimumDistancePx = minimumSwipeDistancePx,
              )
          if (dismissed) {
              downwardDragDistance = 0f
              currentOnClose()
          } else {
              downwardDragDistance = 0f
              currentOnPositionChangeFinished()
          }
      },
  )
''',
    '''  .draggable(
      state = verticalDragState,
      orientation = Orientation.Vertical,
      enabled = movableEnabled,
      onDragStopped = { currentOnPositionChangeFinished() },
  )
''',
)
replace_once(
    learning,
    '''      IconButton(onClick = onClose) {
          Icon(Icons.Default.Close, contentDescription = "Return to transcript panel")
      }
''',
    '''      IconButton(onClick = onClose) {
          Icon(Icons.Default.Close, contentDescription = "Hide dual subtitles")
      }
''',
)

dual = Path("app/src/main/java/com/kienhoang/dualsubreplay/ui/DualSubApp.kt")
replace_once(
    dual,
    '''                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.60f)
                        .testTag("subtitle_timeline"),
''',
    '''                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(
                            if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                                portraitSubtitlePanelHeightFraction(
                                    screenWidthDp = configuration.screenWidthDp,
                                    screenHeightDp = configuration.screenHeightDp,
                                )
                            } else {
                                0.60f
                            },
                        )
                        .testTag("subtitle_timeline"),
''',
)
replace_once(
    dual,
    '''@Composable
private fun SubtitlePanel(
''',
    '''internal fun portraitSubtitlePanelHeightFraction(
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
''',
)

test = Path("app/src/test/java/com/kienhoang/dualsubreplay/ui/MovableSubtitleFabTest.kt")
test.write_text(r'''package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MovableSubtitleFabTest {
    @Test fun positionsNormalizeAndRejectInvalidValues() {
        assertEquals(0f, normalizeControlPosition(-3f), 0f)
        assertEquals(1f, normalizeControlPosition(3f), 0f)
        assertEquals(0.4f, normalizeControlPosition(0.4f), 0f)
        assertEquals(1f, normalizeControlPosition(Float.NaN), 0f)
    }

    @Test fun offsetKeepsTheWholeControlInsideMargins() {
        assertEquals(16, controlOffsetPx(0f, 400, 40, 16))
        assertEquals(344, controlOffsetPx(1f, 400, 40, 16))
        assertEquals(180, controlOffsetPx(0.5f, 400, 40, 16))
        assertEquals(16, controlOffsetPx(1f, 20, 40, 16))
    }

    @Test fun pixelOffsetRoundTripsToNormalizedPosition() {
        assertEquals(0f, controlPositionFromOffsetPx(16f, 400, 40, 16), 0f)
        assertEquals(0.5f, controlPositionFromOffsetPx(180f, 400, 40, 16), 0.0001f)
        assertEquals(1f, controlPositionFromOffsetPx(344f, 400, 40, 16), 0f)
        assertEquals(0f, controlPositionFromOffsetPx(Float.NaN, 400, 40, 16), 0f)
    }

    @Test fun portraitPanelStartsNearEstimatedVideoBottom() {
        val fraction = portraitSubtitlePanelHeightFraction(400, 900)
        assertEquals(0.6805f, fraction, 0.01f)
        assertEquals(0.60f, portraitSubtitlePanelHeightFraction(800, 600), 0f)
    }
}
''')

root_test = Path("app/src/test/java/com/kienhoang/dualsubreplay/ui/LearningPlayerRootTest.kt")
replace_once(
    root_test,
    '''    @Test
    fun swipeDownDismissesOnlyDeliberateOrFastDownwardGestures() {
        val minimum = SUBTITLE_BOX_MINIMUM_SWIPE_DISTANCE_DP.toFloat()
        assertFalse(shouldDismissSubtitleBox(-50f, 4_000f, minimum))
        assertFalse(shouldDismissSubtitleBox(0f, 4_000f, minimum))
        assertFalse(shouldDismissSubtitleBox(20f, 900f, minimum))
        assertTrue(shouldDismissSubtitleBox(minimum, 0f, minimum))
        assertTrue(shouldDismissSubtitleBox(minimum / 2f, 2_000f, minimum))
    }

''',
    '''    @Test
    fun fullscreenDragTravelMatchesVisibleVerticalTravel() {
        assertEquals(712, fullscreenOverlayDragTravelDp(800))
        assertEquals(152, fullscreenOverlayDragTravelDp(240))
    }

''',
)

print("patched")
