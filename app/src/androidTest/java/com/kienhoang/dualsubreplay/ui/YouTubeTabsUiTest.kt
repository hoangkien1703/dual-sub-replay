package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Menu → Tabs (N), the empty page once every tab is closed, and the account tags (owner request 2026-10-27). */
class YouTubeTabsUiTest {
    @get:Rule val compose = createComposeRule()

    private val video = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
    private val search = "https://m.youtube.com/results?search_query=japanese"

    @Before fun foldTheTabList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        studyPreferences(context).edit().remove(DRAWER_TABS_OPEN_PREFERENCE).commit()
    }

    private fun twoTabs() =
        BrowserTabs()
            .opened(video)!!
            .withActive { it.copy(title = "Learn Japanese with anime") }
            .opened(search)!!

    @Test fun tabsSitAbovePracticeAndUnfoldIntoTheList() {
        var tabs by mutableStateOf(twoTabs())
        var opened = 0
        val switched = mutableListOf<Long>()
        val actions =
            BrowserTabActions(
                switchTo = { id ->
                    switched += id
                    tabs = tabs.switchedTo(id)
                },
                close = { id -> tabs = tabs.closed(id) },
                openNew = { account ->
                    assertEquals(MAIN_TAB_ACCOUNT, account)
                    opened++
                    true
                },
            )
        compose.setContent {
            DualSubTheme {
                AppNavigation(onPractice = {}, onSettings = {}, tabs = tabs, tabActions = actions) { menu -> menu() }
            }
        }
        compose.onNodeWithTag("navigation_menu_button").performClick()
        compose.onNodeWithText("Tabs (2)").assertIsDisplayed()
        val tabsTop =
            compose
                .onNodeWithTag("open_tabs")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val practiceTop =
            compose
                .onNodeWithTag("open_saved_words")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Tabs must sit above Practice", tabsTop < practiceTop)

        compose.onNodeWithTag("open_tabs").performClick()
        compose.onNodeWithText("Learn Japanese with anime").assertIsDisplayed()
        compose.onNodeWithText("YouTube search").assertIsDisplayed()
        compose.onNodeWithTag("browser_tab_2").assertIsSelected()
        saveUiEvidence("tabs-drawer")

        compose.onNodeWithTag("close_browser_tab_2").performClick()
        compose.onNodeWithText("Tabs (1)").assertIsDisplayed()
        compose.onNodeWithTag("new_tab").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }

        compose.onNodeWithTag("navigation_menu_button").performClick()
        compose.onNodeWithTag("browser_tab_1").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), switched) }
    }

    @Test fun otherAccountsShowOnlyWhenTheSettingIsOn() {
        var accountsOn by mutableStateOf(false)
        val tabs = twoTabs().withActive { it.copy(account = 2) }
        val requested = mutableListOf<Int>()
        val actions =
            BrowserTabActions(openNew = { account ->
                requested += account
                true
            })
        compose.setContent {
            DualSubTheme {
                AppNavigation(
                    onPractice = {},
                    onSettings = {},
                    tabs = tabs,
                    differentTabAccounts = accountsOn,
                    tabActions = actions,
                ) { menu ->
                    menu()
                }
            }
        }
        compose.onNodeWithTag("navigation_menu_button").performClick()
        compose.onNodeWithTag("open_tabs").performClick()
        compose.onNodeWithTag("new_tab").assertIsDisplayed()
        // The account name is a Text inside the clickable tab row, so it is in the unmerged tree.
        compose.onNodeWithTag("browser_tab_account_2", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("new_tab_other_account").assertDoesNotExist()

        accountsOn = true
        compose.onNodeWithTag("browser_tab_account_2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Account 2").assertIsDisplayed()
        compose.onNodeWithTag("new_tab_other_account").performScrollTo().performClick()
        saveUiEvidence("tabs-other-account")
        compose.onNodeWithTag("new_tab_account_3").performClick()
        compose.runOnIdle { assertEquals(listOf(3), requested) }
    }

    @Test fun aFullListSaysSoInsteadOfOfferingANewTab() {
        val full = (1..MAX_BROWSER_TABS).fold(BrowserTabs()) { state, _ -> state.opened(video)!! }
        compose.setContent {
            DualSubTheme {
                AppNavigation(onPractice = {}, onSettings = {}, tabs = full) { menu -> menu() }
            }
        }
        compose.onNodeWithTag("navigation_menu_button").performClick()
        compose.onNodeWithText("Tabs (10)").performClick()
        compose.onNodeWithTag("new_tab").assertDoesNotExist()
        compose.onNodeWithText("Up to 10 tabs can be open. Close one to open another.").performScrollTo().assertIsDisplayed()
    }

    @Test fun everyTabClosedShowsTheEmptyPage() {
        var opened = 0
        compose.setContent {
            DualSubTheme {
                Column {
                    Text("top bar")
                    EmptyTabsPage(onOpenNewTab = { opened++ }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithText("No YouTube tabs open").assertIsDisplayed()
        compose.onNodeWithText("You closed every tab. Open a new YouTube tab to keep watching with dual subtitles.").assertIsDisplayed()
        saveUiEvidence("tabs-empty-page")
        compose.onNodeWithTag("empty_tabs_open_new").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun longPressMenuOpensTheVideoInANewTabOrHere() {
        var link by mutableStateOf<LongPressedVideoLink?>(LongPressedVideoLink(video, 40f, 80f))
        val newTabs = mutableListOf<String>()
        val here = mutableListOf<String>()
        compose.setContent {
            DualSubTheme {
                OpenInNewTabMenu(link, onOpenInNewTab = { newTabs += it }, onOpenHere = { here += it }, onDismiss = { link = null })
            }
        }
        compose.onNodeWithText("Open in new tab").assertIsDisplayed()
        saveUiEvidence("tabs-long-press-menu")
        compose.onNodeWithTag("open_in_new_tab").performClick()
        compose.runOnIdle { assertEquals(listOf(video), newTabs) }
        compose.runOnIdle { link = LongPressedVideoLink(video, 40f, 80f) }
        compose.onNodeWithTag("open_link_here").performClick()
        compose.runOnIdle { assertEquals(listOf(video), here) }
    }
}
