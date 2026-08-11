package com.kienhoang.dualsubreplay.ui

import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kienhoang.dualsubreplay.BuildConfig
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

private const val EMBEDDED_PLAYER_LOG_TAG = "DualSubEmbeddedPlayer"
private const val PLAYER_INITIALIZATION_TIMEOUT_MS = 12_000L

internal enum class PlaybackState {
    IDLE,
    READY,
    PLAYING,
    PAUSED,
    BUFFERING,
    ENDED,
    ERROR,
}

internal sealed interface PlaybackFailure {
    val recoverable: Boolean

    data object InitializationTimeout : PlaybackFailure {
        override val recoverable: Boolean = true
    }

    data class Initialization(
        val message: String,
    ) : PlaybackFailure {
        override val recoverable: Boolean = true
    }

    data class YouTube(
        val code: String,
        override val recoverable: Boolean,
    ) : PlaybackFailure
}

internal interface EmbeddedPlayerController {
    fun replayFrom(second: Float)

    fun retry()

    /** Returns true when an active fullscreen player was asked to exit. */
    fun exitFullscreen(): Boolean
}

internal class EmbeddedPlayerControllerImpl : EmbeddedPlayerController {
    private var binding: ControllerBinding? = null

    override fun replayFrom(second: Float) {
        binding?.replayFrom?.invoke(second.validSecond())
    }

    override fun retry() {
        binding?.retry?.invoke()
    }

    override fun exitFullscreen(): Boolean = binding?.exitFullscreen?.invoke() == true

    internal fun bind(
        replayFrom: (Float) -> Unit,
        retry: () -> Unit,
        exitFullscreen: () -> Boolean,
    ): Any {
        val token = Any()
        binding = ControllerBinding(token, replayFrom, retry, exitFullscreen)
        return token
    }

    internal fun unbind(token: Any) {
        if (binding?.token === token) binding = null
    }

    private data class ControllerBinding(
        val token: Any,
        val replayFrom: (Float) -> Unit,
        val retry: () -> Unit,
        val exitFullscreen: () -> Boolean,
    )
}

@Composable
internal fun rememberEmbeddedPlayerController(): EmbeddedPlayerControllerImpl =
    remember { EmbeddedPlayerControllerImpl() }

/**
 * Hosts the official YouTube IFrame surface without placing app UI over the video.
 * The caller owns the outer aspect ratio; this native view always matches that container.
 */
@Composable
internal fun EmbeddedYouTubePlayer(
    videoId: String,
    resumeSecond: Float,
    controller: EmbeddedPlayerControllerImpl,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onCurrentSecond: (Float) -> Unit = {},
    onDuration: (Float) -> Unit = {},
    onStateChange: (PlaybackState) -> Unit = {},
    onVideoId: (String) -> Unit = {},
    onError: (PlaybackFailure) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnCurrentSecond by rememberUpdatedState(onCurrentSecond)
    val currentOnDuration by rememberUpdatedState(onDuration)
    val currentOnStateChange by rememberUpdatedState(onStateChange)
    val currentOnVideoId by rememberUpdatedState(onVideoId)
    val currentOnError by rememberUpdatedState(onError)
    var retryGeneration by remember(videoId) { mutableIntStateOf(0) }
    val latestSecond = remember(videoId) { floatArrayOf(resumeSecond.validSecond()) }

    LaunchedEffect(videoId, resumeSecond) {
        if (resumeSecond.isFinite() && resumeSecond > latestSecond[0]) {
            latestSecond[0] = resumeSecond
        }
    }

    key(videoId, retryGeneration) {
        var player by remember { mutableStateOf<YouTubePlayer?>(null) }
        var ready by remember { mutableStateOf(false) }
        var initializationFailed by remember { mutableStateOf(false) }
        var pendingReplaySecond by remember { mutableStateOf<Float?>(null) }
        var fullscreenSession by remember { mutableStateOf<FullscreenSession?>(null) }

        val playerView = remember {
            YouTubePlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                enableAutomaticInitialization = false
            }
        }
        val releasableView = remember { ReleasablePlayerView(playerView) }

        val listener = remember {
            object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    if (releasableView.isReleased()) return
                    player = youTubePlayer
                    ready = true
                    initializationFailed = false
                    logDebug("ready videoId=$videoId generation=$retryGeneration")
                    youTubePlayer.cueVideo(videoId, latestSecond[0])
                    pendingReplaySecond?.let { second ->
                        pendingReplaySecond = null
                        youTubePlayer.seekTo(second)
                        youTubePlayer.play()
                    }
                    currentOnStateChange(PlaybackState.READY)
                    currentOnReady()
                }

                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                    if (second.isFinite()) {
                        latestSecond[0] = second.coerceAtLeast(0f)
                        currentOnCurrentSecond(latestSecond[0])
                    }
                }

                override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                    if (duration.isFinite() && duration >= 0f) currentOnDuration(duration)
                }

                override fun onStateChange(
                    youTubePlayer: YouTubePlayer,
                    state: PlayerConstants.PlayerState,
                ) {
                    val mappedState = state.toPlaybackState()
                    logDebug("state=$state mapped=$mappedState videoId=$videoId")
                    currentOnStateChange(mappedState)
                }

                override fun onVideoId(youTubePlayer: YouTubePlayer, videoId: String) {
                    if (videoId.isNotBlank()) currentOnVideoId(videoId)
                }

                override fun onError(
                    youTubePlayer: YouTubePlayer,
                    error: PlayerConstants.PlayerError,
                ) {
                    val failure = error.toPlaybackFailure()
                    initializationFailed = true
                    Log.e(
                        EMBEDDED_PLAYER_LOG_TAG,
                        "YouTube error=${failure.code} recoverable=${failure.recoverable} videoId=$videoId",
                    )
                    currentOnStateChange(PlaybackState.ERROR)
                    currentOnError(failure)
                }
            }
        }

        val fullscreenListener = remember {
            object : FullscreenListener {
                override fun onEnterFullscreen(fullscreenView: View, exitFullscreen: () -> Unit) {
                    logDebug("enter fullscreen videoId=$videoId")
                    fullscreenSession?.detach()
                    fullscreenSession = FullscreenSession(fullscreenView, exitFullscreen)
                }

                override fun onExitFullscreen() {
                    logDebug("exit fullscreen videoId=$videoId")
                    fullscreenSession?.detach()
                    fullscreenSession = null
                }
            }
        }

        DisposableEffect(controller, playerView) {
            val token = controller.bind(
                replayFrom = { second ->
                    val activePlayer = player
                    if (ready && activePlayer != null) {
                        activePlayer.seekTo(second)
                        activePlayer.play()
                    } else {
                        pendingReplaySecond = second
                    }
                },
                retry = {
                    logDebug("retry videoId=$videoId generation=$retryGeneration")
                    fullscreenSession?.exitFullscreen?.invoke()
                    retryGeneration += 1
                },
                exitFullscreen = {
                    fullscreenSession?.let { session ->
                        session.exitFullscreen()
                        true
                    } ?: false
                },
            )
            onDispose { controller.unbind(token) }
        }

        DisposableEffect(lifecycleOwner, playerView) {
            val observer = LifecycleEventObserver { source, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME,
                    Lifecycle.Event.ON_STOP,
                    -> playerView.onStateChanged(source, event)

                    Lifecycle.Event.ON_DESTROY -> releasableView.release()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                playerView.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                fullscreenSession?.let { session ->
                    session.detach()
                    runCatching { session.exitFullscreen() }
                }
                releasableView.release()
            }
        }

        DisposableEffect(playerView, fullscreenListener, listener) {
            playerView.addFullscreenListener(fullscreenListener)
            val options = IFramePlayerOptions.Builder(context)
                .controls(1)
                .fullscreen(1)
                .autoplay(0)
                .build()
            runCatching {
                logDebug("initialize videoId=$videoId generation=$retryGeneration")
                playerView.initialize(listener, true, options)
            }.onFailure { throwable ->
                initializationFailed = true
                val failure = PlaybackFailure.Initialization(
                    throwable.message ?: throwable.javaClass.simpleName,
                )
                Log.e(EMBEDDED_PLAYER_LOG_TAG, "Initialization failed for videoId=$videoId", throwable)
                currentOnStateChange(PlaybackState.ERROR)
                currentOnError(failure)
            }
            onDispose {
                playerView.removeFullscreenListener(fullscreenListener)
                playerView.removeYouTubePlayerListener(listener)
            }
        }

        LaunchedEffect(playerView, ready, initializationFailed) {
            if (ready || initializationFailed) return@LaunchedEffect
            delay(PLAYER_INITIALIZATION_TIMEOUT_MS)
            if (!ready && !initializationFailed && !releasableView.isReleased()) {
                Log.e(EMBEDDED_PLAYER_LOG_TAG, "Initialization timed out for videoId=$videoId")
                currentOnStateChange(PlaybackState.ERROR)
                currentOnError(PlaybackFailure.InitializationTimeout)
            }
        }

        AndroidView(
            factory = { playerView },
            modifier = modifier,
        )

        fullscreenSession?.let { session ->
            FullscreenPlayerDialog(
                session = session,
                onDismissRequest = { session.exitFullscreen() },
            )
        }
    }
}

@Composable
private fun FullscreenPlayerDialog(
    session: FullscreenSession,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, dialogView).apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {
                window?.let {
                    WindowInsetsControllerCompat(it, dialogView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    attachFullscreenView(session.fullscreenView)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { container -> container.attachFullscreenView(session.fullscreenView) },
        )
        DisposableEffect(session) {
            onDispose { session.detach() }
        }
    }
}

private data class FullscreenSession(
    val fullscreenView: View,
    val exitFullscreen: () -> Unit,
) {
    fun detach() {
        (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
    }
}

private class ReleasablePlayerView(
    private val playerView: YouTubePlayerView,
) {
    private val released = AtomicBoolean(false)

    fun isReleased(): Boolean = released.get()

    fun release() {
        if (released.compareAndSet(false, true)) {
            logDebug("release")
            playerView.release()
        }
    }
}

private fun FrameLayout.attachFullscreenView(fullscreenView: View) {
    if (fullscreenView.parent === this) return
    (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
    removeAllViews()
    addView(
        fullscreenView,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
}

private fun Float.validSecond(): Float = if (isFinite()) coerceAtLeast(0f) else 0f

private fun PlayerConstants.PlayerState.toPlaybackState(): PlaybackState = when (this) {
    PlayerConstants.PlayerState.UNKNOWN,
    PlayerConstants.PlayerState.UNSTARTED,
    -> PlaybackState.IDLE

    PlayerConstants.PlayerState.VIDEO_CUED -> PlaybackState.READY
    PlayerConstants.PlayerState.PLAYING -> PlaybackState.PLAYING
    PlayerConstants.PlayerState.PAUSED -> PlaybackState.PAUSED
    PlayerConstants.PlayerState.BUFFERING -> PlaybackState.BUFFERING
    PlayerConstants.PlayerState.ENDED -> PlaybackState.ENDED
}

private fun PlayerConstants.PlayerError.toPlaybackFailure(): PlaybackFailure.YouTube {
    val recoverable = when (this) {
        PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST,
        PlayerConstants.PlayerError.VIDEO_NOT_FOUND,
        PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER,
        PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER,
        -> false

        PlayerConstants.PlayerError.UNKNOWN,
        PlayerConstants.PlayerError.HTML_5_PLAYER,
        -> true
    }
    return PlaybackFailure.YouTube(name, recoverable)
}

private fun logDebug(message: String) {
    if (BuildConfig.DEBUG) Log.d(EMBEDDED_PLAYER_LOG_TAG, message)
}
