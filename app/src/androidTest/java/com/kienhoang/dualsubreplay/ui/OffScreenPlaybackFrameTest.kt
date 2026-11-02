package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class OffScreenPlaybackFrameTest {
    private class RecordingView(
        context: Context,
    ) : View(context) {
        val heard = mutableListOf<Int>()

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            heard += visibility
        }
    }

    @Test fun fullScreenViewHearsVisibleWhileAVideoPlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var playing = true
            val frame = OffScreenPlaybackFrame(instrumentation.targetContext) { playing }
            val child = RecordingView(instrumentation.targetContext)
            frame.addView(child)

            frame.dispatchWindowVisibilityChanged(View.GONE)
            playing = false
            frame.dispatchWindowVisibilityChanged(View.GONE)

            assertEquals(listOf(View.VISIBLE, View.GONE), child.heard)
        }
    }
}
