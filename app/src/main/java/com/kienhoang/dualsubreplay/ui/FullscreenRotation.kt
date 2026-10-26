package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
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
    val activity = LocalContext.current.findActivity() ?: return
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
