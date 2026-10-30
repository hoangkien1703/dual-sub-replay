package com.kienhoang.dualsubreplay.ui

import android.view.KeyEvent
import android.view.View
import android.webkit.WebView

// A TV remote in the TV layout (spec 2026-10-30-android-tv-layout.md). Keys reach the activity
// before any view, so the video page never sees the keys used here.

internal enum class TvRemoteAction {
    TOGGLE_PLAY,
    PLAY,
    PAUSE,
    SEEK_BACK,
    SEEK_FORWARD,
    PREVIOUS_LINE,
    NEXT_LINE,
    REPLAY_LINE,
    FOCUS_PANEL,
    FOCUS_VIDEO,
    SHOW_HOME,
}

/** Where the remote's focus is when a key comes. */
internal data class TvKeyPlace(
    /** TV home covers the page; it handles its own keys. */
    val homeShown: Boolean = false,
    /** Focus is on the YouTube page. */
    val onPage: Boolean = false,
    /** Focus is on a line of the subtitle panel. */
    val onPanel: Boolean = false,
    /** The page shows a video. */
    val watchPage: Boolean = false,
    /** The page has an earlier page to go back to. */
    val pageCanGoBack: Boolean = false,
)

/** Seconds that rewind and fast-forward move the video. */
internal const val TV_SEEK_MS = 10_000L

/**
 * What [keyCode] does in the TV layout, or null to leave it to Android. Media keys work
 * everywhere. On a video the arrows move between lines, OK plays or pauses, ▼ goes to the panel
 * and Back to TV home; Back in the panel returns to the video; Back on another YouTube page goes
 * back in the page, and to TV home from the first page.
 */
internal fun tvRemoteAction(
    keyCode: Int,
    place: TvKeyPlace,
): TvRemoteAction? =
    mediaKeyAction(keyCode) ?: when {
        place.homeShown -> null
        place.onPage && place.watchPage -> videoKeyAction(keyCode)
        keyCode != KeyEvent.KEYCODE_BACK -> null
        place.onPanel && place.watchPage -> TvRemoteAction.FOCUS_VIDEO
        place.onPage && !place.pageCanGoBack -> TvRemoteAction.SHOW_HOME
        else -> null
    }

private fun mediaKeyAction(keyCode: Int): TvRemoteAction? =
    when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> TvRemoteAction.TOGGLE_PLAY
        KeyEvent.KEYCODE_MEDIA_PLAY -> TvRemoteAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> TvRemoteAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_REWIND -> TvRemoteAction.SEEK_BACK
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TvRemoteAction.SEEK_FORWARD
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> TvRemoteAction.PREVIOUS_LINE
        KeyEvent.KEYCODE_MEDIA_NEXT -> TvRemoteAction.NEXT_LINE
        else -> null
    }

private fun videoKeyAction(keyCode: Int): TvRemoteAction? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TvRemoteAction.TOGGLE_PLAY
        KeyEvent.KEYCODE_DPAD_LEFT -> TvRemoteAction.PREVIOUS_LINE
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvRemoteAction.NEXT_LINE
        KeyEvent.KEYCODE_DPAD_UP -> TvRemoteAction.REPLAY_LINE
        KeyEvent.KEYCODE_DPAD_DOWN -> TvRemoteAction.FOCUS_PANEL
        KeyEvent.KEYCODE_BACK -> TvRemoteAction.SHOW_HOME
        else -> null
    }

/** Whether a held key repeating should act again: only seeking does, the rest act once per press. */
internal fun repeatsWhileHeld(action: TvRemoteAction): Boolean = action == TvRemoteAction.SEEK_BACK || action == TvRemoteAction.SEEK_FORWARD

/**
 * Where the video goes for a line action, in ms, or null when there is no line. [starts] are the
 * lines' start times, [current] the playing line (-1 when unknown, then [positionMs] finds it).
 * Previous and next stop at the first and last line.
 */
internal fun tvLineStartMs(
    starts: List<Long>,
    current: Int,
    positionMs: Long?,
    action: TvRemoteAction,
): Long? {
    if (starts.isEmpty()) return null
    val playing =
        if (current in starts.indices) current else positionMs?.let { position -> starts.indexOfLast { it <= position } } ?: -1
    val target =
        when (action) {
            TvRemoteAction.PREVIOUS_LINE -> playing - 1
            TvRemoteAction.NEXT_LINE -> playing + 1
            TvRemoteAction.REPLAY_LINE -> playing
            else -> return null
        }
    return starts[target.coerceIn(0, starts.lastIndex)]
}

/** Whether [view] is the YouTube page or inside it. */
internal fun isOnYouTubePage(view: View?): Boolean = generateSequence(view) { it.parent as? View }.any { it is WebView }

/**
 * Hands the activity's keys to the TV layout. The layout sets [handler] while it is shown; a key
 * whose press it took also has its release taken, so the page never sees half a press.
 */
internal object TvRemoteKeys {
    /** Acts on a key press and says whether it took it; set only while the TV layout is on. */
    var handler: ((keyCode: Int, repeat: Boolean, focused: View?) -> Boolean)? = null

    /** Moves the remote's focus to the playing line of the panel; false when there is none. */
    var focusPanel: (() -> Boolean)? = null

    /** Moves the remote's focus to the YouTube page. */
    var focusPage: (() -> Unit)? = null

    /** True while the remote's focus is in the subtitle panel. */
    var panelFocused = false

    private val taken = mutableSetOf<Int>()

    fun dispatch(
        event: KeyEvent,
        focused: View?,
    ): Boolean {
        val act = handler ?: return taken.remove(event.keyCode)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val repeat = event.repeatCount > 0
                val took = act(event.keyCode, repeat, focused) || (repeat && event.keyCode in taken)
                if (took) taken += event.keyCode
                took
            }
            KeyEvent.ACTION_UP -> taken.remove(event.keyCode)
            else -> false
        }
    }
}
