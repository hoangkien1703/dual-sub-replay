package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

@Composable
fun DualSubApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DualSubTheme {
        DualSubScreen(
            state = state,
            onInputChange = viewModel::updateInput,
            onOpenVideo = viewModel::loadVideo,
            onSourceChange = viewModel::setSourcePreference,
            onFontScaleChange = viewModel::setFontScale,
            onPlaybackSecond = viewModel::updatePlaybackSecond,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualSubScreen(
    state: DualSubUiState,
    onInputChange: (String) -> Unit,
    onOpenVideo: () -> Unit,
    onSourceChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onPlaybackSecond: (Float) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Subtitles, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Text("DualSub Replay", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Subtitle settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LinkEntry(
                value = state.inputUrl,
                loading = state.stage == LoadStage.LOADING_CAPTIONS,
                onValueChange = onInputChange,
                onOpen = onOpenVideo,
            )

            state.videoId?.let { videoId ->
                YouTubePlayerPanel(
                    videoId = videoId,
                    onPlayerReady = { player = it },
                    onPlaybackSecond = onPlaybackSecond,
                )
            }

            LanguageControls(state, onSourceChange)

            when {
                state.errorMessage != null -> ErrorPanel(state.errorMessage, onOpenVideo)
                state.stage == LoadStage.IDLE -> WelcomePanel()
                state.segments.isEmpty() -> LoadingPanel(state.statusMessage ?: "Loading captions…")
                else -> SubtitleTimeline(
                    state = state,
                    onReplay = { segment ->
                        player?.seekTo(segment.startMs / 1_000f)
                        player?.play()
                    },
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            fontScale = state.fontScale,
            onFontScaleChange = onFontScaleChange,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun LinkEntry(
    value: String,
    loading: Boolean,
    onValueChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = { Text("YouTube link") },
            placeholder = { Text("Paste or share a video") },
            leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
        )
        Button(onClick = onOpen, enabled = !loading && value.isNotBlank()) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.PlayArrow, contentDescription = "Open video")
        }
    }
}

@Composable
private fun YouTubePlayerPanel(
    videoId: String,
    onPlayerReady: (YouTubePlayer) -> Unit,
    onPlaybackSecond: (Float) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playerView = remember {
        YouTubePlayerView(context).apply {
            enableAutomaticInitialization = false
            lifecycle.addObserver(this)
        }
    }
    var initialized by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<YouTubePlayer?>(null) }

    LaunchedEffect(videoId, activePlayer) {
        activePlayer?.loadVideo(videoId, 0f)
    }

    DisposableEffect(playerView) {
        onDispose {
            lifecycle.removeObserver(playerView)
            playerView.release()
        }
    }

    AndroidView(
        factory = {
            playerView.apply {
                if (!initialized) {
                    initialized = true
                    val options = IFramePlayerOptions.Builder(context)
                        .controls(1)
                        .fullscreen(1)
                        .build()
                    initialize(
                        object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                activePlayer = youTubePlayer
                                onPlayerReady(youTubePlayer)
                            }

                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                onPlaybackSecond(second)
                            }
                        },
                        options,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
}

@Composable
private fun LanguageControls(state: DualSubUiState, onSourceChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Original:", style = MaterialTheme.typography.labelLarge)
            listOf("auto" to "Auto", "en" to "English", "ja" to "Japanese").forEach { (code, label) ->
                FilterChip(
                    selected = state.sourcePreference == code,
                    onClick = { onSourceChange(code) },
                    label = { Text(label) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Translation: Vietnamese", color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.weight(1f))
            state.statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun SubtitleTimeline(state: DualSubUiState, onReplay: (SubtitleSegment) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(state.currentIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.segments, key = { _, segment -> segment.id }) { index, segment ->
            SubtitleCard(
                segment = segment,
                active = index == state.currentIndex,
                fontScale = state.fontScale,
                onReplay = { onReplay(segment) },
            )
        }
    }
}

@Composable
private fun SubtitleCard(
    segment: SubtitleSegment,
    active: Boolean,
    fontScale: Float,
    onReplay: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onReplay),
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (active) Color(0xFF0A292E) else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier.size(44.dp).clickable(onClick = onReplay),
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Replay this paragraph",
                        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    segment.originalText,
                    fontSize = (21 * fontScale).sp,
                    lineHeight = (28 * fontScale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    segment.translatedText ?: "Translating…",
                    fontSize = (16 * fontScale).sp,
                    lineHeight = (22 * fontScale).sp,
                    color = if (segment.translatedText == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun WelcomePanel() {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Subtitles, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(18.dp))
            Text("Learn from the videos you already love", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "In YouTube, tap Share → DualSub Replay. Captions will appear as replayable English or Japanese paragraphs with Vietnamese translations.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Automatic captions are experimental. Try another captioned video or retry after YouTube finishes generating captions.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null)
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitle settings") },
        text = {
            Column {
                Text("Text size: ${(fontScale * 100).toInt()}%")
                Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = 0.8f..1.5f)
                Text(
                    "Translations run on your phone. The first use downloads a language model of about 30 MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
