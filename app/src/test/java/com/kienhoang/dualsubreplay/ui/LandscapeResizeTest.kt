package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeResizeTest {

    @Test fun defaultVideoFractionFavorsTheVideo() {
        assertEquals(0.75f, DEFAULT_LANDSCAPE_VIDEO_FRACTION, 0f)
    }

    @Test fun resizeFractionIsClampedToUsableBounds() {
        assertEquals(
            MIN_LANDSCAPE_VIDEO_FRACTION,
            normalizeLandscapeVideoFraction(0.40f),
            0f,
        )
        assertEquals(0.74f, normalizeLandscapeVideoFraction(0.74f), 0f)
        assertEquals(
            MAX_LANDSCAPE_VIDEO_FRACTION,
            normalizeLandscapeVideoFraction(0.95f),
            0f,
        )
    }

    @Test fun invalidFractionFallsBackToDefault() {
        assertEquals(
            DEFAULT_LANDSCAPE_VIDEO_FRACTION,
            normalizeLandscapeVideoFraction(Float.NaN),
            0f,
        )
        assertEquals(
            DEFAULT_LANDSCAPE_VIDEO_FRACTION,
            normalizeLandscapeVideoFraction(Float.POSITIVE_INFINITY),
            0f,
        )
    }
}
