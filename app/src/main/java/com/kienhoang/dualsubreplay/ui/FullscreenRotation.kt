package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Size of the playing video as "width x height", or "" before the page has one. */
internal const val FULLSCREEN_VIDEO_SIZE_SCRIPT =
    "(function(){var v=document.querySelector('#movie_player video')||document.querySelector('video');" +
        "return v&&v.videoWidth&&v.videoHeight?v.videoWidth+'x'+v.videoHeight:'';})()"

/** Upright videos (Shorts, phone recordings) stay upright in fullscreen, like the YouTube app. */
internal fun fullscreenVideoIsTall(scriptResult: String?): Boolean {
    val size =
        scriptResult
            .orEmpty()
            .trim()
            .removeSurrounding("\"")
            .split('x')
    val width = size.getOrNull(0)?.toIntOrNull() ?: return false
    val height = size.getOrNull(1)?.toIntOrNull() ?: return false
    return width > 0 && height > width
}

/**
 * Turning the screen must not recreate the activity: that would destroy the page and end
 * fullscreen. The app's activity handles rotation itself; other hosts (tests) are left alone.
 */
internal fun rotationKeepsActivity(configChanges: Int): Boolean {
    val needed = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE
    return configChanges and needed == needed
}

private fun Activity.keepsRunningWhenRotated(): Boolean =
    runCatching { rotationKeepsActivity(packageManager.getActivityInfo(componentName, 0).configChanges) }
        .getOrDefault(false)

/** OrientationEventListener degrees: 0 is the phone held upright, -1 is lying flat. */
internal fun phoneHeldUpright(degrees: Int): Boolean =
    degrees != OrientationEventListener.ORIENTATION_UNKNOWN && (degrees <= 30 || degrees >= 330)

/**
 * Turns the screen sideways while a video is fullscreen and gives the screen back afterwards.
 * Leaving fullscreen goes upright at once, then follows the phone again once it is held upright,
 * so a phone still held sideways does not flip straight back to landscape.
 */
internal class FullscreenRotation(
    private val activity: Activity,
) {
    private var orientationBefore: Int? = null
    private var uprightWatcher: OrientationEventListener? = null
    private var returnUpright = false

    fun turnSideways() {
        stopWatching()
        if (orientationBefore == null) {
            orientationBefore = activity.requestedOrientation
            returnUpright = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun giveBack() {
        val before = orientationBefore ?: return
        if (!returnUpright) {
            restore(before)
            return
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val watcher =
            object : OrientationEventListener(activity) {
                override fun onOrientationChanged(degrees: Int) {
                    if (phoneHeldUpright(degrees)) restore(before)
                }
            }
        if (!watcher.canDetectOrientation()) {
            restore(before)
            return
        }
        stopWatching()
        uprightWatcher = watcher
        watcher.enable()
    }

    /** The player is gone: hand the screen back right away. */
    fun release() {
        orientationBefore?.let(::restore)
    }

    private fun restore(orientation: Int) {
        stopWatching()
        orientationBefore = null
        activity.requestedOrientation = orientation
    }

    private fun stopWatching() {
        uprightWatcher?.disable()
        uprightWatcher = null
    }
}

/** Fullscreen turns a wide video to landscape like the YouTube app, and back when it ends. */
@Composable
internal fun FullscreenVideoRotation(
    fullscreen: Boolean,
    webView: WebView,
) {
    val activity = LocalContext.current.findActivity()?.takeIf { it.keepsRunningWhenRotated() } ?: return
    val rotation = remember(activity) { FullscreenRotation(activity) }
    DisposableEffect(rotation) {
        onDispose { rotation.release() }
    }
    DisposableEffect(rotation, fullscreen) {
        if (!fullscreen) return@DisposableEffect onDispose { }
        var active = true
        webView.evaluateJavascript(FULLSCREEN_VIDEO_SIZE_SCRIPT) { size ->
            if (active && !fullscreenVideoIsTall(size)) rotation.turnSideways()
        }
        onDispose {
            active = false
            rotation.giveBack()
        }
    }
}

/** True when a layout pass gave the fullscreen video a new size, as turning the screen does. */
internal fun fullscreenViewResized(
    width: Int,
    height: Int,
    oldWidth: Int,
    oldHeight: Int,
): Boolean = width != oldWidth || height != oldHeight

/**
 * WebView (since M139) shrinks the page's visible area by the part of the fullscreen video that
 * hangs below the window, but it measures that only when insets reach the page's own WebView or the
 * video moves. Turning the screen sends the activity its new insets while the fullscreen Dialog
 * still has the portrait size, so the video looks 2400 px tall in a 1080 px window and the visible
 * area stays 0 px high. YouTube's settings menu then scrolls that area to its first item, pushing
 * the video up (owner report 2026-10-27). Asking for insets again once the video has its new size
 * makes WebView measure it again.
 */
@Composable
internal fun RemeasureFullscreenInsets(
    fullscreenView: View,
    webView: WebView,
) {
    DisposableEffect(fullscreenView, webView) {
        val listener =
            View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (fullscreenViewResized(right - left, bottom - top, oldRight - oldLeft, oldBottom - oldTop)) {
                    webView.requestApplyInsets()
                }
            }
        fullscreenView.addOnLayoutChangeListener(listener)
        onDispose { fullscreenView.removeOnLayoutChangeListener(listener) }
    }
}
