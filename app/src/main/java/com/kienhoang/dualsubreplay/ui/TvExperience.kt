package com.kienhoang.dualsubreplay.ui

import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import com.kienhoang.dualsubreplay.playback.WebVideoCommand

// The TV layout's parts that sit over the player (spec 2026-10-30-android-tv-layout.md): TV home,
// and the remote's keys on the video.

/** What the TV layer needs from the screen around it. */
internal class TvLayerActions(
    val openVideo: (videoId: String) -> Unit,
    val tabActions: BrowserTabActions,
    val showPanel: () -> Unit,
    val practice: () -> Unit,
    val progress: () -> Unit,
    val settings: () -> Unit,
)

@Composable
internal fun TvLayer(
    state: DualSubUiState,
    webController: YouTubeWebController,
    homeShown: Boolean,
    onHomeShownChange: (Boolean) -> Unit,
    actions: TvLayerActions,
) {
    if (!LocalTvLayout.current) return
    BindTvRemote(state, webController, homeShown, actions.showPanel) { onHomeShownChange(true) }
    val home = rememberTvHomeState()
    // Leaving TV home hands the remote to the page, so OK and the arrows work on the video at once.
    LaunchedEffect(homeShown) {
        if (homeShown) webController.pause() else TvRemoteKeys.focusPage?.invoke()
    }
    if (!homeShown) return
    val leave = { onHomeShownChange(false) }
    TvHome(
        home = home,
        tabs = state.browserTabs,
        videoOpen = state.activeVideoId != null,
        actions =
            TvHomeActions(
                openVideo = { videoId ->
                    actions.openVideo(videoId)
                    leave()
                },
                openTab = { id ->
                    actions.tabActions.switchTo(id)
                    leave()
                },
                // YouTube's pages in a tab of their own, so the open video stays in Continue watching.
                openYouTubePage = {
                    val tab = tvYouTubePageTab(state.browserTabs, videoOpen = state.activeVideoId != null)
                    when (tab) {
                        null -> actions.tabActions.openNew(MAIN_TAB_ACCOUNT)
                        state.browserTabs.activeId -> Unit
                        else -> actions.tabActions.switchTo(tab)
                    }
                    leave()
                },
                backToVideo = leave,
                practice = actions.practice,
                progress = actions.progress,
                settings = actions.settings,
            ),
        // The page and player stay under TV home; the remote's focus must not wander onto them.
        modifier = Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
    )
}

/** Hands the remote's keys to the video while the TV layout is shown ([TvRemoteKeys]). */
@Composable
private fun BindTvRemote(
    state: DualSubUiState,
    webController: YouTubeWebController,
    homeShown: Boolean,
    showPanel: () -> Unit,
    showHome: () -> Unit,
) {
    val currentState by rememberUpdatedState(state)
    val currentShowPanel by rememberUpdatedState(showPanel)
    val currentHomeShown by rememberUpdatedState(homeShown)
    val currentShowHome by rememberUpdatedState(showHome)
    DisposableEffect(webController) {
        val handler = { keyCode: Int, repeat: Boolean, focused: View? ->
            val page = generateSequence(focused) { it.parent as? View }.filterIsInstance<WebView>().firstOrNull()
            val place =
                TvKeyPlace(
                    homeShown = currentHomeShown,
                    onPage = page != null,
                    onPanel = page == null && TvRemoteKeys.panelFocused,
                    watchPage = currentState.activeVideoId != null,
                    pageCanGoBack = page?.canGoBack() == true,
                )
            val action = tvRemoteAction(keyCode, place)
            if (action != null && (!repeat || repeatsWhileHeld(action))) {
                runTvRemoteAction(action, currentState, webController, currentShowPanel, currentShowHome)
            }
            action != null
        }
        TvRemoteKeys.handler = handler
        onDispose { if (TvRemoteKeys.handler === handler) TvRemoteKeys.handler = null }
    }
}

private fun runTvRemoteAction(
    action: TvRemoteAction,
    state: DualSubUiState,
    webController: YouTubeWebController,
    showPanel: () -> Unit,
    showHome: () -> Unit,
) {
    val playback = WebVideoBridge.playback.value
    when (action) {
        TvRemoteAction.TOGGLE_PLAY -> if (playback != null) WebVideoBridge.toggle() else WebVideoBridge.send(WebVideoCommand.Play)
        TvRemoteAction.PLAY -> WebVideoBridge.send(WebVideoCommand.Play)
        TvRemoteAction.PAUSE -> webController.pause()
        TvRemoteAction.SEEK_BACK, TvRemoteAction.SEEK_FORWARD -> {
            val position = playback?.positionMs ?: return
            val step = if (action == TvRemoteAction.SEEK_BACK) -TV_SEEK_MS else TV_SEEK_MS
            WebVideoBridge.send(WebVideoCommand.SeekTo((position + step).coerceAtLeast(0L)))
        }
        TvRemoteAction.PREVIOUS_LINE, TvRemoteAction.NEXT_LINE, TvRemoteAction.REPLAY_LINE ->
            tvLineStartMs(state.segments.map { it.startMs }, state.currentIndex, playback?.positionMs, action)
                ?.let { webController.replayFrom(it / 1_000f) }
        // Picking a line happens paused: a playing panel scrolls the focused line away.
        TvRemoteAction.FOCUS_PANEL -> {
            webController.pause()
            if (!state.subtitlePanelVisible) showPanel()
            TvRemoteKeys.focusPanel?.invoke()
        }
        TvRemoteAction.FOCUS_VIDEO -> TvRemoteKeys.focusPage?.invoke()
        TvRemoteAction.SHOW_HOME -> showHome()
    }
}
