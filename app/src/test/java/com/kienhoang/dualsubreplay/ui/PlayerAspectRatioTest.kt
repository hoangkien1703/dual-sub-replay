package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAspectRatioTest {
    @Test fun exposesEverySupportedVideoShape() {
        assertEquals(
            listOf("16:9", "4:3", "3:4", "1:1"),
            PlayerAspectRatio.entries.map(PlayerAspectRatio::label),
        )
    }

    @Test fun convertsShapesToHeightOverWidthValues() {
        assertEquals(9f / 16f, PlayerAspectRatio.WIDE_16_9.heightOverWidth, 0.0001f)
        assertEquals(3f / 4f, PlayerAspectRatio.CLASSIC_4_3.heightOverWidth, 0.0001f)
        assertEquals(4f / 3f, PlayerAspectRatio.PORTRAIT_3_4.heightOverWidth, 0.0001f)
        assertEquals(1f, PlayerAspectRatio.SQUARE_1_1.heightOverWidth, 0.0001f)
    }
}
