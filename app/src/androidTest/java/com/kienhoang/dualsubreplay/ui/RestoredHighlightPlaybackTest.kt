package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.CaptionProvider
import com.kienhoang.dualsubreplay.data.CaptionTrackResult
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RestoredHighlightPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun missingLiveSignalStillUpdatesBothSurfacesAcrossLayoutsAndSeeks() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val saved = preferences.all
        preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false).commit()
        val provider =
            object : CaptionProvider {
                override suspend fun fetch(
                    videoId: String,
                    preferredLanguages: List<String>,
                ): CaptionTrackResult =
                    CaptionTrackResult(
                        "en",
                        true,
                        listOf(
                            RawCaptionCue(
                                0,
                                3000,
                                "One two three.",
                                listOf(
                                    SubtitleWord("One", 0, 1000),
                                    SubtitleWord("two", 1000, 2000),
                                    SubtitleWord("three.", 2000, 3000),
                                ),
                            ),
                        ),
                    )
            }
        val vm = withContext(Dispatchers.Main) { AppViewModel(application, provider) }
        val orientation = mutableStateOf(Configuration.ORIENTATION_PORTRAIT)
        try {
            withContext(Dispatchers.Main) {
                vm.setTargetLanguage("en")
                vm.setWordHighlightEnabled(true)
                vm.onYouTubePageChanged("https://m.youtube.com/watch?v=abcdefghijk")
            }
            withTimeout(5000) { vm.state.first { it.segments.isNotEmpty() } }
            compose.setContent {
                MaterialTheme {
                    val current by vm.state.collectAsState()
                    val visible = current.copy(translatedVisibility = CaptionVisibility.NEVER)
                    Column {
                        current.segments.getOrNull(current.currentIndex)?.let { segment ->
                            CompactSubtitleCard(
                                segment,
                                true,
                                1f,
                                {},
                                activeWordIndex = current.activeWordIndex,
                                showTranslation = false,
                                wordLearningEnabled = true,
                            )
                        }
                        learningOverlayContent(visible)?.let { content ->
                            LearningSubtitleOverlay(
                                content,
                                1f,
                                0f,
                                orientation = orientation.value,
                                onPositionChange = {},
                                onSettings = {},
                                onClose = {},
                                wordLearningEnabled = true,
                            )
                        }
                    }
                }
            }
            CaptionFormat.entries.forEach { format ->
                compose.runOnIdle { vm.setCaptionFormat(format) }
                listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE).forEach { value ->
                    compose.runOnIdle { orientation.value = value }
                    listOf(0.1f to "One", 1.1f to "two", 2.1f to "three.", 0.1f to "One").forEach { (second, word) ->
                        compose.runOnIdle { vm.onWebPlaybackSecond("abcdefghijk", second, null) }
                        assertSpokenWord(word)
                    }
                }
                compose.runOnIdle {
                    vm.setWordHighlightEnabled(false)
                    vm.onWebPlaybackSecond("abcdefghijk", 1.1f, null)
                }
                assertSpokenWord(null)
                compose.runOnIdle {
                    vm.setWordHighlightEnabled(true)
                    vm.onWebPlaybackSecond("abcdefghijk", 1.1f, null)
                }
                assertSpokenWord("two")
            }
        } finally {
            withContext(Dispatchers.Main) {
                vm.onYouTubePageChanged(YOUTUBE_HOME_URL)
                vm.viewModelScope.cancel()
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

    private fun assertSpokenWord(expected: String?) {
        val nodes = compose.onAllNodesWithText("One two three.").assertCountEquals(2).fetchSemanticsNodes()
        nodes.forEach { node ->
            val text = node.config[SemanticsProperties.Text].single()
            val highlights = text.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }
            assertEquals(if (expected == null) 0 else 1, highlights.size)
            if (expected != null) {
                val span = highlights.single()
                assertEquals(expected, text.text.substring(span.start, span.end))
            }
        }
    }
}
