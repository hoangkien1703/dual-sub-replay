package com.kienhoang.dualsubreplay.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenRotationTest {
    @Test
    fun wideOrUnknownVideosTurnSideways() {
        assertFalse(fullscreenVideoIsTall("\"1920x1080\""))
        assertFalse(fullscreenVideoIsTall("\"\""))
        assertFalse(fullscreenVideoIsTall(null))
        assertFalse(fullscreenVideoIsTall("null"))
    }

    @Test
    fun uprightVideosStayUpright() {
        assertTrue(fullscreenVideoIsTall("\"1080x1920\""))
        assertTrue(fullscreenVideoIsTall("720x1280"))
    }

    @Test
    fun phoneCountsAsUprightOnlyNearZeroDegrees() {
        assertTrue(phoneHeldUpright(0))
        assertTrue(phoneHeldUpright(20))
        assertTrue(phoneHeldUpright(345))
        assertFalse(phoneHeldUpright(90))
        assertFalse(phoneHeldUpright(270))
        assertFalse(phoneHeldUpright(-1))
    }

    @Test
    fun onlyAnActivityThatHandlesRotationItselfIsTurned() {
        val app = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_KEYBOARD
        assertTrue(rotationKeepsActivity(app))
        assertFalse(rotationKeepsActivity(0))
        assertFalse(rotationKeepsActivity(ActivityInfo.CONFIG_ORIENTATION))
    }

    @Test
    fun fullscreenInsetsAreMeasuredAgainOnlyWhenTheVideoChangesSize() {
        // Turning the screen: the Dialog goes from portrait to landscape size.
        assertTrue(fullscreenViewResized(2400, 1080, 1080, 2400))
        // First layout after the Dialog opens.
        assertTrue(fullscreenViewResized(2400, 1080, 0, 0))
        // Ordinary layout passes (YouTube's menu, the subtitle box) keep the size.
        assertFalse(fullscreenViewResized(2400, 1080, 2400, 1080))
    }
}
