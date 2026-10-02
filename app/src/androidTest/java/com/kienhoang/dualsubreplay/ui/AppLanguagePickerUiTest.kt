package com.kienhoang.dualsubreplay.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AppLanguagePickerUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun drawerOffersEveryLanguageAndFollowsTheDeviceByDefault() {
        var chosen: AppLanguageOption? = null
        compose.setContent {
            DualSubTheme { AppLanguageButton(onSelect = { chosen = it }) }
        }
        compose.onNodeWithText("Device language").assertIsDisplayed()
        compose.onNodeWithTag("app_language_button").performClick()
        compose.onNodeWithText("App language").assertIsDisplayed()
        compose.onNodeWithTag("app_language_option_device").assertIsSelected()
        APP_LANGUAGES.forEach { compose.onNodeWithTag("app_language_option_${it.tag}").performScrollTo().assertIsDisplayed() }
        saveUiEvidence("app-language-picker")
        compose.onNodeWithTag("app_language_option_vi").performClick()
        compose.runOnIdle { assertEquals("vi", chosen?.tag) }
    }

    @Test
    fun drawerShowsTheChosenLanguage() {
        compose.setContent {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag("vi"))) }
            val vietnamese = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides vietnamese,
                LocalResources provides vietnamese.resources,
                LocalConfiguration provides configuration,
            ) {
                DualSubTheme {
                    AppNavigation(onPractice = {}, onSettings = {}) { menu -> menu() }
                }
            }
        }
        compose.onNodeWithContentDescription("Mở menu điều hướng").performClick()
        compose.onNodeWithText("Luyện tập").assertIsDisplayed()
        compose.onNodeWithText("Ngôn ngữ thiết bị").performScrollTo().assertIsDisplayed()
        saveUiEvidence("navigation-drawer-vietnamese")
    }
}
