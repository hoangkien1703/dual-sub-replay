package com.kienhoang.dualsubreplay.data

/**
 * The banner shows at most once in this long, however many versions come out meanwhile (owner request
 * 2026-10-30: releases can come daily, and the banner must not appear every day).
 */
internal const val UPDATE_REMINDER_AGAIN_MS = 7L * 24 * 60 * 60 * 1000

/** After Not now, a security update is shown again the next day during its first days... */
internal const val SECURITY_REMINDER_AGAIN_MS = 24L * 60 * 60 * 1000

/** ...which last this long from its first showing; after that it shows once a week (owner request 2026-10-30). */
internal const val SECURITY_DAILY_PERIOD_MS = 3L * 24 * 60 * 60 * 1000

/** What the app shows about a newer version: nothing, a small banner, or the security dialog. */
internal enum class UpdateReminder { NONE, BANNER, SECURITY }

/**
 * Which version was last shown and when, and the version Skip this version hid; for a security update,
 * which fix (its [AppUpdate.securityFixCode]) was first shown and when.
 */
internal data class ReminderMemory(
    val shownVersion: String? = null,
    val shownAtMs: Long = 0,
    val skippedVersion: String? = null,
    val securityFixCode: Long = 0,
    val securitySinceMs: Long = 0,
    /** When this app was installed or last updated (Android's `lastUpdateTime`); 0 when unknown. */
    val installedAtMs: Long = 0,
)

/**
 * The banner shows at most once a week, whichever version it names, so daily releases do not bring a
 * daily banner, and not in the week after the app was installed or updated, however that happened
 * (owner question 2026-10-30); Skip this version hides that version for good. A security update (the installed app is
 * older than the latest security fix) cannot be skipped: a new fix shows at once, then once a day for
 * its first 3 days and once a week after that, however many versions come out meanwhile. A clock set
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
        if (memory.securityFixCode != update.securityFixCode || memory.securitySinceMs <= 0) return UpdateReminder.SECURITY
        val age = nowMs - memory.securitySinceMs
        val again = if (age in 0 until SECURITY_DAILY_PERIOD_MS) SECURITY_REMINDER_AGAIN_MS else UPDATE_REMINDER_AGAIN_MS
        val due = elapsed >= again || elapsed < 0
        return if (due) UpdateReminder.SECURITY else UpdateReminder.NONE
    }
    if (memory.skippedVersion == update.versionName) return UpdateReminder.NONE
    val quietSince = maxOf(memory.shownAtMs, memory.installedAtMs)
    val quiet = nowMs - quietSince
    val due = quietSince <= 0 || quiet >= UPDATE_REMINDER_AGAIN_MS || quiet < 0
    return if (due) UpdateReminder.BANNER else UpdateReminder.NONE
}

/** A reminder never covers a playing video; it waits until the video is paused. */
internal fun reminderVisible(
    reminder: UpdateReminder,
    videoPlaying: Boolean,
): Boolean = reminder != UpdateReminder.NONE && !videoPlaying
