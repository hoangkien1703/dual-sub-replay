package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE = "collapsed_cc_horizontal_position"
internal const val COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE = "collapsed_cc_vertical_position"
internal const val DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION = 1f
internal const val DEFAULT_COLLAPSED_CC_VERTICAL_POSITION = 0.72f
internal const val FULLSCREEN_LANDSCAPE_DEFAULT_COLLAPSED_CC_VERTICAL_POSITION = 0.08f
private const val COLLAPSED_CC_MARGIN_DP = 16

internal fun normalizeControlPosition(value: Float, fallback: Float = 1f): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else fallback.coerceIn(0f, 1f)

internal fun collapsedCcVerticalPositionForContext(
    position: Float,
    isFullscreen: Boolean,
    orientation: Int,
): Float {
    val normalized = normalizeControlPosition(position, DEFAULT_COLLAPSED_CC_VERTICAL_POSITION)
    return if (
        isFullscreen &&
        orientation == Configuration.ORIENTATION_LANDSCAPE &&
        normalized == DEFAULT_COLLAPSED_CC_VERTICAL_POSITION
    ) {
        FULLSCREEN_LANDSCAPE_DEFAULT_COLLAPSED_CC_VERTICAL_POSITION
    } else {
        normalized
    }
}

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
    isFullscreen: Boolean = false,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
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
    var verticalPosition by remember(isFullscreen, configuration.orientation) {
        mutableFloatStateOf(
            collapsedCcVerticalPositionForContext(
                position = if (rememberPosition) {
                    preferences.getFloat(
                        COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE,
                        DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
                    )
                } else {
                    DEFAULT_COLLAPSED_CC_VERTICAL_POSITION
                },
                isFullscreen = isFullscreen,
                orientation = configuration.orientation,
            ),
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

    DisposableEffect(preferences, isFullscreen, configuration.orientation) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                MOVABLE_OVERLAY_PREFERENCE -> {
                    movableEnabled = sharedPreferences.getBoolean(key, true)
                }
                REMEMBER_OVERLAY_POSITION_PREFERENCE -> {
                    rememberPosition = sharedPreferences.getBoolean(key, true)
                    if (!rememberPosition) {
                        horizontalPosition = DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION
                        verticalPosition = collapsedCcVerticalPositionForContext(
                            position = DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
                            isFullscreen = isFullscreen,
                            orientation = configuration.orientation,
                        )
                    }
                }
                COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE -> {
                    horizontalPosition = normalizeControlPosition(
                        sharedPreferences.getFloat(key, DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION),
                    )
                }
                COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE -> {
                    verticalPosition = collapsedCcVerticalPositionForContext(
                        position = sharedPreferences.getFloat(
                            key,
                            DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
                        ),
                        isFullscreen = isFullscreen,
                        orientation = configuration.orientation,
                    )
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(isFullscreen, configuration.orientation, rememberPosition) {
        val storedPosition = if (rememberPosition) {
            preferences.getFloat(
                COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE,
                DEFAULT_COLLAPSED_CC_VERTICAL_POSITION,
            )
        } else {
            DEFAULT_COLLAPSED_CC_VERTICAL_POSITION
        }
        verticalPosition = collapsedCcVerticalPositionForContext(
            position = storedPosition,
            isFullscreen = isFullscreen,
            orientation = configuration.orientation,
        )
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
