package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.APP_PREFERENCES_FILE
import com.kienhoang.dualsubreplay.data.BackupContents
import com.kienhoang.dualsubreplay.data.ImmersionDay
import com.kienhoang.dualsubreplay.data.ProgressRestore
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.WordRestore
import com.kienhoang.dualsubreplay.data.encodeSetupLink
import com.kienhoang.dualsubreplay.data.setupCodeIn
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Settings → Backup & sharing and its dialogs (owner pick 2026-11-10). */
class BackupSharingUiTest {
    @get:Rule val compose = createComposeRule()

    @After fun clearPendingSetup() {
        SetupLinkInbox.pendingCode = null
    }

    private fun showSection() {
        compose.setContent {
            DualSubTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp)) { BackupSharingSettingsSection() }
                }
            }
        }
    }

    @Test fun theSectionOffersBackupAndSharing() {
        showSection()
        compose.onNodeWithText("Backup").assertIsDisplayed()
        compose.onNodeWithTag("backup_primary").assertIsDisplayed()
        compose.onNodeWithTag("backup_secondary").assertIsDisplayed()
        compose.onNodeWithText("Share my setup").assertIsDisplayed()
        compose.onNodeWithTag("share_setup_primary").assertIsDisplayed()
        compose.onNodeWithTag("share_setup_secondary").assertIsDisplayed()
        saveUiEvidence("backup_sharing_section")
    }

    @Test fun backUpAsksWhatGoesInTheFile() {
        showSection()
        compose.onNodeWithTag("backup_primary").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("backup_save")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("backup_part_settings").assertIsDisplayed()
        compose.onNodeWithTag("backup_part_words").assertIsDisplayed()
        compose.onNodeWithTag("backup_part_progress").assertIsDisplayed()
        compose.onNodeWithText("Never in the file", substring = true).assertIsDisplayed()
        saveUiEvidence("backup_sharing_choices")
    }

    @Test fun aPastedLinkThatIsNotASetupSaysSo() {
        showSection()
        compose.onNodeWithTag("share_setup_secondary").performClick()
        compose.onNodeWithTag("paste_setup_field").performTextInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        compose.onNodeWithTag("paste_setup_continue").performClick()
        compose.onNodeWithText("This isn't a setup link", substring = true).assertIsDisplayed()
        saveUiEvidence("backup_sharing_paste_invalid")
    }

    @Test fun aSharedSetupListsWhatItChangesAndCancelKeepsEverything() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context
            .getSharedPreferences(APP_PREFERENCES_FILE, 0)
            .edit()
            .putFloat("font_scale", 1f)
            .remove("caption_format")
            .remove("app_color_theme")
            .putBoolean("skip_ads", true)
            .commit()
        val link =
            encodeSetupLink(
                mapOf("font_scale" to 1.4f, "caption_format" to "whole_sentence", "skip_ads" to true, "app_color_theme" to "black"),
            )
        SetupLinkInbox.pendingCode = setupCodeIn(link)
        compose.setContent { DualSubTheme { PendingSetupDialog() } }
        compose.onNodeWithText("Use this setup?").assertIsDisplayed()
        compose.onNodeWithText("3 settings will change:").assertIsDisplayed()
        compose.onNodeWithTag("use_setup_confirm").assertIsDisplayed()
        saveUiEvidence("backup_sharing_use_setup")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertNull(SetupLinkInbox.pendingCode)
    }

    @Test fun restoreShowsWhatTheBackupAdds() {
        val words = List(3) { SavedWord("w$it", "word$it", null, "ja", "vi", "m", "s", null, null, 0, 0, false, false) }
        val plan =
            RestorePlan(
                BackupContents(
                    createdAtMs = 1_762_819_200_000,
                    appVersion = "1.8.33",
                    settings = mapOf(APP_PREFERENCES_FILE to mapOf("font_scale" to 1.2f)),
                    words = words,
                    progress = listOf(ImmersionDay(LocalDate.of(2026, 11, 10), "ja", 3_600_000, 2)),
                ),
                WordRestore(words, added = 3, alreadySaved = 0),
                ProgressRestore(emptyList(), newDays = 61),
            )
        compose.setContent {
            DualSubTheme { RestoreDialog(plan, BackupParts(), onPartsChange = {}, onDismiss = {}, onRestore = {}) }
        }
        compose.onNodeWithText("Restore this backup?").assertIsDisplayed()
        compose.onNodeWithText("Adds 3 new words", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Adds 61 study days", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("restore_confirm").assertIsDisplayed()
        saveUiEvidence("backup_sharing_restore")
    }
}
