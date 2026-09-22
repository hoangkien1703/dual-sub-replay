package com.kienhoang.dualsubreplay.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitPanelPositionPersistenceTest {
    @Test fun portraitPanelPositionSurvivesRecreationAndIndividualResetIsIsolated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("dual_sub_preferences", 0)
        val positionExisted = preferences.contains(PORTRAIT_PANEL_OFFSET_PREFERENCE)
        val savedPosition =
            preferences.getFloat(
                PORTRAIT_PANEL_OFFSET_PREFERENCE,
                DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
            )
        val savedPreload = preferences.getBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, true)
        val sentinelKey = "portrait_panel_position_test_sentinel"

        instrumentation.runOnMainSync {
            var store = ViewModelStore()
            try {
                preferences
                    .edit()
                    .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, false)
                    .putBoolean(sentinelKey, true)
                    .commit()
                var provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                provider[AppViewModel::class.java].setPortraitPanelOffsetFraction(0.126f)
                assertEquals(0.13f, preferences.getFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, 0f), 0f)

                store.clear()
                store = ViewModelStore()
                provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                val restored = provider[AppViewModel::class.java]
                assertEquals(0.13f, restored.state.value.portraitPanelOffsetFraction, 0f)

                restored.resetPortraitPanelPosition()
                assertEquals(
                    DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
                    restored.state.value.portraitPanelOffsetFraction,
                    0f,
                )
                assertFalse(preferences.contains(PORTRAIT_PANEL_OFFSET_PREFERENCE))
                assertTrue(preferences.getBoolean(sentinelKey, false))
            } finally {
                store.clear()
                val editor =
                    preferences
                        .edit()
                        .putBoolean(PRELOAD_MODELS_ENABLED_PREFERENCE, savedPreload)
                        .remove(sentinelKey)
                if (positionExisted) {
                    editor.putFloat(PORTRAIT_PANEL_OFFSET_PREFERENCE, savedPosition)
                } else {
                    editor.remove(PORTRAIT_PANEL_OFFSET_PREFERENCE)
                }
                editor.commit()
            }
        }
    }
}
