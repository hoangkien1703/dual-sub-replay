package com.kienhoang.dualsubreplay.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoArtworkTest {
    private val black = 0xFF000000.toInt()
    private val nearBlack = 0xFF141210.toInt()
    private val sky = 0xFF3A7BD5.toInt()

    /** A 480x360 picture: [bar] rows of [barColor] above and below, [frame] in between. */
    private fun picture(
        bar: Int,
        barColor: Int = black,
        frame: Int = sky,
    ): IntArray =
        IntArray(480 * 360) { index ->
            val row = index / 480
            if (row < bar || row >= 360 - bar) barColor else frame
        }

    @Test
    fun artworkIsYouTubesPictureOfAValidVideoOnly() {
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", videoArtworkUrl("dQw4w9WgXcQ"))
        assertNull(videoArtworkUrl(""))
        assertNull(videoArtworkUrl("dQw4w9WgXc"))
        assertNull(videoArtworkUrl("../../evil/x"))
        assertNull(videoArtworkUrl("dQw4w9WgXcQ?a"))
    }

    @Test
    fun cutsTheBlackBarsAroundA16By9Frame() {
        assertEquals(45, letterboxBarRows(480, 360, picture(bar = 45)))
        // JPEG bars are not exactly black.
        assertEquals(45, letterboxBarRows(480, 360, picture(bar = 45, barColor = nearBlack)))
    }

    @Test
    fun keepsPicturesThatFillTheFrame() {
        // A 4:3 video or a Short fills the whole picture.
        assertEquals(0, letterboxBarRows(480, 360, picture(bar = 0)))
        // Only the top is dark (a night sky), not a bar.
        val darkTop = picture(bar = 0).also { pixels -> for (i in 0 until 480 * 45) pixels[i] = black }
        assertEquals(0, letterboxBarRows(480, 360, darkTop))
        // Already 16:9, or a broken picture.
        assertEquals(0, letterboxBarRows(320, 180, IntArray(320 * 180) { black }))
        assertEquals(0, letterboxBarRows(480, 360, IntArray(10)))
        assertEquals(0, letterboxBarRows(0, 0, IntArray(0)))
    }
}
