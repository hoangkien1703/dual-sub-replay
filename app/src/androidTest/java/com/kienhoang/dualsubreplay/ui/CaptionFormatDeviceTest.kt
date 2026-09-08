package com.kienhoang.dualsubreplay.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleMerger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptionFormatDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captionFormatSurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val saved = preferences.getString(CAPTION_FORMAT_PREFERENCE, null)
        val preload = preferences.getBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, true)
        instrumentation.runOnMainSync {
            val store = ViewModelStore()
            try {
                preferences.edit().putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false).commit()
                val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                CaptionFormat.entries.forEach { format ->
                    provider[AppViewModel::class.java].setCaptionFormat(format)
                    store.clear()
                    assertEquals(format, provider[AppViewModel::class.java].state.value.captionFormat)
                }
            } finally {
                store.clear()
                preferences
                    .edit()
                    .putString(CAPTION_FORMAT_PREFERENCE, saved)
                    .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, preload)
                    .commit()
            }
        }
    }

    @Test fun bothFormatsRenderInBothPresentationsWithEnlargedText() {
        val format = mutableStateOf(CaptionFormat.SHORT_PHRASES)
        val source =
            SubtitleMerger.merge(
                listOf(
                    RawCaptionCue(
                        0,
                        7000,
                        "Could you give me a hand with this box, because it is too heavy for me to carry alone?",
                    ),
                ),
            )
        compose.setContent {
            MaterialTheme {
                val segment =
                    captionDisplaySegments(source, format.value, true).first().copy(
                        translatedText =
                            if (format.value == CaptionFormat.SHORT_PHRASES) {
                                "Bạn có thể giúp tôi với chiếc hộp này không,"
                            } else {
                                "Bạn có thể giúp tôi với chiếc hộp này không, vì nó quá nặng để tôi có thể mang một mình?"
                            },
                    )
                val state = DualSubUiState(activeVideoId = "dQw4w9WgXcQ", segments = listOf(segment), currentIndex = 0, activeWordIndex = 2)
                Column(Modifier.systemBarsPadding()) {
                    CompactSubtitleCard(segment, true, 1.35f, {}, activeWordIndex = 2)
                    LearningSubtitleOverlay(
                        learningOverlayContent(state)!!,
                        1.35f,
                        0f,
                        orientation = Configuration.ORIENTATION_LANDSCAPE,
                        onPositionChange = {},
                        onSettings = {},
                        onClose = {},
                    )
                }
            }
        }
        CaptionFormat.entries.forEach { selected ->
            compose.runOnIdle { format.value = selected }
            compose.onAllNodesWithText("Could you give me", substring = true).assertCountEquals(2)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = java.io.File(context.getExternalFilesDir(null), "caption-evidence").apply { mkdirs() }
            java.io.File(directory, "${selected.storageValue}-large.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
