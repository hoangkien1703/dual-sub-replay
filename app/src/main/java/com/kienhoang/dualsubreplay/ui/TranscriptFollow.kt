package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

internal const val TRANSCRIPT_FOLLOW_PREFERENCE = "transcript_follow"

/** How long the transcript takes to bring the next line to the middle. */
private const val CENTRE_SCROLL_MS = 320

/** Where the playing line sits while the transcript follows playback (discussion #81, 2026-11-03). */
enum class TranscriptFollow(
    val storageValue: String,
    @StringRes val labelRes: Int,
) {
    /** The playing line walks down the list and jumps back to the top at the bottom (the default). */
    PAGE("page", R.string.settings_transcript_follow_page),

    /** The playing line stays in the middle and the list scrolls up under it, like song lyrics. */
    CENTER("center", R.string.settings_transcript_follow_center),
}

internal fun storedTranscriptFollow(raw: String?): TranscriptFollow =
    TranscriptFollow.entries.firstOrNull { it.storageValue == raw } ?: TranscriptFollow.PAGE

/**
 * Pixels to scroll so a row at [offset] with [size] has its middle at the middle of a viewport
 * ending at [viewportEnd]; positive scrolls forward. A row taller than the viewport aligns its top.
 */
internal fun centreScrollDelta(
    offset: Int,
    size: Int,
    viewportEnd: Int,
): Int = if (size >= viewportEnd) offset else offset + size / 2 - viewportEnd / 2

/**
 * Brings row [target] to the middle of the list. A row already laid out glides there when
 * [animate]; otherwise it is placed at once, then moved by its measured size.
 */
internal suspend fun LazyListState.centreRow(
    target: Int,
    animate: Boolean,
) {
    val row = layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
    if (animate && row != null) {
        val delta = centreScrollDelta(row.offset, row.size, viewportEnd())
        if (delta != 0) animateScrollBy(delta.toFloat(), tween(CENTRE_SCROLL_MS, easing = FastOutSlowInEasing))
        return
    }
    scrollToItem(target)
    val placed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == target } ?: return
    val delta = centreScrollDelta(placed.offset, placed.size, viewportEnd())
    if (delta != 0) scrollBy(delta.toFloat())
}

private fun LazyListState.viewportEnd(): Int = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptFollowSettings(
    follow: TranscriptFollow,
    onChange: (TranscriptFollow) -> Unit,
) {
    Text(
        stringResource(R.string.settings_transcript_follow_title),
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(R.string.settings_transcript_follow_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TranscriptFollow.entries.forEach { option ->
            FilterChip(
                selected = follow == option,
                onClick = { onChange(option) },
                label = { Text(stringResource(option.labelRes)) },
                modifier = Modifier.testTag("transcript_follow_${option.storageValue}"),
            )
        }
    }
}
