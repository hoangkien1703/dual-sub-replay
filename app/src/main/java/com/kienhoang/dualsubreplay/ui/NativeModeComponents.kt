package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.NativeChannel
import com.kienhoang.dualsubreplay.data.NativeItem
import com.kienhoang.dualsubreplay.data.NativePlaylist
import com.kienhoang.dualsubreplay.data.NativeVideo

@Composable
internal fun NativeThumbnail(
    videoId: String?,
    modifier: Modifier = Modifier,
    badge: String = "",
    live: Boolean = false,
) {
    val image = rememberNativeImage(videoId?.let(::nativeThumbnailUrl))
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        val label = if (live) stringResource(R.string.native_live) else badge
        if (label.isNotBlank()) {
            Surface(
                color = if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.scrim.copy(alpha = 0.8f),
                contentColor = if (live) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
    }
}

@Composable
internal fun NativeAvatar(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val image = rememberNativeImage(url)
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/** A full-width card like YouTube's home feed. */
@Composable
internal fun NativeVideoCard(
    video: NativeVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
            ).padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("native_video_${video.videoId}"),
    ) {
        NativeThumbnail(video.videoId, Modifier.fillMaxWidth(), video.durationText, video.live)
        Spacer(Modifier.size(8.dp))
        Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val details = listOf(video.channelName, video.detailText).filter { it.isNotBlank() }.joinToString(" · ")
        if (details.isNotBlank()) {
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A compact row with the thumbnail on the left, for Up next, playlists and the library. */
@Composable
internal fun NativeVideoRow(
    video: NativeVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
            ).padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("native_video_${video.videoId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NativeThumbnail(video.videoId, Modifier.width(150.dp), video.durationText, video.live)
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (video.channelName.isNotBlank()) {
                Text(
                    video.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (video.detailText.isNotBlank()) {
                Text(
                    video.detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun NativeChannelRow(
    channel: NativeChannel,
    subscribed: Boolean,
    onClick: () -> Unit,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NativeAvatar(channel.avatarUrl, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(channel.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.detailText.isNotBlank()) {
                Text(
                    channel.detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NativeSubscribeButton(subscribed, onSubscribe)
    }
}

@Composable
internal fun NativeSubscribeButton(
    subscribed: Boolean,
    onClick: () -> Unit,
) {
    if (subscribed) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.testTag("native_subscribe"),
        ) { Text(stringResource(R.string.native_subscribed)) }
    } else {
        Button(onClick = onClick, modifier = Modifier.testTag("native_subscribe")) { Text(stringResource(R.string.native_subscribe)) }
    }
}

@Composable
internal fun NativePlaylistRow(
    playlist: NativePlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(150.dp)) {
            NativeThumbnail(playlist.thumbnailVideoId, Modifier.fillMaxWidth(), playlist.countText)
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (playlist.channelName.isNotBlank()) {
                Text(
                    playlist.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One search or browse result of any kind. */
@Composable
internal fun NativeItemRow(
    item: NativeItem,
    subscribed: (String) -> Boolean,
    onVideo: (NativeVideo) -> Unit,
    onChannel: (NativeChannel) -> Unit,
    onSubscribe: (NativeChannel) -> Unit,
    onPlaylist: (NativePlaylist) -> Unit,
    large: Boolean = true,
) {
    when (item) {
        is NativeVideo -> if (large) NativeVideoCard(item, { onVideo(item) }) else NativeVideoRow(item, { onVideo(item) })
        is NativeChannel -> NativeChannelRow(item, subscribed(item.channelId), { onChannel(item) }, { onSubscribe(item) })
        is NativePlaylist -> NativePlaylistRow(item, { onPlaylist(item) })
    }
}

@Composable
internal fun NativeLoadingRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

@Composable
internal fun NativeErrorRow(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(24.dp).testTag("native_error"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.native_loading_failed), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.native_retry)) }
    }
}

@Composable
internal fun NativeEmptyRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(24.dp),
    )
}

/** Asks for the next page as soon as the end of a list is on screen; after a failure, offers Try again. */
@Composable
internal fun NativeLoadMore(
    viewModel: NativeModeViewModel,
    key: String?,
    onMore: () -> Unit,
) {
    if (key == null) return
    val failedPages by viewModel.failedPages.collectAsStateWithLifecycle()
    if (key in failedPages) {
        NativeErrorRow({ viewModel.retryPage(key) })
        return
    }
    LaunchedEffect(key) { onMore() }
    NativeLoadingRow()
}

@Composable
internal fun NativeSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}
