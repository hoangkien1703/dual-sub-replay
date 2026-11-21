package com.kienhoang.dualsubreplay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp

/** Sapphire blue: the corner of the logo's gradient (owner pick 2026-11-21, logo "G"). */
internal val BRAND_SAPPHIRE = Color(0xFF0F52BA)

/** Where the sapphire is half blended into the accent, as a share of the gradient's reach. */
private const val SAPPHIRE_MIDPOINT = 0.45f
private const val SAPPHIRE_MIDPOINT_MIX = 0.55f

/** How far the sapphire corner reaches, as a share of the shape's longer side, unless a use says otherwise. */
internal const val BRAND_DEFAULT_REACH = 0.9f

/**
 * The sapphire corner for the accent in use. Only the default cyan accent gets the gradient, so a
 * colour the person picked in Settings stays exactly that colour (owner pick 2026-11-21).
 */
internal val LocalBrandCorner = staticCompositionLocalOf<Color?> { null }

/** The logo's colours: the accent leads, with [corner] fading in from the bottom-left corner. */
@Immutable
internal data class BrandColors(
    val accent: Color,
    val corner: Color?,
) {
    /**
     * A brush that sizes itself to what it fills: the sapphire reaches [reachFraction] of the longer side
     * from the bottom-left corner, but never more than [maxReachInHeights] times the height.
     */
    fun brush(
        reachFraction: Float = BRAND_DEFAULT_REACH,
        maxReachInHeights: Float = Float.MAX_VALUE,
    ): Brush = if (corner == null) SolidColor(accent) else BrandBrush(accent, corner, reachFraction, maxReachInHeights)
}

/** The gradient itself; see [BrandColors.brush]. */
internal data class BrandBrush(
    val accent: Color,
    val corner: Color,
    val reachFraction: Float,
    val maxReachInHeights: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        RadialGradientShader(
            center = Offset(0f, size.height),
            radius = brandReach(size, reachFraction, maxReachInHeights),
            colors = listOf(corner, lerp(corner, accent, SAPPHIRE_MIDPOINT_MIX), accent),
            colorStops = listOf(0f, SAPPHIRE_MIDPOINT, 1f),
        )
}

/** The sapphire's reach in pixels for an area of [size]; at least one pixel, so a shader can always be made. */
internal fun brandReach(
    size: Size,
    reachFraction: Float,
    maxReachInHeights: Float,
): Float = minOf(maxOf(size.width, size.height) * reachFraction, size.height * maxReachInHeights).coerceAtLeast(1f)

/** The brand colours of the theme in use. */
@Composable
internal fun brandColors(): BrandColors = BrandColors(MaterialTheme.colorScheme.primary, LocalBrandCorner.current)

/** Fills [shape] with the logo's gradient. */
internal fun Modifier.brandBackground(
    colors: BrandColors,
    shape: Shape,
    reachFraction: Float = BRAND_DEFAULT_REACH,
): Modifier = background(colors.brush(reachFraction), shape)

/** Paints what is drawn inside (an icon) with the logo's gradient instead of its own tint. */
internal fun Modifier.brandTint(colors: BrandColors): Modifier {
    if (colors.corner == null) return this
    val brush = colors.brush()
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        drawRect(brush, blendMode = BlendMode.SrcIn)
    }
}
