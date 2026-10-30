package com.kienhoang.dualsubreplay.data

/** After Not now, a new version is shown again a week later (owner request 2026-10-30). */
internal const val UPDATE_REMINDER_AGAIN_MS = 7L * 24 * 60 * 60 * 1000

/** After Not now, a security update is shown again the next day. */
internal const val SECURITY_REMINDER_AGAIN_MS = 24L * 60 * 60 * 1000

/** What the app shows about a newer version: nothing, a small banner, or the security dialog. */
internal enum class UpdateReminder { NONE, BANNER, SECURITY }

/** Which version was last shown and when, and the version Skip this version hid. */
internal data class ReminderMemory(
    val shownVersion: String? = null,
    val shownAtMs: Long = 0,
    val skippedVersion: String? = null,
)

/**
 * A new version shows at once, then at most once a week until Skip this version; a security update
 * (the installed app is older than the latest security fix) cannot be skipped and shows once a day.
 * A clock set back never hides a reminder for long.
 */
internal fun updateReminder(
    update: AppUpdate?,
    memory: ReminderMemory,
    nowMs: Long,
): UpdateReminder {
    if (update == null) return UpdateReminder.NONE
    val shown = if (update.critical) UpdateReminder.SECURITY else UpdateReminder.BANNER
    if (!update.critical && memory.skippedVersion == update.versionName) return UpdateReminder.NONE
    if (memory.shownVersion != update.versionName) return shown
    val elapsed = nowMs - memory.shownAtMs
    val again = if (update.critical) SECURITY_REMINDER_AGAIN_MS else UPDATE_REMINDER_AGAIN_MS
    return if (elapsed >= again || elapsed < 0) shown else UpdateReminder.NONE
}

/** A reminder never covers a playing video; it waits until the video is paused. */
internal fun reminderVisible(
    reminder: UpdateReminder,
    videoPlaying: Boolean,
): Boolean = reminder != UpdateReminder.NONE && !videoPlaying
