package com.kienhoang.dualsubreplay.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.NativeComment
import com.kienhoang.dualsubreplay.data.NativeLibraryState
import com.kienhoang.dualsubreplay.data.NativeVideo
import com.kienhoang.dualsubreplay.data.NativeWatchDetails

internal enum class NativeWatchTab(
    val title: Int,
) {
    SUBTITLES(R.string.native_tab_subtitles),
    UP_NEXT(R.string.native_tab_up_next),
    COMMENTS(R.string.native_tab_comments),
    ABOUT(R.string.native_tab_about),
}

/** Everything under the video on the watch screen: details, then subtitles, up next, comments and about. */
@Composable
internal fun NativeWatchPane(
    viewModel: NativeModeViewModel,
    videoId: String,
    library: NativeLibraryState,
    host: NativeModeHost,
    modifier: Modifier = Modifier,
) {
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val load = watch[videoId]
    LaunchedEffect(videoId) { viewModel.loadWatch(videoId) }
    val details = load?.value
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Surface(modifier.testTag("native_watch"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            NativeWatchHeader(details, videoId, library, viewModel, host)
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                NativeWatchTab.entries.forEachIndexed { index, item ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(stringResource(item.title), maxLines = 1) },
                        modifier = Modifier.testTag("native_watch_tab_${item.name.lowercase()}"),
                    )
                }
            }
            val content = Modifier.weight(1f).fillMaxWidth()
            when (NativeWatchTab.entries[tab]) {
                NativeWatchTab.SUBTITLES -> host.subtitles(content)
                NativeWatchTab.UP_NEXT ->
                    NativeUpNext(
                        viewModel,
                        videoId,
                        details,
                        load?.loading == true,
                        load?.failed == true,
                        host,
                        content,
                    )
                NativeWatchTab.COMMENTS -> NativeComments(viewModel, videoId, details, content)
                NativeWatchTab.ABOUT -> NativeAbout(details, content)
            }
        }
    }
}

@Composable
private fun NativeWatchHeader(
    details: NativeWatchDetails?,
    videoId: String,
    library: NativeLibraryState,
    viewModel: NativeModeViewModel,
    host: NativeModeHost,
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.native_share_video)
    val fallback = library.history.firstOrNull { it.videoId == videoId }
    val title = details?.title?.takeIf { it.isNotBlank() } ?: fallback?.title.orEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(details?.viewsText, details?.dateText).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        details?.channel?.let { channel ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clickable { viewModel.open(NativeScreen.Channel(channel.channelId)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NativeAvatar(channel.avatarUrl, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(channel.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channel.detailText.isNotBlank()) {
                        Text(
                            channel.detailText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                NativeSubscribeButton(
                    library.subscriptions.any { it.channelId == channel.channelId },
                ) { viewModel.toggleSubscription(channel) }
            }
        }
        val video =
            NativeVideo(
                videoId,
                title,
                details?.channel?.title ?: fallback?.channelName.orEmpty(),
                details?.channel?.channelId ?: fallback?.channelId,
            )
        val saved = library.watchLater.any { it.videoId == videoId }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { viewModel.library.toggleWatchLater(video) },
                label = { Text(stringResource(if (saved) R.string.native_saved else R.string.native_watch_later)) },
                leadingIcon = { Icon(if (saved) Icons.Default.WatchLater else Icons.Outlined.WatchLater, contentDescription = null) },
                modifier = Modifier.testTag("native_watch_later"),
            )
            AssistChip(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/$videoId")
                    runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
                },
                label = { Text(stringResource(R.string.native_share)) },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            )
            AssistChip(
                onClick = host.switchBack,
                label = { Text(stringResource(R.string.native_classic_page)) },
                leadingIcon = { Icon(Icons.Default.Web, contentDescription = null) },
                modifier = Modifier.testTag("native_classic_page"),
            )
        }
    }
}

@Composable
private fun NativeUpNext(
    viewModel: NativeModeViewModel,
    videoId: String,
    details: NativeWatchDetails?,
    loading: Boolean,
    failed: Boolean,
    host: NativeModeHost,
    modifier: Modifier,
) {
    LazyColumn(modifier.testTag("native_up_next")) {
        if (details != null) {
            items(details.related.items, key = { it.key }) { item ->
                NativeItemRow(
                    item = item,
                    subscribed = { false },
                    onVideo = { host.play(it, null) },
                    onChannel = { viewModel.open(NativeScreen.Channel(it.channelId)) },
                    onSubscribe = viewModel::toggleSubscription,
                    onPlaylist = { viewModel.open(NativeScreen.Playlist(it.playlistId)) },
                    large = false,
                )
            }
            item { NativeLoadMore(viewModel, details.related.continuation) { viewModel.moreRelated(videoId) } }
        }
        when {
            loading && details == null -> item { NativeLoadingRow() }
            failed && details == null -> item { NativeErrorRow({ viewModel.loadWatch(videoId, force = true) }) }
        }
    }
}

@Composable
private fun NativeComments(
    viewModel: NativeModeViewModel,
    videoId: String,
    details: NativeWatchDetails?,
    modifier: Modifier,
) {
    val all by viewModel.comments.collectAsStateWithLifecycle()
    val state = all[videoId]
    LaunchedEffect(videoId, details?.commentsContinuation) { viewModel.loadComments(videoId) }
    val page = state?.page?.value
    LazyColumn(modifier.testTag("native_comments")) {
        item {
            Text(
                listOfNotNull(page?.countText, stringResource(R.string.native_comments_read_only)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (page != null) {
            items(page.comments, key = { it.id }) { comment ->
                val replies = state?.replies?.get(comment.id)
                NativeCommentItem(
                    comment,
                    replies?.value?.comments,
                    replies?.loading == true,
                    onReplies = { viewModel.loadReplies(videoId, comment) },
                )
            }
            if (page.comments.isEmpty()) item { NativeEmptyRow(stringResource(R.string.native_no_comments)) }
            item { NativeLoadMore(viewModel, page.continuation) { viewModel.loadComments(videoId, more = true) } }
        }
        when {
            details != null && details.commentsContinuation == null && page == null ->
                item {
                    NativeEmptyRow(stringResource(R.string.native_no_comments))
                }
            state?.page?.loading == true && page == null -> item { NativeLoadingRow() }
            state?.page?.failed == true && page == null -> item { NativeErrorRow({ viewModel.loadComments(videoId) }) }
            details == null -> item { NativeLoadingRow() }
        }
    }
}

@Composable
private fun NativeCommentItem(
    comment: NativeComment,
    replies: List<NativeComment>?,
    loadingReplies: Boolean,
    onReplies: () -> Unit,
    reply: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var showReplies by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (reply) 52.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NativeAvatar(comment.avatarUrl, if (reply) 24.dp else 32.dp)
            Column(Modifier.weight(1f)) {
                if (comment.pinned) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.native_pinned),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    listOf(comment.author, comment.publishedText).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (comment.byCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        comment.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
                if (comment.likesText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            Icons.Default.ThumbUp,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            comment.likesText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!reply && comment.replyCount > 0 && comment.repliesContinuation != null) {
                    TextButton(onClick = {
                        showReplies = !showReplies
                        if (showReplies) onReplies()
                    }) {
                        Text(
                            if (showReplies) {
                                stringResource(
                                    R.string.native_hide_replies,
                                )
                            } else {
                                pluralStringResource(R.plurals.native_replies, comment.replyCount, comment.replyCount)
                            },
                        )
                    }
                }
            }
        }
        if (showReplies) {
            if (loadingReplies && replies == null) NativeLoadingRow()
            replies?.forEach { NativeCommentItem(it, null, false, {}, reply = true) }
        }
    }
}

@Composable
private fun NativeAbout(
    details: NativeWatchDetails?,
    modifier: Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag("native_about")) {
        if (details == null) {
            NativeLoadingRow()
            return@Column
        }
        Text(details.title, style = MaterialTheme.typography.titleMedium)
        val meta = listOf(details.viewsText, details.dateText).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        SelectionContainer {
            Text(
                details.description.ifBlank { stringResource(R.string.native_no_description) },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
