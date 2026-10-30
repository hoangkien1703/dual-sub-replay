package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteSwitchesTest {
    private val addresses = listOf("BATCH", "EXTENSION_BATCH", "SINGLE", "WEB_BATCH")

    private fun parse(
        rules: String,
        versionCode: Long = 50,
        schema: Int = 1,
    ) = parseRemoteSwitches("""{"schema":$schema,"rules":[$rules]}""", versionCode, addresses)

    @Test
    fun theCommittedFileChangesNothing() {
        val committed = listOf(File("remote-config/switches.json"), File("../remote-config/switches.json")).first { it.exists() }
        assertEquals(RemoteSwitches(), parseRemoteSwitches(committed.readText(), 50, addresses))
    }

    @Test
    fun aBrokenFileOrAnotherSchemaChangesNothing() {
        assertEquals(RemoteSwitches(), parseRemoteSwitches("<html>", 50, addresses))
        assertEquals(RemoteSwitches(), parse("""{"switches":{"skip_ads":"off"}}""", schema = 2))
        assertEquals(RemoteSwitches(), parseRemoteSwitches("""{"schema":1}""", 50, addresses))
    }

    @Test
    fun everySwitchIsRead() {
        val switches =
            parse(
                """{"switches":{"google_translate_addresses":["WEB_BATCH","BATCH"],"background_translate_pause_seconds":10,
                |"skip_ads":"off","skip_sponsors":"off","page_token_captions":"off","notice":"youtube_changed","notice_key":"2026-11-02"}}
                """.trimMargin(),
            )
        assertEquals(listOf("WEB_BATCH", "BATCH"), switches.googleTranslateAddresses)
        assertEquals(10_000L, switches.backgroundPauseMs)
        assertTrue(switches.skipAdsOff && switches.skipSponsorsOff && switches.pageTokenOff)
        assertEquals(RemoteNotice.YOUTUBE_CHANGED, switches.notice)
        assertEquals("2026-11-02", switches.noticeKey)
    }

    @Test
    fun rulesApplyOnlyToTheirVersionsAndLaterOnesWin() {
        val rules =
            """{"max_version_code":49,"switches":{"skip_ads":"off"}},
            |{"min_version_code":40,"switches":{"page_token_captions":"off","notice":"update_recommended"}},
            |{"min_version_code":50,"max_version_code":50,"switches":{"page_token_captions":"default"}}
            """.trimMargin()
        val old = parse(rules, versionCode = 45)
        assertTrue(old.skipAdsOff && old.pageTokenOff)
        val current = parse(rules, versionCode = 50)
        assertFalse(current.skipAdsOff)
        assertFalse(current.pageTokenOff)
        assertEquals(RemoteNotice.UPDATE_RECOMMENDED, current.notice)
        assertEquals(RemoteSwitches(), parse(rules, versionCode = 30).copy(skipAdsOff = false))
    }

    @Test
    fun unknownOrOutOfRangeValuesAreIgnored() {
        val switches =
            parse(
                """{"switches":{"google_translate_addresses":["https://evil.example/translate","BATCH","BATCH"],
                |"background_translate_pause_seconds":1,"skip_ads":"yes","notice":"run_this_script","notice_key":"<b>"}}
                """.trimMargin(),
            )
        assertEquals(listOf("BATCH"), switches.googleTranslateAddresses)
        assertNull(switches.backgroundPauseMs)
        assertFalse(switches.skipAdsOff)
        assertNull(switches.notice)
        assertEquals("", switches.noticeKey)
        assertNull(parse("""{"switches":{"google_translate_addresses":["nope"]}}""").googleTranslateAddresses)
        assertNull(parse("""{"switches":{"background_translate_pause_seconds":61}}""").backgroundPauseMs)
        assertEquals(60_000L, parse("""{"switches":{"background_translate_pause_seconds":60}}""").backgroundPauseMs)
    }

    @Test
    fun anEmptyNoticeClearsAnEarlierOne() {
        val switches = parse("""{"switches":{"notice":"youtube_changed"}},{"switches":{"notice":""}}""")
        assertNull(switches.notice)
    }

    @Test
    fun aSavedFileIsUsedForThirtyDays() {
        val day = 24L * 60 * 60 * 1000
        val saved = 1_800_000_000_000L
        assertEquals("{}", usableRemoteSwitches("{}", saved, saved + 29 * day))
        assertNull(usableRemoteSwitches("{}", saved, saved + 30 * day))
        assertNull(usableRemoteSwitches("{}", 0, saved))
        assertNull(usableRemoteSwitches("{}", saved + day, saved))
        assertNull(usableRemoteSwitches(null, saved, saved))
    }
}
