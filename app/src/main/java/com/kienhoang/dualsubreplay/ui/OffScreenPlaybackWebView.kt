package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Android's WebView pauses a page's video itself as soon as its window is hidden (screen off,
 * Home, another app), before any page script can react. While a video plays, this WebView keeps
 * telling WebView its window is visible, so the video keeps playing off screen; at every other
 * time it passes the real visibility on.
 */
@SuppressLint("ViewConstructor")
internal class OffScreenPlaybackWebView(
    context: Context,
    private val keepPlaying: () -> Boolean,
) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(windowVisibilityForPage(visibility, keepPlaying()))
    }
}

/**
 * Holds WebView's full-screen video view, which WebView creates itself and the app cannot
 * subclass. It sits in its own dialog window, so without this it heard the real "hidden" when the
 * screen turned off and WebView paused the video until the app said "visible" again a moment
 * later: a short gap in the sound. While a video plays, its children hear "visible" straight away.
 */
@SuppressLint("ViewConstructor")
internal class OffScreenPlaybackFrame(
    context: Context,
    private val keepPlaying: () -> Boolean,
) : FrameLayout(context) {
    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        super.dispatchWindowVisibilityChanged(windowVisibilityForPage(visibility, keepPlaying()))
    }
}

internal fun windowVisibilityForPage(
    actualVisibility: Int,
    keepPlaying: Boolean,
): Int = if (keepPlaying) View.VISIBLE else actualVisibility
