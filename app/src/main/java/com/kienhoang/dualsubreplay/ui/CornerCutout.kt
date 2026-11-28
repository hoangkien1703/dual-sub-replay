package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat

/** A camera hole's bounds in window pixels (Android's display cutout rectangles). */
internal data class CutoutBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Room to leave above and beside a top-corner button, in pixels. */
internal data class CornerRoom(
    val top: Int = 0,
    val side: Int = 0,
)

/**
 * How far a button in the top-left ([left]) or top-right corner moves so that no camera hole covers
 * it (owner question 2026-11-28). The app hides the status bar, so the top bar starts at the very
 * top of the screen. A hole that overlaps the button's [buttonWidthPx] by [buttonHeightPx] corner
 * moves it below the hole, or beside it when the hole sits on that side edge (a phone held sideways).
 * A camera in the middle of the top edge moves nothing, so most phones keep the bar as it is.
 */
internal fun cornerRoom(
    cutouts: List<CutoutBox>,
    left: Boolean,
    windowWidthPx: Int,
    buttonWidthPx: Int,
    buttonHeightPx: Int,
): CornerRoom {
    val boxLeft = if (left) 0 else windowWidthPx - buttonWidthPx
    val boxRight = if (left) buttonWidthPx else windowWidthPx
    var top = 0
    var side = 0
    cutouts
        .filter { it.left < boxRight && it.right > boxLeft && it.top < buttonHeightPx && it.bottom > 0 }
        .forEach { hole ->
            val onSideEdge = if (left) hole.left <= 0 else hole.right >= windowWidthPx
            if (onSideEdge && hole.bottom - hole.top > hole.right - hole.left) {
                side = maxOf(side, if (left) hole.right else windowWidthPx - hole.left)
            } else {
                top = maxOf(top, hole.bottom)
            }
        }
    return CornerRoom(top, side)
}

/**
 * Keeps a top-corner button of [buttonWidth] by [buttonHeight] clear of a camera hole; see
 * [cornerRoom]. [start] is the menu's corner (left, or right in a right-to-left language).
 */
@Composable
internal fun Modifier.clearOfCornerCutout(
    start: Boolean,
    buttonWidth: Dp,
    buttonHeight: Dp,
): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Read so a turn of the phone or a change of window asks again.
    val cutoutInsets = WindowInsets.displayCutout
    val insetKey =
        listOf(
            cutoutInsets.getTop(density),
            cutoutInsets.getLeft(density, direction),
            cutoutInsets.getRight(density, direction),
        )
    val left = start == (direction == LayoutDirection.Ltr)
    val room =
        remember(view, insetKey, left, buttonWidth, buttonHeight, view.width) {
            val holes =
                ViewCompat
                    .getRootWindowInsets(view)
                    ?.displayCutout
                    ?.boundingRects
                    .orEmpty()
                    .map { CutoutBox(it.left, it.top, it.right, it.bottom) }
            with(density) {
                cornerRoom(holes, left, view.rootView.width, buttonWidth.roundToPx(), buttonHeight.roundToPx())
            }
        }
    if (room == CornerRoom()) return this
    return with(density) {
        val top = room.top.toDp()
        val side = room.side.toDp()
        if (left) absolutePadding(left = side, top = top) else absolutePadding(right = side, top = top)
    }
}
