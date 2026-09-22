package com.kienhoang.dualsubreplay.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.round
import kotlin.math.roundToInt

internal const val PORTRAIT_PANEL_OFFSET_PREFERENCE = "portrait_panel_offset_fraction"
internal const val DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION = 0.01f
internal const val MIN_PORTRAIT_PANEL_OFFSET_FRACTION = -0.20f
internal const val MAX_PORTRAIT_PANEL_OFFSET_FRACTION = 0.20f
internal const val PORTRAIT_PANEL_OFFSET_STEPS = 39
internal const val PORTRAIT_PANEL_HEADER_HEIGHT_DP = 72f
internal const val PORTRAIT_PANEL_MIN_VIEWPORT_HEIGHT_DP = 120f

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

internal fun portraitPanelGeometry(
    availableHeightPx: Float,
    baselineHeightPx: Float,
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
    val baselineHeight =
        baselineHeightPx
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, availableHeight)
            ?: 0f
    val fullHeight = maxOf(baselineHeight, minimumHeight)
    val baselineTop = availableHeight - fullHeight
    val desiredTop =
        baselineTop +
            normalizePortraitPanelOffsetFraction(offsetFraction) * availableHeight
    val top = desiredTop.coerceIn(0f, availableHeight - minimumHeight)
    val height = if (top <= baselineTop) fullHeight else minOf(fullHeight, availableHeight - top)

    return PortraitPanelGeometry(topPx = top, heightPx = height.coerceAtLeast(0f))
}

@Composable
internal fun BoxScope.PortraitSubtitlePanelLayout(
    baselineHeightFraction: Float,
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
        val geometry =
            portraitPanelGeometry(
                availableHeightPx = availableHeightPx,
                baselineHeightPx = availableHeightPx * baselineHeightFraction,
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
) {
    val normalizedOffset = normalizePortraitPanelOffsetFraction(offsetFraction)
    Text("Portrait panel position", fontWeight = FontWeight.SemiBold)
    Text("Move the portrait transcript panel up or down. Moving it lower shows fewer subtitle rows.")
    Text(
        text = portraitPanelPositionLabel(normalizedOffset),
        modifier = Modifier.testTag("portrait_panel_position_value"),
        fontWeight = FontWeight.Medium,
    )
    Slider(
        value = normalizedOffset,
        onValueChange = { onOffsetFractionChange(normalizePortraitPanelOffsetFraction(it)) },
        valueRange = MIN_PORTRAIT_PANEL_OFFSET_FRACTION..MAX_PORTRAIT_PANEL_OFFSET_FRACTION,
        steps = PORTRAIT_PANEL_OFFSET_STEPS,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Portrait panel position" }
                .testTag("portrait_panel_position_slider"),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Higher")
        Text("Default")
        Text("Lower")
    }
    OutlinedButton(
        onClick = onReset,
        enabled = normalizedOffset != DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
        modifier = Modifier.testTag("reset_portrait_panel_position"),
    ) {
        Text("Reset position")
    }
}

internal fun portraitPanelPositionLabel(offsetFraction: Float): String {
    val normalized = normalizePortraitPanelOffsetFraction(offsetFraction)
    if (normalized == 0f) return "Position: Center"
    val percentage = (kotlin.math.abs(normalized) * 100f).roundToInt()
    return "Position: $percentage% ${if (normalized < 0f) "higher" else "lower"}"
}
