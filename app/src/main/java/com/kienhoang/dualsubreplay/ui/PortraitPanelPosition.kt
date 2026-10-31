package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

// Renamed when 0 changed from "fixed panel height" to "fitted under the video", so every
// phone starts fitted once instead of keeping an offset chosen for the old layout.
internal const val PORTRAIT_PANEL_OFFSET_PREFERENCE = "portrait_panel_fit_offset_fraction"
internal const val DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION = 0f
internal const val MIN_PORTRAIT_PANEL_OFFSET_FRACTION = -0.20f
internal const val MAX_PORTRAIT_PANEL_OFFSET_FRACTION = 0.20f
internal const val PORTRAIT_PANEL_OFFSET_STEPS = 39
internal const val PORTRAIT_PANEL_HEADER_HEIGHT_DP = 72f
internal const val PORTRAIT_PANEL_MIN_VIEWPORT_HEIGHT_DP = 120f

/** m.youtube.com's top bar above the watch page player, used until the page reports the real player. */
internal const val YOUTUBE_MOBILE_HEADER_HEIGHT_DP = 48f

internal data class PortraitPanelGeometry(
    val topPx: Float,
    val heightPx: Float,
)

internal fun normalizePortraitPanelOffsetFraction(value: Float): Float {
    if (!value.isFinite()) return DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION
    val clamped =
        value.coerceIn(
            MIN_PORTRAIT_PANEL_OFFSET_FRACTION,
            MAX_PORTRAIT_PANEL_OFFSET_FRACTION,
        )
    return (round(clamped * 100f) / 100f).let { if (it == -0f) 0f else it }
}

/**
 * Where the YouTube player ends inside the page: its bottom edge as a fraction of the page's
 * height, and the page's width / height so a reading taken in another shape (rotation, split
 * screen) is not used.
 */
internal data class PagePlayerFrame(
    val bottomFraction: Float,
    val viewportAspect: Float,
)

/**
 * Where the panel's top sits when it fits the video: right under the player the page reported,
 * or, before the page has reported one for this screen shape, under a 16:9 player below
 * YouTube's top bar.
 */
internal fun fittedPortraitPanelTopPx(
    availableWidthPx: Float,
    availableHeightPx: Float,
    playerFrame: PagePlayerFrame?,
    estimatedHeaderHeightPx: Float,
): Float {
    if (!availableHeightPx.isFinite() || availableHeightPx <= 0f) return 0f
    val width = availableWidthPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val measured =
        playerFrame?.takeIf { frame ->
            frame.bottomFraction.isFinite() && frame.bottomFraction in 0f..1f &&
                frame.viewportAspect.isFinite() && frame.viewportAspect > 0f &&
                width > 0f &&
                abs(frame.viewportAspect / (width / availableHeightPx) - 1f) <= PLAYER_FRAME_ASPECT_TOLERANCE
        }
    if (measured != null) return measured.bottomFraction * availableHeightPx
    val header = estimatedHeaderHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return (header + width * 9f / 16f).coerceIn(0f, availableHeightPx)
}

private const val PLAYER_FRAME_ASPECT_TOLERANCE = 0.05f

/**
 * The panel's bottom stays at the bottom of the screen; only its top moves. At 0 the top sits on
 * the video's bottom edge, so nothing overlaps and no gap is left. Higher (negative) makes the
 * panel taller over the bottom of the video, lower makes it shorter. Both are a share of the
 * available height, and the panel never gets shorter than [minimumPanelHeightPx].
 */
internal fun portraitPanelGeometry(
    availableHeightPx: Float,
    fittedTopPx: Float,
    offsetFraction: Float,
    minimumPanelHeightPx: Float,
): PortraitPanelGeometry {
    val availableHeight = availableHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (availableHeight == 0f) return PortraitPanelGeometry(topPx = 0f, heightPx = 0f)

    val minimumHeight =
        minimumPanelHeightPx
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, availableHeight)
            ?: 0f
    val fittedTop =
        fittedTopPx
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, availableHeight)
            ?: 0f
    val desiredTop =
        fittedTop +
            normalizePortraitPanelOffsetFraction(offsetFraction) * availableHeight
    val top = desiredTop.coerceIn(0f, availableHeight - minimumHeight)

    return PortraitPanelGeometry(topPx = top, heightPx = (availableHeight - top).coerceAtLeast(0f))
}

@Composable
internal fun BoxScope.PortraitSubtitlePanelLayout(
    playerFrame: PagePlayerFrame?,
    offsetFraction: Float,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val availableHeightPx = constraints.maxHeight.toFloat()
        val minimumPanelHeightPx =
            with(density) {
                (PORTRAIT_PANEL_HEADER_HEIGHT_DP + PORTRAIT_PANEL_MIN_VIEWPORT_HEIGHT_DP).dp.toPx()
            }
        val fittedTopPx =
            fittedPortraitPanelTopPx(
                availableWidthPx = constraints.maxWidth.toFloat(),
                availableHeightPx = availableHeightPx,
                playerFrame = playerFrame,
                estimatedHeaderHeightPx = with(density) { YOUTUBE_MOBILE_HEADER_HEIGHT_DP.dp.toPx() },
            )
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = availableHeightPx,
                fittedTopPx = fittedTopPx,
                offsetFraction = offsetFraction,
                minimumPanelHeightPx = minimumPanelHeightPx,
            )
        val panelHeight = with(density) { geometry.heightPx.toDp() }

        content(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, geometry.topPx.roundToInt()) }
                .fillMaxWidth()
                .height(panelHeight),
        )
    }
}

@Composable
internal fun PortraitPanelPositionSettings(
    offsetFraction: Float,
    onOffsetFractionChange: (Float) -> Unit,
    onReset: () -> Unit,
    peekSlider: PeekSlider? = null,
) {
    val normalizedOffset = normalizePortraitPanelOffsetFraction(offsetFraction)
    val ownInteraction = remember { MutableInteractionSource() }
    val positionLabel = portraitPanelPositionLabel(normalizedOffset)
    val positionRange = MIN_PORTRAIT_PANEL_OFFSET_FRACTION..MAX_PORTRAIT_PANEL_OFFSET_FRACTION
    val positionTitle = stringResource(R.string.settings_portrait_panel_position)
    Text(positionTitle, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.settings_portrait_panel_position_hint))
    Text(
        text = positionLabel,
        modifier = Modifier.testTag("portrait_panel_position_value"),
        fontWeight = FontWeight.Medium,
    )
    Slider(
        value = normalizedOffset,
        onValueChange = { onOffsetFractionChange(normalizePortraitPanelOffsetFraction(it)) },
        valueRange = positionRange,
        steps = PORTRAIT_PANEL_OFFSET_STEPS,
        interactionSource = peekSlider?.interaction ?: ownInteraction,
        modifier =
            Modifier
                .fillMaxWidth()
                .peekWhileHeld(peekSlider, positionLabel, normalizedOffset, positionRange, PORTRAIT_PANEL_OFFSET_STEPS)
                .tvArrowsLeave()
                .semantics { contentDescription = positionTitle }
                .testTag("portrait_panel_position_slider"),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.settings_portrait_panel_higher))
        Text(stringResource(R.string.settings_portrait_panel_default))
        Text(stringResource(R.string.settings_portrait_panel_lower))
    }
    OutlinedButton(
        onClick = onReset,
        enabled = normalizedOffset != DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
        modifier = Modifier.testTag("reset_portrait_panel_position"),
    ) {
        Text(stringResource(R.string.settings_portrait_panel_reset))
    }
}

/** The position label's string and its percentage argument (unused by the centered label). */
internal data class PortraitPanelPositionText(
    @StringRes val textRes: Int,
    val percentage: Int,
)

internal fun portraitPanelPositionText(offsetFraction: Float): PortraitPanelPositionText {
    val normalized = normalizePortraitPanelOffsetFraction(offsetFraction)
    if (normalized == 0f) return PortraitPanelPositionText(R.string.settings_portrait_panel_position_center, 0)
    val percentage = (kotlin.math.abs(normalized) * 100f).roundToInt()
    val textRes =
        if (normalized < 0f) {
            R.string.settings_portrait_panel_position_higher_percent
        } else {
            R.string.settings_portrait_panel_position_lower_percent
        }
    return PortraitPanelPositionText(textRes, percentage)
}

@Composable
internal fun portraitPanelPositionLabel(offsetFraction: Float): String {
    val text = portraitPanelPositionText(offsetFraction)
    return if (text.textRes == R.string.settings_portrait_panel_position_center) {
        stringResource(text.textRes)
    } else {
        stringResource(text.textRes, text.percentage)
    }
}
