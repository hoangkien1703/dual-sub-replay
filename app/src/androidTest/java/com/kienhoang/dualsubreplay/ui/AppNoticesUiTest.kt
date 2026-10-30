package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.AppUpdate
import com.kienhoang.dualsubreplay.data.ProblemArea
import com.kienhoang.dualsubreplay.data.ProblemLog
import com.kienhoang.dualsubreplay.data.RemoteNotice
import com.kienhoang.dualsubreplay.data.RemoteSwitchState
import com.kienhoang.dualsubreplay.data.RemoteSwitches
import com.kienhoang.dualsubreplay.data.UpdateReminder
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import com.kienhoang.dualsubreplay.update.AppUpdater
import com.kienhoang.dualsubreplay.update.UPDATE_REMINDERS_PREFERENCE
import com.kienhoang.dualsubreplay.update.UpdateState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A dialog window fades in after Compose is idle; the screenshot waits for it to finish. */
private const val DIALOG_FADE_IN_MS = 700L

/** The update reminder, the security dialog, a remote notice and Report a problem (owner request 2026-10-30). */
class AppNoticesUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("dual_sub_preferences", Context.MODE_PRIVATE)

    private fun update(critical: Boolean = false) =
        AppUpdate(
            versionName = "1.9.0",
            downloadUrl = "https://github.com/hoangkien1703/dual-sub-replay/releases/download/v1.9.0/DualSub-Replay.apk",
            sha256 = "136c5c6363625a137e1b07cd4fcc6e00fd64ae23bfca427cfeff01ff038f080e",
            checksumUrl = null,
            sizeBytes = 34_251_919,
            pageUrl = "https://github.com/hoangkien1703/dual-sub-replay/releases/tag/v1.9.0",
            critical = critical,
        )

    @After
    fun forget() {
        preferences
            .edit()
            .remove(UPDATE_REMINDERS_PREFERENCE)
            .remove(REMOTE_NOTICE_CLOSED_PREFERENCE)
            .remove("update_reminder_version")
            .remove("update_reminder_shown_at")
            .remove("update_reminder_skipped")
            .remove("update_reminder_security_fix")
            .remove("update_reminder_security_since")
            .commit()
        AppUpdater.dismissReminder()
        // Leaves no found update behind for the menu's Check for updates row in other tests.
        AppUpdater.showForTest(UpdateState.Idle)
        AppUpdater.hideDialog()
        RemoteSwitchState.set(RemoteSwitches())
        ProblemLog.clear()
    }

    @Test
    fun aNewVersionShowsABannerThatCanSkipTheVersion() {
        AppUpdater.showReminderForTest(update(), UpdateReminder.BANNER)
        composeRule.setContent { DualSubTheme { AppNoticesHost() } }
        composeRule.onNodeWithText("Version 1.9.0 is available.").assertIsDisplayed()
        composeRule.onNodeWithTag("update_banner_update").assertIsDisplayed()
        composeRule.onNodeWithTag("update_banner_not_now").assertIsDisplayed()
        saveUiEvidence("update-reminder-banner")
        composeRule.runOnIdle { assertEquals("1.9.0", preferences.getString("update_reminder_version", null)) }
        composeRule.onNodeWithTag("update_banner_skip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("update_banner")).fetchSemanticsNodes().isEmpty() }
        composeRule.runOnIdle { assertEquals("1.9.0", preferences.getString("update_reminder_skipped", null)) }
    }

    @Test
    fun aSecurityUpdateAsksAtOnceAndCanBeDeclined() {
        AppUpdater.showReminderForTest(update(critical = true), UpdateReminder.SECURITY)
        composeRule.setContent { DualSubTheme { AppNoticesHost() } }
        composeRule.onNodeWithText("Security update").assertIsDisplayed()
        composeRule.onNodeWithTag("security_update_update").assertIsDisplayed()
        composeRule.onNodeWithTag("security_update_details").assertIsDisplayed()
        Thread.sleep(DIALOG_FADE_IN_MS)
        saveUiEvidence("security-update-dialog")
        composeRule.onNodeWithTag("security_update_not_now").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("security_update_message")).fetchSemanticsNodes().isEmpty() }
        composeRule.runOnIdle { assertEquals(UpdateReminder.NONE, AppUpdater.reminder.value) }
    }

    @Test
    fun aRemoteNoticeStaysClosedUntilANewOne() {
        RemoteSwitchState.set(RemoteSwitches(notice = RemoteNotice.YOUTUBE_CHANGED, noticeKey = "a"))
        composeRule.setContent { DualSubTheme { AppNoticesHost() } }
        composeRule.onNodeWithTag("remote_notice").assertIsDisplayed()
        saveUiEvidence("remote-notice")
        composeRule.onNodeWithTag("remote_notice_close").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag("remote_notice")).fetchSemanticsNodes().isEmpty() }
        composeRule.runOnIdle { RemoteSwitchState.set(RemoteSwitches(notice = RemoteNotice.YOUTUBE_CHANGED, noticeKey = "b")) }
        composeRule.onNodeWithTag("remote_notice").assertIsDisplayed()
    }

    @Test
    fun reportAProblemShowsWhatWouldBeSent() {
        ProblemLog.record(ProblemArea.GOOGLE_TRANSLATE, "translate.googleapis.com/translate_a/t: HTTP 429")
        composeRule.setContent { DualSubTheme { ReportProblemDialog(onDismiss = {}) } }
        composeRule
            .onNodeWithText(
                "Google Translate: translate.googleapis.com/translate_a/t: HTTP 429",
                substring = true,
            ).assertIsDisplayed()
        composeRule.onNodeWithTag("report_problem_open").assertIsDisplayed()
        composeRule.onNodeWithTag("report_problem_copy").assertIsDisplayed()
        Thread.sleep(DIALOG_FADE_IN_MS)
        saveUiEvidence("report-a-problem")
    }

    @Test
    fun remindersAreOnUntilTurnedOff() {
        composeRule.setContent {
            DualSubTheme {
                SubtitleSettingsDialog(
                    sourcePreference = "auto",
                    targetLanguage = "vi",
                    availableSourceLanguages = emptyList(),
                    fontScale = 1f,
                    landscapeSplitEnabled = true,
                    onSourceChange = {},
                    onTargetChange = {},
                    onFontScaleChange = {},
                    onLandscapeSplitChange = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("settings_section_updates").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(preferences.getBoolean(UPDATE_REMINDERS_PREFERENCE, true)) }
        composeRule
            .onNodeWithTag("update_reminders_switch")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertFalse(preferences.getBoolean(UPDATE_REMINDERS_PREFERENCE, true)) }
        composeRule.onNodeWithText("security updates", substring = true).performScrollTo().assertIsDisplayed()
        saveUiEvidence("app-updates-settings-reminders")
    }
}
