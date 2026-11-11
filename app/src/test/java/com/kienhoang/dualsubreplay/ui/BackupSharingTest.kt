package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.AI_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.APP_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.BackupContents
import com.kienhoang.dualsubreplay.data.DECK_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.ImmersionDay
import com.kienhoang.dualsubreplay.data.SETTING_SPECS
import com.kienhoang.dualsubreplay.data.SETUP_PAGE_URL
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SettingScope
import com.kienhoang.dualsubreplay.data.changedSettingKeys
import com.kienhoang.dualsubreplay.data.decodeSetupCode
import com.kienhoang.dualsubreplay.data.decodeUndo
import com.kienhoang.dualsubreplay.data.encodeBackup
import com.kienhoang.dualsubreplay.data.encodeSetupLink
import com.kienhoang.dualsubreplay.data.encodeUndo
import com.kienhoang.dualsubreplay.data.exportSettings
import com.kienhoang.dualsubreplay.data.importSettings
import com.kienhoang.dualsubreplay.data.parseBackup
import com.kienhoang.dualsubreplay.data.planProgressRestore
import com.kienhoang.dualsubreplay.data.planWordRestore
import com.kienhoang.dualsubreplay.data.setupCodeIn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Base64
import java.util.zip.Deflater

class BackupSharingTest {
    /**
     * Keys read or written with a string literal instead of a `*_PREFERENCE` constant. A new setting
     * should use a constant, so the scan below finds it.
     */
    private val literalKeys =
        listOf(
            "auto_pronounce",
            "font_scale",
            "landscape_split_enabled",
            "preferred_caption_language",
            "target_language",
            "onboarding_completed",
            "last_browser_url",
            "original_caption_visibility",
            "translated_caption_visibility",
        )

    /** `*_PREFERENCE` constants that name a different preference file or are not a setting. */
    private val notSettings = emptySet<String>()

    @Test
    fun everySettingIsSortedIntoShareBackupOrNever() {
        val constant = Regex("""const val (\w+_PREFERENCE)\s*=\s*"([^"]+)"""")
        val found =
            File("src/main/java")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file -> constant.findAll(file.readText()).map { it.groupValues[2] } }
                .toSet()
        assertTrue("the scan found the preference constants", found.size > 80)
        val missing =
            (found + RESETTABLE_SETTING_KEYS + literalKeys + SearchableSetting.entries.flatMap { it.keys })
                .filter { it !in SETTING_SPECS && it !in notSettings }
                .sorted()
        assertEquals(
            "Add each new setting to SETTING_SPECS in data/SettingsTransfer.kt (share, backup or never)",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun aSharedSetupNeverCarriesAiOrPersonalSettings() {
        val shared = SETTING_SPECS.filterValues { it.scope == SettingScope.SHARE }.keys
        val aiKeys = SETTING_SPECS.keys.filter { it.startsWith("ai_") || it == "meaning_in_context" }
        assertTrue(aiKeys.size >= 10)
        aiKeys.forEach { assertFalse("$it must not be shared", it in shared) }
        listOf("target_language", "send_to_app", "ankiconnect_deck", "daily_goal_minutes", "app_language").forEach {
            assertFalse("$it must not be shared", it in shared)
        }
    }

    @Test
    fun theAiAddressAndFullAccessNeverLeaveThePhone() {
        listOf("ai_custom_base_url", "ai_full_access", "browser_tabs", "app_language", "guide_completed").forEach {
            assertEquals(it, SettingScope.NEVER, SETTING_SPECS.getValue(it).scope)
        }
        val stored = mapOf("ai_custom_base_url" to "https://evil.example/v1", "ai_full_access" to true, "ai_provider" to "gemini")
        assertEquals(mapOf("ai_provider" to "gemini"), exportSettings(AI_PREFERENCES_FILE, stored, shared = false))
        val planted = JSONObject().put("ai_custom_base_url", "https://evil.example/v1").put("ai_full_access", true)
        assertTrue(importSettings(AI_PREFERENCES_FILE, planted, shared = false).isEmpty())
    }

    @Test
    fun exportKeepsOnlyKnownSettingsOfTheRightType() {
        val stored =
            mapOf(
                "font_scale" to 1.25f,
                "target_language" to "vi",
                "skip_ads" to true,
                "study_repeat_count" to 3,
                "app_update_last_check" to 1_700_000_000_000L,
                "some_future_setting" to "x",
                "caption_format" to 5,
            )
        assertEquals(
            mapOf("font_scale" to 1.25f, "skip_ads" to true, "study_repeat_count" to 3),
            exportSettings(APP_PREFERENCES_FILE, stored, shared = true),
        )
        assertEquals(
            mapOf("font_scale" to 1.25f, "skip_ads" to true, "study_repeat_count" to 3, "target_language" to "vi"),
            exportSettings(APP_PREFERENCES_FILE, stored, shared = false),
        )
    }

    @Test
    fun importRejectsWrongTypesAndOutOfRangeValues() {
        val json =
            JSONObject()
                .put("skip_ads", "yes")
                .put("font_scale", 1e9)
                .put("study_repeat_count", 2.5)
                .put("known_words_after", 3)
                .put("caption_format", "whole_sentence")
                .put("unknown", true)
                .put("subtitle_original_color", "x".repeat(5_000))
        assertEquals(
            mapOf("known_words_after" to 3, "caption_format" to "whole_sentence"),
            importSettings(APP_PREFERENCES_FILE, json, shared = true),
        )
    }

    @Test
    fun deckAndModelKeysTravelOnlyInBackups() {
        assertEquals(
            mapOf("deck_ja" to "Anime"),
            exportSettings(DECK_PREFERENCES_FILE, mapOf("deck_ja" to "Anime", "deck_../x" to "y", "other" to "z"), shared = false),
        )
        assertTrue(exportSettings(DECK_PREFERENCES_FILE, mapOf("deck_ja" to "Anime"), shared = true).isEmpty())
        assertEquals(
            mapOf("ai_model_gemini" to "gemini-flash"),
            exportSettings(AI_PREFERENCES_FILE, mapOf("ai_model_gemini" to "gemini-flash"), shared = false),
        )
    }

    private fun word(
        id: String,
        intervalMs: Long = 0,
        dueAt: Long = 0,
    ) = SavedWord(id, "word$id", null, "ja", "vi", "meaning", "sentence $id", null, null, 0, 0, false, false, dueAt, intervalMs)

    @Test
    fun aBackupRoundTripsEveryPart() {
        val contents =
            BackupContents(
                createdAtMs = 1_762_800_000_000,
                appVersion = "1.8.33",
                settings =
                    mapOf(
                        APP_PREFERENCES_FILE to mapOf("font_scale" to 1.5f, "target_language" to "vi", "skip_ads" to false),
                        AI_PREFERENCES_FILE to mapOf("ai_provider" to "gemini"),
                        DECK_PREFERENCES_FILE to mapOf("deck_ja" to "Anime"),
                    ),
                words = listOf(word("a", intervalMs = 86_400_000, dueAt = 5), word("b")),
                knownWords = mapOf("ja" to setOf("食べる", "見る")),
                stories = mapOf("ja" to mapOf("日" to "The sun.")),
                progress = listOf(ImmersionDay(LocalDate.of(2026, 11, 10), "ja", 1_800_000, 2)),
            )
        assertEquals(contents, parseBackup(encodeBackup(contents)))
    }

    @Test
    fun aBackupWithOnlyProgressHasNoWordsOrSettings() {
        val contents =
            BackupContents(
                1L,
                "1.0",
                settings = null,
                words = null,
                progress = listOf(ImmersionDay(LocalDate.of(2026, 1, 1), "en", 60_000, 1)),
            )
        val read = parseBackup(encodeBackup(contents))
        assertNull(read.settings)
        assertNull(read.words)
        assertEquals(contents.progress, read.progress)
    }

    @Test
    fun otherFilesAndNewerBackupsAreRefused() {
        for (text in listOf("""{"format":"DualSubReplay","version":1,"words":[]}""", """{"format":"DualSubReplayBackup","version":2}""")) {
            try {
                parseBackup(text)
                fail("accepted $text")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aBackupDropsBadProgressRowsAndStories() {
        val text =
            """
            {"format":"DualSubReplayBackup","version":1,
             "progress":[{"day":"2026-11-10","language":"ja","ms":60000,"videos":1},
                         {"day":"nope","language":"ja","ms":1},
                         {"day":"2026-11-09","language":"ja","ms":999999999999},
                         {"day":"2026-11-08","language":"../x","ms":1}],
             "stories":{"ja":{"日":"ok","two":"no","月":""},"xx":{"a":"b"}}}
            """.trimIndent()
        val read = parseBackup(text)
        assertEquals(listOf(ImmersionDay(LocalDate.of(2026, 11, 10), "ja", 60_000, 1)), read.progress)
        assertEquals(mapOf("ja" to mapOf("日" to "ok")), read.stories)
    }

    @Test
    fun restoringWordsAddsNewOnesAndKeepsTheNewerReview() {
        val existing = listOf(word("a", intervalMs = 10), word("b", intervalMs = 50))
        val incoming = listOf(word("a", intervalMs = 20), word("b", intervalMs = 5), word("c"), word("c"))
        val plan = planWordRestore(existing, incoming)
        assertEquals(listOf("c", "a"), plan.words.map { it.id })
        assertEquals(1, plan.added)
        assertEquals(2, plan.alreadySaved)
    }

    @Test
    fun restoringProgressCountsNewDaysAndNeverAddsTimeTwice() {
        val day1 = LocalDate.of(2026, 11, 9)
        val day2 = LocalDate.of(2026, 11, 10)
        val existing = listOf(ImmersionDay(day1, "ja", 100, 1))
        val incoming = listOf(ImmersionDay(day1, "ja", 50, 1), ImmersionDay(day2, "ja", 70, 1), ImmersionDay(day2, "en", 10, 1))
        val plan = planProgressRestore(existing, incoming)
        assertEquals(1, plan.newDays)
        assertEquals(3, plan.days.size)
    }

    @Test
    fun aSetupLinkCarriesOnlySharedSettings() {
        val link = encodeSetupLink(mapOf("font_scale" to 1.25f, "skip_ads" to false, "target_language" to "vi", "ai_enabled" to true))
        assertTrue(link.startsWith("$SETUP_PAGE_URL#1."))
        assertTrue("a link stays short enough for chat apps", link.length < 2_000)
        val code = setupCodeIn("My Dual Sub Replay setup: $link")!!
        assertEquals(mapOf("font_scale" to 1.25f, "skip_ads" to false), decodeSetupCode(code))
        assertEquals(code, setupCodeIn("dualsubreplay://setup?d=$code"))
        assertEquals(code, setupCodeIn(code))
    }

    @Test
    fun aHandMadeLinkCannotSetPrivateOrWrongValues() {
        val json =
            """{"v":1,"s":{"ai_enabled":true,"ai_custom_base_url":"https://evil.example",""" +
                """"send_to_app":"evil.app","skip_ads":"no","font_scale":1.1}}"""
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val buffer = ByteArray(1024)
        val size = deflater.deflate(buffer)
        deflater.end()
        val code = "1." + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(size))
        assertEquals(mapOf("font_scale" to 1.1f), decodeSetupCode(code))
    }

    @Test
    fun otherTextIsNotASetupLink() {
        assertNull(setupCodeIn("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(setupCodeIn("hello"))
        assertNull(setupCodeIn("$SETUP_PAGE_URL#2.abcdefghijk"))
        try {
            decodeSetupCode("1.AAAAAAAAAAAA")
            fail("a broken code was accepted")
        } catch (_: Exception) {
        }
    }

    @Test
    fun changedKeysAndUndoPutBackWhatWasThere() {
        val current = mapOf("font_scale" to 1.0f, "skip_ads" to true)
        val incoming = mapOf("font_scale" to 1.5f, "skip_ads" to true, "caption_format" to "whole_sentence")
        val changed = changedSettingKeys(current, incoming)
        assertEquals(listOf("font_scale", "caption_format"), changed)
        val undo = decodeUndo(encodeUndo(changed.associateWith { current[it] }))
        assertEquals(mapOf("font_scale" to 1.0f, "caption_format" to null), undo)
        assertNull(decodeUndo(null))
    }
}
