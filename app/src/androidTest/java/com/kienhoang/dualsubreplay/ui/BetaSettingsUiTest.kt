package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.BILIBILI_HOME_URL
import com.kienhoang.dualsubreplay.data.BILIBILI_TV_HOME_URL
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Settings → Beta, the New tab menu and the Bilibili no-subtitles card (owner picks 2026-11-17). */
class BetaSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private val preferences get() = studyPreferences(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun startWithBetaOff() {
        closeSystemDialogs()
        clear()
    }

    @After
    fun clear() {
        preferences
            .edit()
            .remove(BETA_BILIBILI_PREFERENCE)
            .remove(BETA_BILIBILI_TV_PREFERENCE)
            .remove("beta_bilibili_opened")
            .remove("beta_bilibili_tv_opened")
            .remove(DRAWER_TABS_OPEN_PREFERENCE)
            .commit()
        BetaSites.set(emptySet())
    }

    @Test
    fun betaHoldsBothBilibiliSwitchesOffAndTheTvLayout() {
        compose.setContent {
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
                    jumpToSection = MoreSettingsSection.BETA,
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("beta_bilibili_switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("beta_bilibili_tv_switch").performScrollTo().assertIsOff()
        compose.onNodeWithText("TV layout (beta)").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("beta_bilibili_switch").performScrollTo().performClick()
        compose.onNodeWithTag("beta_bilibili_switch").assertIsOn()
        compose.runOnIdle { assertTrue(preferences.getBoolean(BETA_BILIBILI_PREFERENCE, false)) }
        compose.onNodeWithTag("beta_bilibili_switch").performScrollTo()
        saveUiEvidence("settings-beta")
    }

    @Test
    fun aSiteOpensByItselfOnlyTheFirstTimeItsSwitchGoesOn() {
        val opened = mutableListOf<String>()
        compose.setContent { OpenBetaSiteWhenFirstOn({ opened.add(it) }) }
        compose.runOnIdle { preferences.edit().putBoolean(BETA_BILIBILI_TV_PREFERENCE, true).commit() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(BILIBILI_TV_HOME_URL), opened)
            assertTrue(BetaSites.isOn(com.kienhoang.dualsubreplay.data.VideoSite.BILIBILI_TV))
            preferences.edit().putBoolean(BETA_BILIBILI_TV_PREFERENCE, false).commit()
            preferences.edit().putBoolean(BETA_BILIBILI_TV_PREFERENCE, true).commit()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(BILIBILI_TV_HOME_URL), opened) }
    }

    @Test
    fun newTabOffersYouTubeWithEachAccountAndTheBilibiliSitesThatAreOn() {
        preferences
            .edit()
            .putBoolean(BETA_BILIBILI_PREFERENCE, true)
            .putBoolean(BETA_BILIBILI_TV_PREFERENCE, true)
            .commit()
        val sites = mutableListOf<String>()
        val accounts = mutableListOf<Int>()
        val actions =
            BrowserTabActions(
                openNew = { account ->
                    accounts += account
                    true
                },
                openSite = { url ->
                    sites += url
                    true
                },
            )
        compose.setContent {
            DualSubTheme {
                AppNavigation(
                    onPractice = {},
                    onSettings = {},
                    tabs = BrowserTabs().opened("https://m.youtube.com/")!!,
                    differentTabAccounts = true,
                    tabActions = actions,
                ) { menu -> menu() }
            }
        }
        compose.onNodeWithTag("navigation_menu_button").performClick()
        compose.onNodeWithTag("open_tabs").performClick()
        // The menu holds the accounts, so the separate account row is gone.
        compose.onNodeWithTag("new_tab_other_account").assertDoesNotExist()
        compose.onNodeWithTag("new_tab").performScrollTo().performClick()
        compose.onNodeWithTag("new_tab_youtube_1").assertIsDisplayed()
        compose.onNodeWithTag("new_tab_youtube_3").assertIsDisplayed()
        compose.onNodeWithTag("new_tab_site_bilibili_tv").assertIsDisplayed()
        saveUiEvidence("new-tab-chooser")
        compose.onNodeWithTag("new_tab_site_bilibili").performClick()
        compose.runOnIdle { assertEquals(listOf(BILIBILI_HOME_URL), sites) }

        compose.onNodeWithTag("navigation_menu_button").performClick()
        compose.onNodeWithTag("new_tab").performScrollTo().performClick()
        compose.onNodeWithTag("new_tab_youtube_2").performClick()
        compose.runOnIdle { assertEquals(listOf(2), accounts) }
    }

    @Test
    fun aSignedOutBilibiliVideoAsksForASignIn() {
        var retried = 0
        compose.setContent {
            DualSubTheme {
                Box(Modifier.padding(16.dp)) { BilibiliNoSubtitlesCard(signInNeeded = true) { retried++ } }
            }
        }
        compose.onNodeWithText("Sign in to Bilibili to load its subtitles").assertIsDisplayed()
        saveUiEvidence("bilibili-sign-in-card")
        compose.onNodeWithTag("bilibili_try_again").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }
}
