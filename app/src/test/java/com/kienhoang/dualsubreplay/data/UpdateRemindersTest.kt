package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRemindersTest {
    private val digest = "136c5c6363625a137e1b07cd4fcc6e00fd64ae23bfca427cfeff01ff038f080e"
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun info(
        version: String = "1.9.0",
        code: Long = 60,
        minimumSafe: Long = 0,
        schema: Int = 1,
        sha256: String = digest,
    ) = """{"schema":$schema,"version":"$version","version_code":$code,"minimum_safe_version_code":$minimumSafe,
        |"apk":"DualSub-Replay.apk","sha256":"$sha256","size":34251919}
        """.trimMargin()

    private fun update(critical: Boolean = false) =
        AppUpdate("1.9.0", "https://github.com/x.apk", digest, null, 1, "https://github.com/x", critical)

    @Test
    fun updateInfoNamesTheApkTheChecksumAndTheReleasePage() {
        val parsed = parseUpdateInfo(info(minimumSafe = 55))!!
        assertEquals(UpdateInfo("1.9.0", 60, 55, digest, 34251919), parsed)
        val update = updateFromInfo(parsed, installedVersionCode = 57)!!
        assertEquals("https://github.com/hoangkien1703/dual-sub-replay/releases/download/v1.9.0/DualSub-Replay.apk", update.downloadUrl)
        assertEquals("https://github.com/hoangkien1703/dual-sub-replay/releases/tag/v1.9.0", update.pageUrl)
        assertEquals(digest, update.sha256)
        assertFalse(update.critical)
    }

    @Test
    fun anAppOlderThanTheLatestSecurityFixIsCritical() {
        val parsed = parseUpdateInfo(info(minimumSafe = 55))!!
        assertTrue(updateFromInfo(parsed, installedVersionCode = 54)!!.critical)
        // The latest release is ordinary, but this app also misses the earlier security release.
        assertTrue(updateFromInfo(parseUpdateInfo(info(code = 70, minimumSafe = 55))!!, 50)!!.critical)
        assertNull(updateFromInfo(parsed, installedVersionCode = 60))
        assertNull(updateFromInfo(parsed, installedVersionCode = 61))
    }

    @Test
    fun aBrokenOrUnknownFileIsIgnored() {
        assertNull(parseUpdateInfo("not json"))
        assertNull(parseUpdateInfo(info(schema = 2)))
        assertNull(parseUpdateInfo(info(sha256 = "abc")))
        assertNull(parseUpdateInfo(info(version = "1.9.0/../../evil")))
        assertNull(parseUpdateInfo(info(version = "1.9.0-preview")))
        assertNull(parseUpdateInfo(info(code = 0)))
        assertNull(parseUpdateInfo(info().replace("DualSub-Replay.apk", "Other.apk")))
        assertEquals(0L, parseUpdateInfo(info(minimumSafe = -4))!!.minimumSafeVersionCode)
    }

    @Test
    fun theBannerShowsAtMostOnceAWeekWhateverTheVersion() {
        assertEquals(UpdateReminder.BANNER, updateReminder(update(), ReminderMemory(), now))
        val shown = ReminderMemory(shownVersion = "1.9.0", shownAtMs = now)
        assertEquals(UpdateReminder.NONE, updateReminder(update(), shown, now + day))
        assertEquals(UpdateReminder.NONE, updateReminder(update(), shown, now + 7 * day - 1))
        assertEquals(UpdateReminder.BANNER, updateReminder(update(), shown, now + 7 * day))
        // A release every day still brings at most one banner a week (owner request 2026-10-30).
        val yesterday = shown.copy(shownVersion = "1.8.9")
        assertEquals(UpdateReminder.NONE, updateReminder(update(), yesterday, now + day))
        assertEquals(UpdateReminder.BANNER, updateReminder(update(), yesterday, now + 7 * day))
        assertEquals(UpdateReminder.NONE, updateReminder(null, ReminderMemory(), now))
    }

    @Test
    fun skipHidesOnlyThatVersionAndNeverASecurityUpdate() {
        val skipped = ReminderMemory(shownVersion = "1.9.0", shownAtMs = now, skippedVersion = "1.9.0")
        assertEquals(UpdateReminder.NONE, updateReminder(update(), skipped, now + 30 * day))
        assertEquals(UpdateReminder.BANNER, updateReminder(update(), skipped.copy(skippedVersion = "1.8.0"), now + 30 * day))
        assertEquals(UpdateReminder.SECURITY, updateReminder(update(critical = true), skipped, now + day))
    }

    @Test
    fun aSecurityUpdateShowsAtOnceAndAgainTheNextDay() {
        assertEquals(UpdateReminder.SECURITY, updateReminder(update(critical = true), ReminderMemory(), now))
        // A banner shown today does not hold back a security update.
        val banner = ReminderMemory(shownVersion = "1.8.9", shownAtMs = now)
        assertEquals(UpdateReminder.SECURITY, updateReminder(update(critical = true), banner, now + 1))
        val shown = ReminderMemory(shownVersion = "1.9.0", shownAtMs = now)
        assertEquals(UpdateReminder.NONE, updateReminder(update(critical = true), shown, now + day - 1))
        assertEquals(UpdateReminder.SECURITY, updateReminder(update(critical = true), shown, now + day))
    }

    @Test
    fun aClockSetBackDoesNotHideTheReminder() {
        val shown = ReminderMemory(shownVersion = "1.9.0", shownAtMs = now)
        assertEquals(UpdateReminder.BANNER, updateReminder(update(), shown, now - day))
    }

    @Test
    fun noReminderCoversAPlayingVideo() {
        assertFalse(reminderVisible(UpdateReminder.SECURITY, videoPlaying = true))
        assertFalse(reminderVisible(UpdateReminder.BANNER, videoPlaying = true))
        assertTrue(reminderVisible(UpdateReminder.BANNER, videoPlaying = false))
        assertFalse(reminderVisible(UpdateReminder.NONE, videoPlaying = false))
    }
}
