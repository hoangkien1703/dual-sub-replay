package com.kienhoang.dualsubreplay.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Closes the emulator's own "System UI isn't responding" dialog and stops it coming back. The CI
 * emulator shows it during long runs, and while it is up it holds window focus, which Espresso's
 * pressBack and closeSoftKeyboard wait for. Call this just before those.
 */
internal fun closeSystemDialogs() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    listOf(
        "settings put global hide_error_dialogs 1",
        "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
    ).forEach { command ->
        // Reading to the end waits for the command to finish.
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
}
