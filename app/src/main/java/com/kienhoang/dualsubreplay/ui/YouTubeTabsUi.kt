package com.kienhoang.dualsubreplay.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MotionEvent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val THUMBNAIL_CACHE_SIZE = 24
private const val THUMBNAIL_MAX_BYTES = 256 * 1024L
private const val THUMBNAIL_TIMEOUT_MS = 8_000L

/** What the menu's Tabs list and the YouTube page can do with tabs (AppViewModel does the work). */
internal class BrowserTabActions(
    val switchTo: (Long) -> Unit = {},
    val close: (Long) -> Unit = {},
    /** False when [MAX_BROWSER_TABS] are open. */
    val openNew: (account: Int) -> Boolean = { false },
    val openInNew: (url: String) -> Boolean = { false },
    /** The page at this address got this title. */
    val pageTitle: (url: String, title: String?) -> Unit = { _, _ -> },
)

internal fun browserTabActions(viewModel: AppViewModel) =
    BrowserTabActions(
        switchTo = viewModel::switchTab,
        close = viewModel::closeTab,
        openNew = { account -> viewModel.openNewTab(account) },
        openInNew = viewModel::openInNewTab,
        pageTitle = viewModel::onYouTubePageTitle,
    )

/** Menu → Tabs (N): tap to unfold the tabs; each tab opens with a tap and closes with its X. */
@Composable
internal fun DrawerTabsSection(
    tabs: BrowserTabs,
    differentAccounts: Boolean,
    actions: BrowserTabActions,
    closeDrawer: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    var open by remember { mutableStateOf(preferences.getBoolean(DRAWER_TABS_OPEN_PREFERENCE, false)) }
    val stateText = stringResource(if (open) R.string.tabs_list_shown else R.string.tabs_list_hidden)
    NavigationDrawerItem(
        icon = { Icon(Icons.Default.Tab, contentDescription = null) },
        label = { Text(stringResource(R.string.tabs_menu_title, tabs.tabs.size)) },
        badge = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) },
        selected = false,
        modifier = Modifier.testTag("open_tabs").semantics { stateDescription = stateText },
        onClick = {
            open = !open
            preferences.edit().putBoolean(DRAWER_TABS_OPEN_PREFERENCE, open).apply()
        },
    )
    AnimatedVisibility(open) {
        Column(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            tabs.tabs.forEach { tab ->
                BrowserTabRow(
                    tab = tab,
                    active = tab.id == tabs.activeId,
                    showAccount = differentAccounts && tab.account != MAIN_TAB_ACCOUNT,
                    onOpen = {
                        actions.switchTo(tab.id)
                        closeDrawer()
                    },
                    onClose = { actions.close(tab.id) },
                )
            }
            NewTabRows(tabs.full, differentAccounts) { account ->
                if (actions.openNew(account)) closeDrawer()
            }
        }
    }
}

@Composable
private fun NewTabRows(
    full: Boolean,
    differentAccounts: Boolean,
    onNewTab: (account: Int) -> Unit,
) {
    if (full) {
        Text(
            stringResource(R.string.tabs_full, MAX_BROWSER_TABS),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    TabMenuRow(Icons.Default.Add, stringResource(R.string.tabs_new_tab), "new_tab") { onNewTab(MAIN_TAB_ACCOUNT) }
    if (!differentAccounts) return
    var chooseAccount by remember { mutableStateOf(false) }
    Box {
        TabMenuRow(Icons.Default.ManageAccounts, stringResource(R.string.tabs_new_tab_other_account), "new_tab_other_account") {
            chooseAccount = true
        }
        DropdownMenu(expanded = chooseAccount, onDismissRequest = { chooseAccount = false }, offset = DpOffset(48.dp, 0.dp)) {
            (MAIN_TAB_ACCOUNT..TAB_ACCOUNT_COUNT).forEach { account ->
                DropdownMenuItem(
                    text = { Text(tabAccountName(account)) },
                    modifier = Modifier.testTag("new_tab_account_$account"),
                    onClick = {
                        chooseAccount = false
                        onNewTab(account)
                    },
                )
            }
        }
    }
}

@Composable
private fun tabAccountName(account: Int): String =
    if (account == MAIN_TAB_ACCOUNT) {
        stringResource(R.string.tabs_account_main)
    } else {
        stringResource(R.string.tabs_account, account)
    }

@Composable
private fun TabMenuRow(
    icon: ImageVector,
    text: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BrowserTabRow(
    tab: BrowserTab,
    active: Boolean,
    showAccount: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val title = browserTabName(tab)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onOpen)
            .semantics { selected = active }
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag("browser_tab_${tab.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPicture(tab.url)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            if (showAccount) {
                Text(
                    tabAccountName(tab.account),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("browser_tab_account_${tab.id}"),
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("close_browser_tab_${tab.id}")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tabs_close, title))
        }
    }
}

/** The page's title, or a name for its kind of page while YouTube has not named it yet. */
@Composable
internal fun browserTabName(tab: BrowserTab): String =
    tab.title.ifBlank {
        stringResource(
            when (browserTabKind(tab.url)) {
                BrowserTabKind.VIDEO -> R.string.tabs_name_video
                BrowserTabKind.HOME -> R.string.tabs_name_home
                BrowserTabKind.SEARCH -> R.string.tabs_name_search
                BrowserTabKind.OTHER -> R.string.tabs_name_page
            },
        )
    }

@Composable
private fun TabPicture(url: String) {
    val thumbnailUrl = browserTabThumbnailUrl(url)
    val picture by produceState(TabThumbnails.cached(thumbnailUrl), thumbnailUrl) {
        if (value == null && thumbnailUrl != null) value = TabThumbnails.load(thumbnailUrl)
    }
    Box(
        Modifier
            .size(width = 64.dp, height = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = picture
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val icon =
                when (browserTabKind(url)) {
                    BrowserTabKind.VIDEO -> Icons.Default.OndemandVideo
                    BrowserTabKind.HOME -> Icons.Default.Home
                    BrowserTabKind.SEARCH -> Icons.Default.Search
                    BrowserTabKind.OTHER -> Icons.Default.Public
                }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** YouTube's small video pictures for the Tabs list, kept in memory only. */
private object TabThumbnails {
    private val cache = LruCache<String, ImageBitmap>(THUMBNAIL_CACHE_SIZE)
    private val client by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(THUMBNAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    fun cached(url: String?): ImageBitmap? = url?.let { cache.get(it) }

    suspend fun load(url: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://i.ytimg.com/vi/")) return@withContext null
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body.contentLength() > THUMBNAIL_MAX_BYTES) return@use null
                    val bytes = body.source().apply { request(THUMBNAIL_MAX_BYTES + 1) }.buffer
                    if (bytes.size > THUMBNAIL_MAX_BYTES) return@use null
                    val data = bytes.readByteArray()
                    BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
}

/** Shown instead of the YouTube page once every tab is closed. */
@Composable
internal fun EmptyTabsPage(
    onOpenNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back leaves the app as on the YouTube page, keeping the app's state.
    val context = LocalContext.current
    BackHandler { (context as? Activity)?.leaveKeepingState() }
    Box(modifier.testTag("empty_tabs_page"), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Tab, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.tabs_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.tabs_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenNewTab, modifier = Modifier.testTag("empty_tabs_open_new")) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tabs_open_new_youtube_tab))
            }
        }
    }
}

/** A long-pressed video link and where it was pressed, for the Open in new tab menu. */
internal data class LongPressedVideoLink(
    val url: String,
    val x: Float,
    val y: Float,
)

/** The small menu at a long-pressed video: Open in new tab, or Open here. */
@Composable
internal fun OpenInNewTabMenu(
    link: LongPressedVideoLink?,
    onOpenInNewTab: (String) -> Unit,
    onOpenHere: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val shown = link ?: return
    val offset = with(density) { DpOffset(shown.x.toDp(), shown.y.toDp()) }
    Box(Modifier.padding(start = offset.x, top = offset.y)) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tabs_open_in_new_tab)) },
                leadingIcon = { Icon(Icons.Default.Tab, contentDescription = null) },
                modifier = Modifier.testTag("open_in_new_tab"),
                onClick = {
                    onDismiss()
                    onOpenInNewTab(shown.url)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tabs_open_here)) },
                leadingIcon = { Icon(Icons.Default.OndemandVideo, contentDescription = null) },
                modifier = Modifier.testTag("open_link_here"),
                onClick = {
                    onDismiss()
                    onOpenHere(shown.url)
                },
            )
        }
    }
}

/** The address of a YouTube video link, or null for any other link. */
internal fun videoLinkOrNull(url: String?): String? = url?.takeIf { isYouTubeWebUrl(it) && browseVideoSelection(it) != null }

/** A long press on a video link (or its picture) in the page reports the link and where it was pressed. */
@SuppressLint("ClickableViewAccessibility")
internal fun WebView.listenForVideoLongPress(onLink: (LongPressedVideoLink) -> Unit) {
    var touchX = 0f
    var touchY = 0f
    setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchX = event.x
            touchY = event.y
        }
        false
    }
    setOnLongClickListener {
        val hit = hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                val link = videoLinkOrNull(hit.extra) ?: return@setOnLongClickListener false
                onLink(LongPressedVideoLink(link, touchX, touchY))
                true
            }
            // A video's picture: WebView gives the picture's address, so ask for the link around it.
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val x = touchX
                val y = touchY
                val handler =
                    Handler(Looper.getMainLooper()) { message ->
                        videoLinkOrNull(message.data.getString("url"))?.let { onLink(LongPressedVideoLink(it, x, y)) }
                        true
                    }
                requestFocusNodeHref(handler.obtainMessage())
                true
            }
            else -> false
        }
    }
}

/**
 * The YouTube page's tab actions: Open in new tab says where the tab went ("Show" opens it), or
 * that [MAX_BROWSER_TABS] are already open.
 */
@Composable
internal fun rememberPageTabActions(
    actions: BrowserTabActions,
    tabs: BrowserTabs,
    snackbars: SnackbarHostState,
): BrowserTabActions {
    val scope = rememberCoroutineScope()
    val latestTabs by rememberUpdatedState(tabs)
    val opened = stringResource(R.string.tabs_opened_in_new_tab)
    val show = stringResource(R.string.tabs_show)
    val full = stringResource(R.string.tabs_full, MAX_BROWSER_TABS)
    return remember(actions, opened, show, full) {
        BrowserTabActions(
            switchTo = actions.switchTo,
            close = actions.close,
            openNew = actions.openNew,
            pageTitle = actions.pageTitle,
            openInNew = { url ->
                val done = actions.openInNew(url)
                scope.launch {
                    snackbars.currentSnackbarData?.dismiss()
                    if (!done) {
                        snackbars.showSnackbar(full)
                    } else if (snackbars.showSnackbar(opened, actionLabel = show) == SnackbarResult.ActionPerformed) {
                        latestTabs.tabs.lastOrNull()?.let { actions.switchTo(it.id) }
                    }
                }
                done
            },
        )
    }
}
