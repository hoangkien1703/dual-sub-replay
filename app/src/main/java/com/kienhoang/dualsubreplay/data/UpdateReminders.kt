package com.kienhoang.dualsubreplay.data

/**
 * The banner shows at most once in this long, however many versions come out meanwhile (owner request
 * 2026-10-30: releases can come daily, and the banner must not appear every day).
 */
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
 * The banner shows at most once a week, whichever version it names, so daily releases do not bring a
 * daily banner; Skip this version hides that version for good. A security update (the installed app is
 * older than the latest security fix) cannot be skipped: it shows at once, then once a day. A clock set
 * back never hides a reminder for long.
 */
internal fun updateReminder(
    update: AppUpdate?,
    memory: ReminderMemory,
    nowMs: Long,
): UpdateReminder {
    if (update == null) return UpdateReminder.NONE
    val elapsed = nowMs - memory.shownAtMs
    if (update.critical) {
        val due = memory.shownVersion != update.versionName || elapsed >= SECURITY_REMINDER_AGAIN_MS || elapsed < 0
        return if (due) UpdateReminder.SECURITY else UpdateReminder.NONE
    }
    if (memory.skippedVersion == update.versionName) return UpdateReminder.NONE
    val due = memory.shownAtMs <= 0 || elapsed >= UPDATE_REMINDER_AGAIN_MS || elapsed < 0
    return if (due) UpdateReminder.BANNER else UpdateReminder.NONE
}

/** A reminder never covers a playing video; it waits until the video is paused. */
internal fun reminderVisible(
    reminder: UpdateReminder,
    videoPlaying: Boolean,
): Boolean = reminder != UpdateReminder.NONE && !videoPlaying
