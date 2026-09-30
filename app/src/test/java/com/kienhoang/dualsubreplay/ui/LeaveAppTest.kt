package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Back on the first page leaves the app without closing it, so the transcript is still there on return. */
class LeaveAppTest {
    private class RecordingActivity(
        private val canMoveTaskToBack: Boolean,
    ) : Activity() {
        val calls = mutableListOf<String>()

        override fun moveTaskToBack(nonRoot: Boolean): Boolean {
            calls += "moveTaskToBack($nonRoot)"
            return canMoveTaskToBack
        }

        override fun finish() {
            calls += "finish"
        }
    }

    @Test
    fun backOnTheFirstPageSendsTheAppToTheBackgroundInsteadOfClosingIt() {
        val activity = RecordingActivity(canMoveTaskToBack = true)

        activity.leaveKeepingState()

        assertEquals(listOf("moveTaskToBack(true)"), activity.calls)
    }

    @Test
    fun theAppStillClosesWhenAndroidCannotMoveItToTheBackground() {
        val activity = RecordingActivity(canMoveTaskToBack = false)

        activity.leaveKeepingState()

        assertEquals(listOf("moveTaskToBack(true)", "finish"), activity.calls)
    }
}
