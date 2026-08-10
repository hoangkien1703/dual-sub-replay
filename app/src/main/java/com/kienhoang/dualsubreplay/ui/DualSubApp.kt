package com.kienhoang.dualsubreplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme

@Composable
fun DualSubApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val webController = remember { YouTubeWebController() }

    DualSubTheme {
        DualSubExperience(
            state = state,
            webController = webController,
            onBrowserUrlChanged = viewModel::onBrowserUrlChanged,
            onPlaybackSecond = viewModel::updatePlaybackSecond,
            onShowSubtitles = viewModel::showSubtitlePanel,
            onHideSubtitles = viewModel::hideSubtitlePanel,
            onRetry = viewModel::retryCaptions,
            onSourceChange = viewModel::setSourcePreference,
            onFontScaleChange = viewModel::setFontScale,
        )
    }
}

@Composable
private fun DualSubExperience(
    state: DualSubUiState,
    webController: YouTubeWebController,
    onBrowserUrlChanged: (String) -> Unit,
    onPlaybackSecond: (Float) -> Unit,
    onShowSubtitles: () -> Unit,
    onHideSubtitles: () -> Unit,
    onRetry: () -> Unit,
    onSourceChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var playerBottomFraction by remember { mutableStateOf(0.38f) }
    val panelVisible = state.videoId != null && state.subtitlePanelVisible
    val panelHeightFraction = (1f - playerBottomFraction).coerceIn(0.42f, 0.78f)

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        YouTubeWebPage(
            controller = webController,
            initialUrl = state.currentPageUrl,
            navigationRequestId = state.navigationRequestId,
            watchPageActive = state.videoId != null,
            onUrlChanged = onBrowserUrlChanged,
            onPlaybackSecond = onPlaybackSecond,
            onPlayerBottomFraction = { playerBottomFraction = it },
        )

        AnimatedVisibility(
            visible = panelVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            SubtitlePanel(
                state = state,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(panelHeightFraction),
                onHide = onHideSubtitles,
                onSettings = { showSettings = true },
                onRetry = onRetry,
                onReplay = { segment -> webController.replayFrom(segment.startMs / 1_000f) },
            )
        }

        if (state.videoId != null && !panelVisible) {
            SmallFloatingActionButton(
                onClick = {
                    webController.focusPlayer()
                    onShowSubtitles()
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.ClosedCaption, contentDescription = "Show dual subtitles")
            }
        }
    }

    if (showSettings) {
        SubtitleSettingsDialog(
            sourcePreference = state.sourcePreference,
            fontScale = state.fontScale,
            onSourceChange = onSourceChange,
            onFontScaleChange = onFontScaleChange,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun SubtitlePanel(
    state: DualSubUiState,
    modifier: Modifier,
    onHide: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onReplay: (SubtitleSegment) -> Unit,
) {
    Surface(
        modifier = modifier.shadow(18.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = Color(0xFF061719),
        contentColor = Color(0xFFF3FAFA),
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                Surface(Modifier.fillMaxSize(), shape = CircleShape, color = Color(0xFF607477)) {}
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ClosedCaption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sourceDescription(state),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFF3FAFA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.statusMessage ?: "Tap a paragraph to replay it",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB7CED1),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Subtitle settings",
                        tint = Color(0xFFE5F2F3),
                    )
                }
                IconButton(onClick = onHide) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Hide dual subtitles",
                        tint = Color(0xFFE5F2F3),
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF244044))

            when {
                state.errorMessage != null -> CompactErrorPanel(state.errorMessage, onRetry)
                state.segments.isEmpty() -> CompactLoadingPanel(state.statusMessage ?: "Loading captions…")
                else -> SubtitleTimeline(state, onReplay)
            }
        }
    }
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        itemsIndexed(state.segments, key = { _, segment -> segment.id }) { index, segment ->
            CompactSubtitleCard(
                segment = segment,
                active = index == state.currentIndex,
                fontScale = state.fontScale,
                onReplay = { onReplay(segment) },
            )
        }
    }
}

@Composable
private fun CompactSubtitleCard(
    segment: SubtitleSegment,
    active: Boolean,
    fontScale: Float,
    onReplay: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onReplay),
        border = BorderStroke(
            width = if (active) 2.dp else 1.dp,
            color = if (active) MaterialTheme.colorScheme.primary else Color(0xFF183034),
        ),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (active) Color(0xFF0A2B30) else Color(0xFF081D20),
            contentColor = Color(0xFFF3FAFA),
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary else Color(0xFF24383B),
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Replay this paragraph",
                        modifier = Modifier.size(22.dp),
                        tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.size(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = segment.originalText,
                    fontSize = (17 * fontScale).sp,
                    lineHeight = (22 * fontScale).sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = Color(0xFFF3FAFA),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = segment.translatedText ?: "Translating…",
                    fontSize = (14 * fontScale).sp,
                    lineHeight = (18 * fontScale).sp,
                    color = Color(0xFF9EDCE4),
                )
            }
        }
    }
}

@Composable
private fun CompactLoadingPanel(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFFB7CED1))
        }
    }
}

@Composable
private fun CompactErrorPanel(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text("Retry captions")
            }
        }
    }
}

@Composable
private fun SubtitleSettingsDialog(
    sourcePreference: String,
    fontScale: Float,
    onSourceChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dual-subtitle settings") },
        text = {
            Column {
                Text("Original language")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Auto", "en" to "English", "ja" to "Japanese").forEach { (code, label) ->
                        FilterChip(
                            selected = sourcePreference == code,
                            onClick = { onSourceChange(code) },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Translation: Vietnamese")
                Spacer(Modifier.height(14.dp))
                Text("Text size: ${(fontScale * 100).toInt()}%")
                Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = 0.8f..1.5f)
                Text(
                    "Captions continue tracking while the panel is hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun sourceDescription(state: DualSubUiState): String {
    val source = when (state.resolvedSourceLanguage?.substringBefore('-')) {
        "en" -> "English"
        "ja" -> "Japanese"
        null -> "Finding captions"
        else -> state.resolvedSourceLanguage.orEmpty().uppercase()
    }
    val generated = if (state.generatedCaptions) " (auto-generated)" else ""
    return "$source$generated  →  Vietnamese"
}
