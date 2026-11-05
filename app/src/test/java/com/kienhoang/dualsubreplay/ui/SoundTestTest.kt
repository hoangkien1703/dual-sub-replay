package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundTestTest {
    @Test
    fun anUnknownOrMissingValueIsNormal() {
        assertEquals(SoundTestStage.NORMAL, storedSoundTestStage(null))
        assertEquals(SoundTestStage.NORMAL, storedSoundTestStage("loud"))
        SoundTestStage.entries.forEach { assertEquals(it, storedSoundTestStage(it.storageValue)) }
    }

    @Test
    fun normalTurnsNothingOff() {
        val normal = SoundTestStage.NORMAL
        assertFalse(normal.oldFullScreen || normal.noNotification || normal.slowReading || normal.noScripts || normal.plainPage)
    }

    @Test
    fun eachStageKeepsTheOnesBeforeIt() {
        val plain = SoundTestStage.PLAIN_PAGE
        assertTrue(plain.oldFullScreen && plain.noNotification && plain.slowReading && plain.noScripts && plain.plainPage)
        val slow = SoundTestStage.SLOW_READING
        assertTrue(slow.oldFullScreen && slow.noNotification && slow.slowReading)
        assertFalse(slow.noScripts || slow.plainPage)
        assertTrue(SoundTestStage.OLD_FULL_SCREEN.oldFullScreen)
        assertFalse(SoundTestStage.OLD_FULL_SCREEN.noNotification)
    }
}
