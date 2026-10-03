package com.kienhoang.dualsubreplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How long the pill stays after the learner stops scrolling, long enough to reach and tap it. */
internal const val JUMP_BACK_LINGER_MS = 2_000L

/** Where the spoken row is relative to what the transcript shows. */
internal enum class ActiveRowPlacement { NONE, VISIBLE, ABOVE, BELOW }

/** A row the list has laid out: its [index] and its [offset] and [size] in pixels. */
internal data class LaidOutRow(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * Where row [target] is. It counts as visible when at least half of it is on screen (half the
 * viewport for a row taller than the viewport), so a sliver at the edge still offers the way back.
 */
internal fun activeRowPlacement(
    target: Int,
    rows: List<LaidOutRow>,
    viewportStart: Int,
    viewportEnd: Int,
): ActiveRowPlacement {
    if (target < 0 || rows.isEmpty()) return ActiveRowPlacement.NONE
    val row = rows.firstOrNull { it.index == target }
    if (row == null) return if (target < rows.first().index) ActiveRowPlacement.ABOVE else ActiveRowPlacement.BELOW
    val shown = min(row.offset + row.size, viewportEnd) - max(row.offset, viewportStart)
    return when {
        shown * 2 >= min(row.size, viewportEnd - viewportStart) -> ActiveRowPlacement.VISIBLE
        row.offset < viewportStart -> ActiveRowPlacement.ABOVE
        else -> ActiveRowPlacement.BELOW
    }
}

/** The pill shows while the learner is scrolling, or just stopped, with the spoken row off screen. */
internal fun jumpBackPillVisible(
    placement: ActiveRowPlacement,
    browsing: Boolean,
): Boolean = browsing && (placement == ActiveRowPlacement.ABOVE || placement == ActiveRowPlacement.BELOW)

/** A playback position as YouTube shows it: 0:09, 12:05, 1:02:03. */
internal fun formatPlaybackClock(ms: Long): String {
    val seconds = (ms / 1_000).coerceAtLeast(0)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

private fun LazyListState.placementOf(target: Int): ActiveRowPlacement {
    val layout = layoutInfo
    return activeRowPlacement(
        target = target,
        rows = layout.visibleItemsInfo.map { LaidOutRow(it.index, it.offset, it.size) },
        viewportStart = 0,
        viewportEnd = layout.viewportEndOffset - layout.afterContentPadding,
    )
}

/**
 * True from the moment the learner drags the list until [JUMP_BACK_LINGER_MS] after the list
 * stops moving. Scrolls the app makes itself (following playback) do not count.
 */
@Composable
private fun rememberLearnerBrowsing(listState: LazyListState): MutableState<Boolean> {
    val browsing = remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) browsing.value = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to browsing.value }.collectLatest { (scrolling, active) ->
            if (active && !scrolling) {
                delay(JUMP_BACK_LINGER_MS)
                browsing.value = false
            }
        }
    }
    return browsing
}

/**
 * "Now playing · 0:09": offers a way back to the spoken line while the learner scrolls the
 * transcript away from it. It hides shortly after they stop to read, comes back when they scroll
 * again, and never shows while the spoken line is on screen. Tapping it scrolls back.
 */
@Composable
internal fun JumpBackPill(
    listState: LazyListState,
    target: Int,
    startMs: Long?,
    modifier: Modifier = Modifier,
) {
    val browsing = rememberLearnerBrowsing(listState)
    val placement by remember(listState, target) { derivedStateOf { listState.placementOf(target) } }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = startMs != null && jumpBackPillVisible(placement, browsing.value),
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
    ) {
        val primary = MaterialTheme.colorScheme.primary
        Surface(
            onClick = {
                browsing.value = false
                scope.launch {
                    if (abs(target - listState.firstVisibleItemIndex) > SUBTITLE_INSTANT_SCROLL_DISTANCE) {
                        listState.scrollToItem(target)
                    } else {
                        listState.animateScrollToItem(target)
                    }
                }
            },
            shape = CircleShape,
            color = Color(0xFF0A2B30),
            contentColor = primary,
            border = BorderStroke(1.dp, primary.copy(alpha = 0.6f)),
            shadowElevation = 6.dp,
            modifier = Modifier.padding(bottom = 12.dp).testTag("jump_back_pill"),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (placement == ActiveRowPlacement.ABOVE) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(R.string.player_now_playing, formatPlaybackClock(startMs ?: 0)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
