package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Logo "G" (owner pick 2026-11-21): the playing line's outline fades to sapphire only with the default accent. */
class BrandGradientUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    @After
    fun forgetTheAccent() {
        preferences.edit().remove(APP_THEME_ACCENT_PREFERENCE).commit()
    }

    private fun showPlayingLine() {
        composeRule.setContent {
            DualSubTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(12.dp)) {
                        Box(Modifier.testTag("brand_playing_line")) {
                            CompactSubtitleCard(
                                SubtitleSegment(1, 0, 2_000, "Chinese AI labs still train on American GPUs,", "Các phòng thí nghiệm AI"),
                                true,
                                1f,
                                {},
                            )
                        }
                        CompactSubtitleCard(SubtitleSegment(2, 2_000, 4_000, "despite U.S.", "bất chấp lệnh cấm của Mỹ."), false, 1f, {})
                        AndroidView(
                            factory = { ImageView(it).apply { setImageResource(R.mipmap.ic_launcher) } },
                            modifier = Modifier.padding(top = 16.dp).size(96.dp),
                        )
                    }
                }
            }
        }
    }

    /** Green of the outline near the bottom-left corner and near the top-right corner, on the straight edges. */
    private fun outlineGreens(image: ImageBitmap): Pair<Float, Float> {
        val pixels = image.toPixelMap()
        val inset = (1 * density).toInt().coerceAtLeast(1)
        val fromCorner = (16 * density).toInt()
        val bottomLeft = pixels[inset, image.height - fromCorner]
        val topRight = pixels[image.width - 1 - inset, fromCorner]
        return bottomLeft.green to topRight.green
    }

    @Test
    fun theDefaultAccentFadesToSapphireAtTheBottomLeft() {
        showPlayingLine()
        val (bottomLeft, topRight) = outlineGreens(composeRule.onNodeWithTag("brand_playing_line").captureToImage())
        saveUiEvidence("brand-playing-line-and-launcher", composeRule.onRoot().captureToImage())
        // Cyan has a lot of green; sapphire has little.
        assertTrue("Expected sapphire at the bottom left: green $bottomLeft vs $topRight", bottomLeft + 0.15f < topRight)
    }

    @Test
    fun anotherAccentStaysFlat() {
        preferences.edit().putString(APP_THEME_ACCENT_PREFERENCE, AppThemeAccentOption.AMBER.key).commit()
        showPlayingLine()
        val (bottomLeft, topRight) = outlineGreens(composeRule.onNodeWithTag("brand_playing_line").captureToImage())
        assertTrue("Expected a flat amber outline: green $bottomLeft vs $topRight", abs(bottomLeft - topRight) < 0.08f)
    }
}
