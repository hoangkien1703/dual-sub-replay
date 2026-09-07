package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionVisibilityPersistenceTest {
    @Test fun independentModesSurviveViewModelRecreationAndUpdateLive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val original = preferences.getString(ORIGINAL_VISIBILITY, null)
        val translated = preferences.getString(TRANSLATED_VISIBILITY, null)
        instrumentation.runOnMainSync {
            val store = ViewModelStore()
            try {
                preferences
                    .edit()
                    .putString(ORIGINAL_VISIBILITY, "PAUSED")
                    .putString(TRANSLATED_VISIBILITY, "NEVER")
                    .commit()
                val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                val first = provider[AppViewModel::class.java]
                assertEquals(CaptionVisibility.PAUSED, first.state.value.originalVisibility)
                assertEquals(CaptionVisibility.NEVER, first.state.value.translatedVisibility)
                preferences.edit().putString(TRANSLATED_VISIBILITY, "ALWAYS").commit()
                assertEquals(CaptionVisibility.ALWAYS, first.state.value.translatedVisibility)
                store.clear()
                val recreated = provider[AppViewModel::class.java]
                assertEquals(CaptionVisibility.PAUSED, recreated.state.value.originalVisibility)
                assertEquals(CaptionVisibility.ALWAYS, recreated.state.value.translatedVisibility)
            } finally {
                store.clear()
                preferences
                    .edit()
                    .putString(ORIGINAL_VISIBILITY, original)
                    .putString(TRANSLATED_VISIBILITY, translated)
                    .commit()
            }
        }
    }
}
