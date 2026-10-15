package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/*
 * Back presses and keyboard hiding that work while the app window has no input focus. Espresso's
 * pressBack and closeSoftKeyboard wait up to 10 s for a focused window, and some managed-emulator
 * boots never give the app window focus (Android CI, 2026-10-15), while Compose's own input works.
 */

private fun ComposeTestRule.viewShowing(tag: String): View =
    (onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().root as ViewRootForTest).view

private tailrec fun Context.activity(): ComponentActivity = this as? ComponentActivity ?: (this as ContextWrapper).baseContext.activity()

/** Presses back in the popup or dialog window that shows [tag]; Compose's popups handle it there. */
internal fun ComposeTestRule.pressBackInWindowOf(tag: String) {
    val window = viewShowing(tag).rootView
    runOnUiThread {
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
    }
    waitForIdle()
}

/** Sends back to the activity that shows [tag], as the system back gesture does. */
internal fun ComposeTestRule.pressActivityBack(tag: String) {
    val activity = viewShowing(tag).context.activity()
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

/** Hides the keyboard of the window that shows [tag], if it is open. */
internal fun ComposeTestRule.hideKeyboard(tag: String) {
    val view = viewShowing(tag)
    runOnUiThread {
        view.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }
    waitForIdle()
}
