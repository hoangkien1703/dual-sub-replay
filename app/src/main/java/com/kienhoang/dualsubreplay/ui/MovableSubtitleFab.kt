package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal const val COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE = "collapsed_cc_horizontal_position"
internal const val COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE = "collapsed_cc_vertical_position"
internal const val DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION = 1f
internal const val DEFAULT_COLLAPSED_CC_VERTICAL_POSITION = 1f
private const val COLLAPSED_CC_MARGIN_DP = 16

internal fun normalizeControlPosition(value: Float, fallback: Float = 1f): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else fallback.coerceIn(0f, 1f)

internal fun controlPositionAfterDrag(
    currentPosition: Float,
    deltaPx: Float,
    travelPx: Float,
): Float {
    if (!deltaPx.isFinite() || !travelPx.isFinite() || travelPx <= 0f) {
        return normalizeControlPosition(currentPosition)
    }
    return normalizeControlPosition(currentPosition + deltaPx / travelPx)
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

/** A collapsed CC control that can be parked anywhere without blocking the YouTube page. */
@Composable
internal fun MovableSubtitleFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        val horizontalTravelPx =
            (parentWidthPx - controlWidthPx - marginPx * 2).coerceAtLeast(1).toFloat()
        val verticalTravelPx =
            (parentHeightPx - controlHeightPx - marginPx * 2).coerceAtLeast(1).toFloat()

        fun commitPosition() {
            if (!rememberPosition) return
            preferences.edit()
                .putFloat(COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE, horizontalPosition)
                .putFloat(COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE, verticalPosition)
                .apply()
        }

        SmallFloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = controlOffsetPx(
                            horizontalPosition,
                            parentWidthPx,
                            controlWidthPx,
                            marginPx,
                        ),
                        y = controlOffsetPx(
                            verticalPosition,
                            parentHeightPx,
                            controlHeightPx,
                            marginPx,
                        ),
                    )
                }
                .onSizeChanged {
                    controlWidthPx = it.width
                    controlHeightPx = it.height
                }
                .pointerInput(movableEnabled, horizontalTravelPx, verticalTravelPx) {
                    if (movableEnabled) {
                        detectDragGestures(
                            onDragEnd = ::commitPosition,
                            onDragCancel = ::commitPosition,
                        ) { change, dragAmount ->
                            change.consume()
                            horizontalPosition = controlPositionAfterDrag(
                                horizontalPosition,
                                dragAmount.x,
                                horizontalTravelPx,
                            )
                            verticalPosition = controlPositionAfterDrag(
                                verticalPosition,
                                dragAmount.y,
                                verticalTravelPx,
                            )
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
