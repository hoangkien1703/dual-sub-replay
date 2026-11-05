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

class SoundTestLogTest {
    @Test
    fun theSameLineInARowOnlyCountsUp() {
        SoundTestLog.clear()
        SoundTestLog.app("page transcript read")
        SoundTestLog.app("page transcript read")
        SoundTestLog.add("page: waiting at 12.00")
        val lines = SoundTestLog.lines.value
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("page: waiting at 12.00"))
        assertTrue(lines[1].endsWith("app: page transcript read (x2)"))
        SoundTestLog.clear()
    }

    @Test
    fun pageEventsKeepTheirOwnTime() {
        SoundTestLog.clear()
        addSoundLogReply("""[[0,"page: seeking at 5.00 (was 9.00)"],[1000,"page: seeked at 5.00"]]""")
        assertEquals(2, SoundTestLog.lines.value.size)
        assertTrue(SoundTestLog.lines.value[0].endsWith("page: seeked at 5.00"))
        SoundTestLog.clear()
    }
}
