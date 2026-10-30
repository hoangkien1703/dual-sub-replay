package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.YouTubeVideoResult
import com.kienhoang.dualsubreplay.data.YouTubeVideoSearch
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Locale

// TV home (spec 2026-10-30-android-tv-layout.md): the TV layout's start page. YouTube's own pages
// are made for touch, so TV home searches YouTube itself and shows big cards a remote can reach.

internal const val TV_RECENT_SEARCHES_PREFERENCE = "tv_recent_searches"
private const val MAX_RECENT_SEARCHES = 6
private const val TV_GRID_COLUMNS = 4

/** [query] first, without an older copy (any case), at most [MAX_RECENT_SEARCHES]. */
internal fun withRecentSearch(
    recent: List<String>,
    query: String,
): List<String> {
    val clean = query.trim()
    if (clean.isEmpty()) return recent
    return (listOf(clean) + recent.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
}

/** Recent searches are kept one per line. */
internal fun parseRecentSearches(stored: String?): List<String> =
    stored
        .orEmpty()
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(MAX_RECENT_SEARCHES)

/** Continue watching: the tabs on a video. YouTube's own pages are reached with "YouTube page". */
internal fun tvContinueTabs(tabs: BrowserTabs): List<BrowserTab> = tabs.tabs.filter { browserTabThumbnailUrl(it.url) != null }

/**
 * The tab "YouTube page" shows: the open tab unless it holds the open video, else a tab already off
 * any video, so pressing it again does not pile up tabs. Null opens a new tab.
 */
internal fun tvYouTubePageTab(
    tabs: BrowserTabs,
    videoOpen: Boolean,
): Long? = tabs.active?.takeUnless { videoOpen }?.id ?: tabs.tabs.firstOrNull { browserTabThumbnailUrl(it.url) == null }?.id

/** "Channel · 12:34 · 1.2M views": the parts YouTube gave. */
internal fun tvVideoDetails(video: YouTubeVideoResult): String =
    listOf(video.channel, video.length, video.views).filter(String::isNotBlank).joinToString(" · ")

internal sealed interface TvSearchState {
    data object Idle : TvSearchState

    data class Searching(
        val query: String,
    ) : TvSearchState

    data class Found(
        val query: String,
        val videos: List<YouTubeVideoResult>,
    ) : TvSearchState

    data class Failed(
        val query: String,
    ) : TvSearchState
}

/** What TV home can open. */
internal class TvHomeActions(
    val openVideo: (videoId: String) -> Unit,
    val openTab: (tabId: Long) -> Unit,
    val openYouTubePage: () -> Unit,
    val backToVideo: () -> Unit,
    val practice: () -> Unit,
    val progress: () -> Unit,
    val settings: () -> Unit,
)

/**
 * TV home's search, kept while a video plays so the results are still there on the way back.
 * [searchRun] counts searches asked for; [searched] is the run and filter last answered.
 */
@Stable
internal class TvHomeState(
    recent: List<String>,
) {
    var recent by mutableStateOf(recent)
    var subtitlesOnly by mutableStateOf(false)
    var search by mutableStateOf<TvSearchState>(TvSearchState.Idle)
    var searchRun by mutableIntStateOf(0)
    var searched by mutableStateOf<Pair<Int, Boolean>?>(null)
}

@Composable
internal fun rememberTvHomeState(): TvHomeState {
    val context = LocalContext.current
    return remember { TvHomeState(parseRecentSearches(studyPreferences(context).getString(TV_RECENT_SEARCHES_PREFERENCE, null))) }
}

@Composable
internal fun TvHome(
    home: TvHomeState,
    tabs: BrowserTabs,
    videoOpen: Boolean,
    actions: TvHomeActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    var askQuery by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    fun runSearch(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        home.recent = withRecentSearch(home.recent, clean)
        preferences.edit().putString(TV_RECENT_SEARCHES_PREFERENCE, home.recent.joinToString("\n")).apply()
        home.search = TvSearchState.Searching(clean)
        home.searchRun += 1
    }

    LaunchedEffect(home.searchRun, home.subtitlesOnly) {
        val asked = home.searchRun to home.subtitlesOnly
        if (home.searched == asked) return@LaunchedEffect
        val query =
            when (val current = home.search) {
                is TvSearchState.Searching -> current.query
                is TvSearchState.Found -> current.query
                is TvSearchState.Failed -> current.query
                TvSearchState.Idle -> return@LaunchedEffect
            }
        home.search = TvSearchState.Searching(query)
        home.search =
            try {
                TvSearchState.Found(query, YouTubeVideoSearch.search(query, home.subtitlesOnly, Locale.getDefault().country))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IOException) {
                TvSearchState.Failed(query)
            }
        home.searched = asked
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler {
        if (videoOpen) actions.backToVideo() else (context as? Activity)?.leaveKeepingState()
    }

    Surface(
        modifier = modifier.fillMaxSize().testTag("tv_home"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(TV_GRID_COLUMNS),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvHomeHeader(videoOpen, actions, firstFocus)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                val search = home.search
                TvSearchBar(
                    query = (search as? TvSearchState.Found)?.query ?: (search as? TvSearchState.Searching)?.query,
                    subtitlesOnly = home.subtitlesOnly,
                    onSearch = { askQuery = true },
                    onSubtitlesOnlyChange = { home.subtitlesOnly = it },
                    recent = home.recent,
                    onRecent = ::runSearch,
                    focus = if (videoOpen) null else firstFocus,
                )
            }
            val continueTabs = tvContinueTabs(tabs)
            if (continueTabs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TvContinueRow(continueTabs, actions.openTab)
                }
            }
            tvSearchResults(home.search, actions.openVideo) { home.searchRun += 1 }
        }
    }
    if (askQuery) {
        TvSearchDialog(
            initial = (home.search as? TvSearchState.Found)?.query.orEmpty(),
            onSearch = { query ->
                askQuery = false
                runSearch(query)
            },
            onDismiss = { askQuery = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvHomeHeader(
    videoOpen: Boolean,
    actions: TvHomeActions,
    firstFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Default.ClosedCaption,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (videoOpen) {
                TvHomeButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.tv_home_back_to_video),
                    actions.backToVideo,
                    Modifier.focusRequester(firstFocus).testTag("tv_back_to_video"),
                )
            }
            TvHomeButton(
                Icons.Default.OndemandVideo,
                stringResource(R.string.tv_home_youtube_page),
                actions.openYouTubePage,
                Modifier.testTag("tv_youtube_page"),
            )
            TvHomeButton(Icons.Default.School, stringResource(R.string.navigation_practice), actions.practice)
            TvHomeButton(Icons.Default.Insights, stringResource(R.string.navigation_progress), actions.progress)
            TvHomeButton(
                Icons.Default.Settings,
                stringResource(R.string.navigation_settings),
                actions.settings,
                Modifier.testTag("tv_settings"),
            )
        }
    }
}

@Composable
private fun TvHomeButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(onClick = onClick, modifier = modifier.tvFocusRing(RoundedCornerShape(24.dp))) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun TvSearchBar(
    query: String?,
    subtitlesOnly: Boolean,
    onSearch: () -> Unit,
    onSubtitlesOnlyChange: (Boolean) -> Unit,
    recent: List<String>,
    onRecent: (String) -> Unit,
    focus: FocusRequester?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onSearch,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                        .tvFocusRing(RoundedCornerShape(28.dp))
                        .testTag("tv_search"),
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    query ?: stringResource(R.string.tv_home_search),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilterChip(
                selected = subtitlesOnly,
                onClick = { onSubtitlesOnlyChange(!subtitlesOnly) },
                label = { Text(stringResource(R.string.tv_home_subtitles_only)) },
                modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)).testTag("tv_subtitles_only"),
            )
        }
        if (recent.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item {
                    Text(
                        stringResource(R.string.tv_home_recent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(recent) { text ->
                    TextButton(onClick = { onRecent(text) }, modifier = Modifier.tvFocusRing(RoundedCornerShape(20.dp))) { Text(text) }
                }
            }
        }
    }
}

@Composable
private fun TvContinueRow(
    tabs: List<BrowserTab>,
    onOpen: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvSectionTitle(stringResource(R.string.tv_home_continue))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(tabs, key = { it.id }) { tab ->
                TvVideoCard(
                    thumbnailUrl = browserTabThumbnailUrl(tab.url),
                    title = browserTabName(tab),
                    details = null,
                    subtitles = false,
                    onClick = { onOpen(tab.id) },
                    modifier = Modifier.width(220.dp).testTag("tv_tab_${tab.id}"),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.tvSearchResults(
    search: TvSearchState,
    onOpen: (String) -> Unit,
    onTryAgain: () -> Unit,
) {
    when (search) {
        TvSearchState.Idle ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvHint(stringResource(R.string.tv_home_start_hint))
            }
        is TvSearchState.Searching ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.tv_home_searching))
                }
            }
        is TvSearchState.Failed ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvHint(stringResource(R.string.tv_home_search_failed))
                    OutlinedButton(onClick = onTryAgain, modifier = Modifier.tvFocusRing(RoundedCornerShape(24.dp))) {
                        Text(stringResource(R.string.tv_home_try_again))
                    }
                }
            }
        is TvSearchState.Found -> {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvSectionTitle(
                    if (search.videos.isEmpty()) {
                        stringResource(R.string.tv_home_no_results)
                    } else {
                        stringResource(R.string.tv_home_results, search.query)
                    },
                )
            }
            items(search.videos, key = { it.videoId }) { video ->
                TvVideoCard(
                    thumbnailUrl = "https://i.ytimg.com/vi/${video.videoId}/mqdefault.jpg",
                    title = video.title,
                    details = tvVideoDetails(video),
                    subtitles = video.subtitles,
                    onClick = { onOpen(video.videoId) },
                    modifier = Modifier.testTag("tv_video_${video.videoId}"),
                )
            }
        }
    }
}

@Composable
private fun TvSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TvHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TvVideoCard(
    thumbnailUrl: String?,
    title: String,
    details: String?,
    subtitles: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val picture by produceState(TabThumbnails.cached(thumbnailUrl), thumbnailUrl) {
        value = TabThumbnails.cached(thumbnailUrl)
        if (value == null && thumbnailUrl != null) value = TabThumbnails.load(thumbnailUrl)
    }
    Column(
        modifier
            .clip(shape)
            .tvFocusRing(shape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val image = picture
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.OndemandVideo, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (subtitles) {
                Text(
                    stringResource(R.string.tv_home_cc),
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            title,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        details?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvSearchDialog(
    initial: String,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_home_search)) },
        text = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .tvArrowsLeave()
                        .testTag("tv_search_field"),
                label = { Text(stringResource(R.string.tv_home_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSearch(query) }, enabled = query.isNotBlank(), modifier = Modifier.testTag("tv_search_go")) {
                Text(stringResource(R.string.tv_home_search_button))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tv_close)) } },
    )
}
