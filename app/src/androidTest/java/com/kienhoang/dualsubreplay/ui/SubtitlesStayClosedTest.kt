package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.content.res.Configuration
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/** Closing the subtitles in fullscreen keeps them closed after fullscreen and on the next video, until the bubble is tapped. */
class SubtitlesStayClosedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun subtitlesClosedInFullscreenStayClosedUntilTheBubbleIsTapped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val saved = preferences.all
        preferences
            .edit()
            .putBoolean("onboarding_completed", true)
            .putBoolean(GUIDE_COMPLETED_PREFERENCE, true)
            .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false)
            .putBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, true)
            .putString(PLAYER_EXPERIENCE_MODE_PREFERENCE, PlayerExperienceMode.TRANSCRIPT_PANEL.storageValue)
            .putString("last_browser_url", "https://m.youtube.com/watch?v=abcdefghijk")
            .putString("preferred_caption_language", "en")
            .putString("target_language", "en")
            .putString(ORIGINAL_VISIBILITY, CaptionVisibility.ALWAYS.name)
            .commit()
        lateinit var model: AppViewModel
        instrumentation.runOnMainSync { model = AppViewModel(application, LandscapeLiveCaptureTest.DelayedTranscript()) }
        val configuration = Configuration(application.resources.configuration)
        configuration.orientation = Configuration.ORIENTATION_PORTRAIT
        val page = AtomicReference<WebView>()
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalYouTubeRequestInterceptor provides { view, request ->
                        page.compareAndSet(null, view)
                        val html = if (request.isForMainFrame) LandscapeLiveCaptureTest.OFFLINE_PAGE else ""
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    },
                ) {
                    LearningPlayerRoot(model)
                }
            }
            compose.waitUntil(15_000) {
                val loaded = model.state.value
                loaded.segments.isNotEmpty() && loaded.subtitlePanelVisible
            }

            // Fullscreen shows the box; its X leaves the bubble.
            enterFullscreen(page.get())
            // The window under fullscreen keeps its own box; tap the one in fullscreen.
            val fullscreenBox = hasTestTag("learning_subtitle_overlay") and hasAnyAncestor(isDialog())
            compose.waitUntil(5000) { compose.onAllNodes(fullscreenBox).fetchSemanticsNodes().isNotEmpty() }
            // Tap the box's edge: a tap on a word opens the word card instead of the box's buttons.
            compose.onNode(fullscreenBox).performTouchInput { click(centerLeft.copy(x = 6f)) }
            compose.onNodeWithContentDescription("Hide dual subtitles").performClick()
            compose.waitUntil(5000) { model.state.value.subtitlesClosedByUser && exists("show_subtitles") }
            compose.waitForIdle()
            saveUiEvidence("subtitles-closed-fullscreen-bubble")

            // Leaving fullscreen keeps the panel closed: only the bubble shows.
            exitFullscreen(page.get())
            compose.waitForIdle()
            assertFalse(model.state.value.subtitlePanelVisible)
            assertFalse(exists("subtitle_timeline"))
            compose.onNodeWithTag("show_subtitles").assertIsDisplayed()
            saveUiEvidence("subtitles-closed-portrait-bubble")

            // Going back into fullscreen keeps the box closed.
            enterFullscreen(page.get())
            compose.waitUntil(5000) { exists("show_subtitles") }
            assertFalse(exists("learning_subtitle_overlay"))
            exitFullscreen(page.get())

            // Another video keeps it closed too.
            instrumentation.runOnMainSync { model.onYouTubePageChanged("https://m.youtube.com/watch?v=bcdefghijkl") }
            compose.waitUntil(5000) { model.state.value.activeVideoId == "bcdefghijkl" }
            compose.waitForIdle()
            assertFalse(model.state.value.subtitlePanelVisible)
            assertTrue(model.state.value.subtitlesClosedByUser)

            // The bubble opens the panel again.
            compose.onNodeWithTag("show_subtitles").performClick()
            compose.waitUntil(5000) { model.state.value.subtitlePanelVisible }
            assertFalse(model.state.value.subtitlesClosedByUser)
        } finally {
            instrumentation.runOnMainSync {
                page.get()?.webChromeClient?.onHideCustomView()
                model.viewModelScope.cancel()
                youtubeFullscreenActive.value = false
            }
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                }
            }
            editor.commit()
        }
    }

    private fun enterFullscreen(page: WebView) {
        compose.runOnIdle { page.webChromeClient!!.onShowCustomView(FrameLayout(page.context)) {} }
        compose.waitUntil(5000) { youtubeFullscreenActive.value }
    }

    private fun exitFullscreen(page: WebView) {
        compose.runOnIdle { page.webChromeClient!!.onHideCustomView() }
        compose.waitUntil(5000) { !youtubeFullscreenActive.value }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
}
