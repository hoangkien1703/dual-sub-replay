package com.kienhoang.dualsubreplay.ui

/** How long after closing the settings page it still reopens where it was (owner request 2026-10-31). */
internal const val SETTINGS_PAGE_MEMORY_MILLIS = 3 * 60_000L

/** Where the settings page was: the open "More settings" section and how far it was scrolled. */
internal data class SettingsPagePlace(
    val openSection: MoreSettingsSection? = null,
    val scrollOffset: Int = 0,
)

/**
 * Remembers [SettingsPagePlace] between openings of the settings page, so a user who closes it to
 * check the video comes back to the same section and scroll position. It lives in memory only
 * (kept by [DualSubApp]), so restarting the app starts at the top again, and a place older than
 * [SETTINGS_PAGE_MEMORY_MILLIS] is forgotten.
 */
internal class SettingsPageMemory {
    private var place = SettingsPagePlace()
    private var closedAtMillis = Long.MIN_VALUE

    fun placeToRestore(nowMillis: Long): SettingsPagePlace =
        if (settingsPlaceStillFresh(closedAtMillis, nowMillis)) place else SettingsPagePlace()

    fun remember(
        place: SettingsPagePlace,
        nowMillis: Long,
    ) {
        this.place = place
        closedAtMillis = nowMillis
    }
}

internal fun settingsPlaceStillFresh(
    closedAtMillis: Long,
    nowMillis: Long,
): Boolean = closedAtMillis != Long.MIN_VALUE && nowMillis - closedAtMillis in 0..SETTINGS_PAGE_MEMORY_MILLIS

/**
 * Where the page opens: at the top of [jumpToSection] when a shortcut asked for one (long-press on
 * a subtitle panel's gear), otherwise where the user left it.
 */
internal fun SettingsPageMemory.placeOnOpen(
    jumpToSection: MoreSettingsSection?,
    nowMillis: Long,
): SettingsPagePlace = if (jumpToSection != null) SettingsPagePlace(openSection = jumpToSection) else placeToRestore(nowMillis)

/** A quick tap on the peek button keeps the page see-through; a longer hold peeks only while held. */
internal const val SETTINGS_PEEK_TAP_MILLIS = 300L

/** How quickly the page fades out and back in. */
internal const val SETTINGS_PEEK_FADE_MILLIS = 150

/**
 * Whether the page stays see-through after the peek button's click. [pressedAtMillis] is null
 * when the click came without a touch (TalkBack, a TV remote's key), which counts as a tap.
 */
internal fun settingsPeekLockedAfterClick(
    locked: Boolean,
    pressedAtMillis: Long?,
    nowMillis: Long,
): Boolean =
    when {
        locked -> false
        pressedAtMillis == null -> true
        else -> nowMillis - pressedAtMillis < SETTINGS_PEEK_TAP_MILLIS
    }

/**
 * Page opacity: hidden while peeking or while a subtitle slider is held. A half-faded page mixed its
 * text with the subtitles behind it (owner feedback 2026-10-31), so it is all or nothing.
 */
internal fun settingsPageAlpha(
    peeking: Boolean,
    sliderHeld: Boolean,
): Float = if (peeking || sliderHeld) 0f else 1f
