package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.NativeChannel
import com.kienhoang.dualsubreplay.data.NativeLibraryState
import com.kienhoang.dualsubreplay.data.NativePlaylist
import com.kienhoang.dualsubreplay.data.NativeVideo
import kotlinx.coroutines.flow.MutableStateFlow

/** What the native screens need from the rest of the app: the page video and the subtitle panel. */
internal class NativeModeHost(
    val activeVideoId: String?,
    val playing: Boolean,
    val play: (NativeVideo, String?) -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val stop: () -> Unit,
    val switchBack: () -> Unit,
    val subtitles: @Composable (Modifier) -> Unit,
)

/** Native mode as the main screen sees it: whether it is on, and how it opens or closes the page video. */
internal class NativeModeBinding(
    val viewModel: NativeModeViewModel,
    val enabled: Boolean,
    val openVideo: (videoId: String, playlistId: String?) -> Unit,
    val closeVideo: () -> Unit,
)

/** True while a native screen covers the YouTube page, so overlays drawn beside the app stay hidden. */
internal val nativeModeCoversPage = MutableStateFlow(false)

/**
 * Native mode's screens. The watch screen leaves [videoHeight] at the top transparent, where the
 * one YouTube page shows only its player; every other screen covers the page completely.
 */
@Composable
internal fun NativeModeShell(
    viewModel: NativeModeViewModel,
    host: NativeModeHost,
    videoHeight: Dp,
    showWatch: Boolean,
    modifier: Modifier = Modifier,
) {
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val library by viewModel.library.state.collectAsStateWithLifecycle()
    val top = stack.last()
    val covers = top !is NativeScreen.Watch
    DisposableEffect(covers) {
        nativeModeCoversPage.value = covers
        onDispose { nativeModeCoversPage.value = false }
    }

    if (top is NativeScreen.Watch) {
        if (showWatch) {
            NativeWatchPane(viewModel, top.videoId, library, host, modifier.fillMaxSize().padding(top = videoHeight))
        }
        return
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            var editing by remember { mutableStateOf(false) }
            val focusManager = LocalFocusManager.current
            NativeSearchHeader(
                current = (top as? NativeScreen.Search)?.query.orEmpty(),
                canGoBack = stack.size > 1,
                onBack = { viewModel.back() },
                onSearch = { query ->
                    editing = false
                    viewModel.open(NativeScreen.Search(query))
                },
                onEditingChange = { editing = it },
            )
            Box(Modifier.weight(1f)) {
                when (top) {
                    NativeScreen.Home -> NativeHomeContent(viewModel, library, host)
                    is NativeScreen.Search -> NativeSearchContent(viewModel, top.query, library, host)
                    is NativeScreen.Channel -> NativeChannelContent(viewModel, top.channelId, library, host)
                    is NativeScreen.Playlist -> NativePlaylistContent(viewModel, top.playlistId, host)
                    NativeScreen.Library -> NativeLibraryContent(viewModel, library, host)
                    is NativeScreen.Watch -> Unit
                }
                if (editing && library.searches.isNotEmpty()) {
                    NativeRecentSearches(library.searches, onPick = { query ->
                        focusManager.clearFocus()
                        editing = false
                        viewModel.open(NativeScreen.Search(query))
                    }, onForget = viewModel.library::forgetSearch)
                }
            }
            host.activeVideoId?.let { videoId ->
                NativeMiniPlayer(videoId, library, viewModel, host)
            }
            NavigationBar {
                NavigationBarItem(
                    selected = top == NativeScreen.Home,
                    onClick = viewModel::goHome,
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.native_home)) },
                    modifier = Modifier.testTag("native_tab_home"),
                )
                NavigationBarItem(
                    selected = top == NativeScreen.Library,
                    onClick = { viewModel.open(NativeScreen.Library) },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text(stringResource(R.string.native_library)) },
                    modifier = Modifier.testTag("native_tab_library"),
                )
            }
        }
    }
}

@Composable
private fun NativeSearchHeader(
    current: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit,
) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    val focus = LocalFocusManager.current

    fun submit() {
        if (text.isBlank()) return
        focus.clearFocus()
        onSearch(text.trim())
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canGoBack) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("native_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.native_back))
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.native_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(
                        onClick = { text = "" },
                    ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.native_clear_search)) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            shape = MaterialTheme.shapes.extraLarge,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .onFocusChanged { onEditingChange(it.isFocused) }
                    .testTag("native_search_field"),
        )
    }
}

@Composable
private fun NativeRecentSearches(
    searches: List<String>,
    onPick: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { NativeSectionTitle(stringResource(R.string.native_recent_searches)) }
            items(searches, key = { it }) { query ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(query) }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        query,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onForget(query) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.native_forget_search))
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeBetaNotice(onSwitchBack: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("native_beta_notice"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.native_mode_beta_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSwitchBack, modifier = Modifier.testTag("native_switch_back")) {
                Text(stringResource(R.string.native_mode_switch_back))
            }
        }
    }
}

private fun openItemActions(
    viewModel: NativeModeViewModel,
    host: NativeModeHost,
): Triple<(NativeVideo) -> Unit, (NativeChannel) -> Unit, (NativePlaylist) -> Unit> =
    Triple(
        { video -> host.play(video, null) },
        { channel -> viewModel.open(NativeScreen.Channel(channel.channelId)) },
        { playlist -> viewModel.open(NativeScreen.Playlist(playlist.playlistId)) },
    )

@Composable
private fun NativeHomeContent(
    viewModel: NativeModeViewModel,
    library: NativeLibraryState,
    host: NativeModeHost,
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val (onVideo, _, _) = openItemActions(viewModel, host)
    LaunchedEffect(Unit) { viewModel.loadHome() }
    LazyColumn(Modifier.fillMaxSize().testTag("native_home")) {
        item { NativeBetaNotice(host.switchBack) }
        if (library.history.isNotEmpty()) {
            item { NativeSectionTitle(stringResource(R.string.native_home_continue)) }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(horizontal = 12.dp),
                ) {
                    items(library.history.take(12), key = { it.videoId }) { video ->
                        Column(Modifier.width(180.dp).clickable { onVideo(video) }) {
                            NativeThumbnail(video.videoId, Modifier.fillMaxWidth(), video.durationText)
                            Text(
                                video.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        val feed = home.value
        if (feed != null && feed.subscriptionVideos.isNotEmpty()) {
            item { NativeSectionTitle(stringResource(R.string.native_home_subscriptions)) }
            items(feed.subscriptionVideos.take(30), key = { "sub:" + it.videoId }) { NativeVideoCard(it, { onVideo(it) }) }
        }
        if (feed != null && feed.forYou.isNotEmpty()) {
            item { NativeSectionTitle(forYouHeading(library, feed)) }
            items(feed.forYou, key = { "for:" + it.videoId }) { NativeVideoCard(it, { onVideo(it) }) }
        }
        when {
            home.loading -> item { NativeLoadingRow() }
            home.failed -> item { NativeErrorRow({ viewModel.loadHome(force = true) }) }
            library.history.isEmpty() && (feed == null || feed.subscriptionVideos.isEmpty()) ->
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.native_home_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            stringResource(R.string.native_home_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
        }
    }
}

@Composable
private fun forYouHeading(
    library: NativeLibraryState,
    feed: NativeHomeFeed,
): String {
    val basedOn =
        library.history
            .firstOrNull { it.videoId == feed.basedOnVideoId }
            ?.title
            .orEmpty()
    return if (basedOn.isBlank()) {
        stringResource(R.string.native_home_recommended)
    } else {
        stringResource(R.string.native_home_for_you, basedOn)
    }
}

@Composable
private fun NativeSearchContent(
    viewModel: NativeModeViewModel,
    query: String,
    library: NativeLibraryState,
    host: NativeModeHost,
) {
    val results by viewModel.search.collectAsStateWithLifecycle()
    val load = results[query]
    val (onVideo, onChannel, onPlaylist) = openItemActions(viewModel, host)
    LaunchedEffect(query) { viewModel.loadSearch(query) }
    LazyColumn(Modifier.fillMaxSize().testTag("native_search_results")) {
        val page = load?.value
        if (page != null) {
            items(page.items, key = { it.key }) { item ->
                NativeItemRow(
                    item = item,
                    subscribed = { id -> library.subscriptions.any { it.channelId == id } },
                    onVideo = onVideo,
                    onChannel = onChannel,
                    onSubscribe = viewModel::toggleSubscription,
                    onPlaylist = onPlaylist,
                )
            }
            if (page.items.isEmpty()) item { NativeEmptyRow(stringResource(R.string.native_no_results)) }
            item { NativeLoadMore(viewModel, page.continuation) { viewModel.moreSearch(query) } }
        }
        when {
            load == null || (load.loading && page == null) -> item { NativeLoadingRow() }
            load.failed && page == null -> item { NativeErrorRow({ viewModel.loadSearch(query, force = true) }) }
        }
    }
}

@Composable
private fun NativeChannelContent(
    viewModel: NativeModeViewModel,
    channelId: String,
    library: NativeLibraryState,
    host: NativeModeHost,
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val load = channels[channelId]
    LaunchedEffect(channelId) { viewModel.loadChannel(channelId) }
    val page = load?.value
    LazyColumn(Modifier.fillMaxSize().testTag("native_channel")) {
        if (page != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    NativeAvatar(page.channel.avatarUrl, 80.dp)
                    Text(page.channel.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    if (page.channel.detailText.isNotBlank()) {
                        Text(
                            page.channel.detailText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    NativeSubscribeButton(library.subscriptions.any { it.channelId == page.channel.channelId }) {
                        viewModel.toggleSubscription(page.channel)
                    }
                    if (page.description.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        Text(
                            page.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded },
                        )
                    }
                }
            }
            item { NativeSectionTitle(stringResource(R.string.native_channel_videos)) }
            items(page.videos.items, key = { it.key }) { item ->
                if (item is NativeVideo) NativeVideoCard(item, { host.play(item, null) })
            }
            item { NativeLoadMore(viewModel, page.videos.continuation) { viewModel.moreChannelVideos(channelId) } }
        }
        when {
            load == null || (load.loading && page == null) -> item { NativeLoadingRow() }
            load.failed && page == null -> item { NativeErrorRow({ viewModel.loadChannel(channelId, force = true) }) }
        }
    }
}

@Composable
private fun NativePlaylistContent(
    viewModel: NativeModeViewModel,
    playlistId: String,
    host: NativeModeHost,
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val load = playlists[playlistId]
    LaunchedEffect(playlistId) { viewModel.loadPlaylist(playlistId) }
    val page = load?.value
    LazyColumn(Modifier.fillMaxSize().testTag("native_playlist")) {
        if (page != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(page.playlist.title, style = MaterialTheme.typography.titleLarge)
                    if (page.playlist.channelName.isNotBlank()) {
                        Text(
                            page.playlist.channelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(page.videos.items, key = { it.key }) { item ->
                if (item is NativeVideo) NativeVideoRow(item, { host.play(item, playlistId) })
            }
            if (page.videos.items.isEmpty()) item { NativeEmptyRow(stringResource(R.string.native_library_empty)) }
            item { NativeLoadMore(viewModel, page.videos.continuation) { viewModel.morePlaylistVideos(playlistId) } }
        }
        when {
            load == null || (load.loading && page == null) -> item { NativeLoadingRow() }
            load.failed && page == null -> item { NativeErrorRow({ viewModel.loadPlaylist(playlistId, force = true) }) }
        }
    }
}

@Composable
private fun NativeLibraryContent(
    viewModel: NativeModeViewModel,
    library: NativeLibraryState,
    host: NativeModeHost,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(R.string.native_library_history, R.string.native_watch_later, R.string.native_library_subscriptions)
    Column(Modifier.fillMaxSize().testTag("native_library")) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title), maxLines = 1) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> {
                    if (library.history.isNotEmpty()) {
                        item {
                            TextButton(onClick = viewModel.library::clearHistory, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(stringResource(R.string.native_library_clear_history))
                            }
                        }
                    }
                    items(library.history, key = { "h:" + it.videoId }) { video ->
                        NativeVideoRow(video, { host.play(video, null) }, trailing = {
                            IconButton(onClick = { viewModel.library.removeFromHistory(video.videoId) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.native_remove_from_history))
                            }
                        })
                    }
                    if (library.history.isEmpty()) item { NativeEmptyRow(stringResource(R.string.native_library_empty)) }
                }
                1 -> {
                    items(library.watchLater, key = { "w:" + it.videoId }) { video ->
                        NativeVideoRow(video, { host.play(video, null) }, trailing = {
                            IconButton(onClick = { viewModel.library.toggleWatchLater(video) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.native_remove_from_watch_later))
                            }
                        })
                    }
                    if (library.watchLater.isEmpty()) item { NativeEmptyRow(stringResource(R.string.native_library_empty)) }
                }
                else -> {
                    items(library.subscriptions, key = { "s:" + it.channelId }) { channel ->
                        NativeChannelRow(
                            channel,
                            true,
                            { viewModel.open(NativeScreen.Channel(channel.channelId)) },
                            { viewModel.toggleSubscription(channel) },
                        )
                    }
                    if (library.subscriptions.isEmpty()) item { NativeEmptyRow(stringResource(R.string.native_library_empty)) }
                }
            }
        }
    }
}

@Composable
private fun NativeMiniPlayer(
    videoId: String,
    library: NativeLibraryState,
    viewModel: NativeModeViewModel,
    host: NativeModeHost,
) {
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    LaunchedEffect(videoId) { viewModel.loadWatch(videoId) }
    val title =
        watch[videoId]?.value?.title ?: library.history
            .firstOrNull { it.videoId == videoId }
            ?.title
            .orEmpty()
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag("native_mini_player")) {
        Row(
            Modifier.fillMaxWidth().clickable { viewModel.open(NativeScreen.Watch(videoId)) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NativeThumbnail(videoId, Modifier.width(96.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = if (host.playing) host.pause else host.resume) {
                if (host.playing) {
                    Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.native_pause))
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.native_play))
                }
            }
            IconButton(onClick = host.stop) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.native_close_player)) }
        }
    }
}
