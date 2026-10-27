package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

// YouTube tabs (owner request 2026-10-27): the menu's "Tabs (N)" list. Every tab is a saved YouTube
// address; only the open tab is loaded, in the app's one YouTube page, so tabs never play at once.

internal const val MAX_BROWSER_TABS = 10

/** Account 1 is the sign-in the app always had; 2 and 3 exist only with "Use different Google accounts". */
internal const val MAIN_TAB_ACCOUNT = 1
internal const val TAB_ACCOUNT_COUNT = 3

internal const val BROWSER_TABS_PREFERENCE = "browser_tabs"

// A video is reopened where it was left only after this much of it played.
private const val RESUME_FROM_MS = 5_000L
private const val MAX_TAB_TITLE_LENGTH = 200

data class BrowserTab(
    val id: Long,
    val url: String,
    val title: String = "",
    val account: Int = MAIN_TAB_ACCOUNT,
)

data class BrowserTabs(
    val tabs: List<BrowserTab> = emptyList(),
    val activeId: Long? = null,
) {
    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }
    val full: Boolean get() = tabs.size >= MAX_BROWSER_TABS

    /** Adds a tab after the others, or returns null when [MAX_BROWSER_TABS] are open. */
    fun opened(
        url: String,
        account: Int = MAIN_TAB_ACCOUNT,
        activate: Boolean = true,
    ): BrowserTabs? {
        if (full) return null
        val tab = BrowserTab(id = (tabs.maxOfOrNull { it.id } ?: 0L) + 1, url = url, account = normalizeTabAccount(account))
        return copy(tabs = tabs + tab, activeId = if (activate || activeId == null) tab.id else activeId)
    }

    /** Closing the open tab opens the one after it, or the one before it when it was the last. */
    fun closed(id: Long): BrowserTabs {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }
        val nextActive =
            if (id != activeId) {
                activeId
            } else {
                (remaining.getOrNull(index) ?: remaining.lastOrNull())?.id
            }
        return BrowserTabs(remaining, nextActive)
    }

    fun switchedTo(id: Long): BrowserTabs = if (tabs.any { it.id == id }) copy(activeId = id) else this

    fun withActive(change: (BrowserTab) -> BrowserTab): BrowserTabs = copy(tabs = tabs.map { if (it.id == activeId) change(it) else it })

    fun withActiveUrl(url: String): BrowserTabs = if (active?.url == url) this else withActive { it.copy(url = url) }

    fun withActiveTitle(title: String): BrowserTabs = if (active?.title == title) this else withActive { it.copy(title = title) }
}

internal fun normalizeTabAccount(account: Int): Int = account.coerceIn(MAIN_TAB_ACCOUNT, TAB_ACCOUNT_COUNT)

/**
 * The account a tab's page signs in with. With "Use different Google accounts" off, or on a phone
 * whose WebView has no profiles, every tab uses the main account.
 */
internal fun effectiveTabAccount(
    tab: BrowserTab?,
    differentAccountsOn: Boolean,
): Int = if (differentAccountsOn && tab != null) normalizeTabAccount(tab.account) else MAIN_TAB_ACCOUNT

/** The page's title without YouTube's own " - YouTube" ending; blank for YouTube's home page. */
internal fun browserTabTitle(pageTitle: String?): String {
    val title =
        pageTitle
            .orEmpty()
            .trim()
            .removeSuffix("- YouTube")
            .trim()
    return if (title.equals("YouTube", ignoreCase = true)) "" else title.take(MAX_TAB_TITLE_LENGTH)
}

internal enum class BrowserTabKind { VIDEO, HOME, SEARCH, OTHER }

internal fun browserTabKind(url: String): BrowserTabKind {
    if (YouTubeUrlParser.extractVideoId(url) != null) return BrowserTabKind.VIDEO
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().trimEnd('/')
    return when (path) {
        "" -> BrowserTabKind.HOME
        "/results" -> BrowserTabKind.SEARCH
        else -> BrowserTabKind.OTHER
    }
}

/** YouTube's small picture of a tab's video (the same one its pages show), or null for other pages. */
internal fun browserTabThumbnailUrl(url: String): String? =
    YouTubeUrlParser.extractVideoId(url)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

/**
 * A watch page address that starts the video at [positionMs], so a tab reopens where it was left.
 * Other pages, and videos only just started, keep their address (without an old start time).
 */
internal fun watchUrlAt(
    url: String,
    positionMs: Long,
): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    if (uri.path?.trimEnd('/') != "/watch" || YouTubeUrlParser.extractVideoId(url) == null) return url
    val kept =
        uri.rawQuery
            .orEmpty()
            .split('&')
            .filter { it.isNotBlank() && !it.startsWith("t=") }
    val start = if (positionMs >= RESUME_FROM_MS) listOf("t=${positionMs / 1_000}s") else emptyList()
    val query = (kept + start).joinToString("&")
    return URI(uri.scheme, uri.rawAuthority, uri.rawPath, null, null).toString() + "?" + query
}

internal fun encodeBrowserTabs(state: BrowserTabs): String =
    JSONObject()
        .put("active", state.activeId ?: -1L)
        .put(
            "tabs",
            JSONArray().apply {
                state.tabs.forEach { tab ->
                    put(
                        JSONObject()
                            .put("id", tab.id)
                            .put("url", tab.url)
                            .put("title", tab.title)
                            .put("account", tab.account),
                    )
                }
            },
        ).toString()

/** Saved tabs, or null when none were saved yet or the saved text can't be read. */
internal fun decodeBrowserTabs(stored: String?): BrowserTabs? {
    if (stored.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(stored)
        val array = json.getJSONArray("tabs")
        val tabs =
            (0 until array.length())
                .map { array.getJSONObject(it) }
                .mapNotNull { item ->
                    val url = item.optString("url")
                    if (!isYouTubeWebUrl(url)) return@mapNotNull null
                    BrowserTab(
                        id = item.getLong("id"),
                        url = url,
                        title = item.optString("title").take(MAX_TAB_TITLE_LENGTH),
                        account = normalizeTabAccount(item.optInt("account", MAIN_TAB_ACCOUNT)),
                    )
                }.distinctBy { it.id }
                .take(MAX_BROWSER_TABS)
        val activeId = json.optLong("active", -1L).takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id
        BrowserTabs(tabs, activeId)
    }.getOrNull()
}

/**
 * The tabs to start with. Before tabs existed the app kept one page ([lastUrl]); that page becomes
 * the first tab, and a brand-new user starts with YouTube's home page open, as before.
 */
internal fun initialBrowserTabs(
    stored: String?,
    lastUrl: String?,
): BrowserTabs =
    decodeBrowserTabs(stored)
        ?: BrowserTabs(listOf(BrowserTab(id = 1, url = trustedEmbeddedUrlOrHome(lastUrl ?: YOUTUBE_HOME_URL))), activeId = 1)

/** Keeps the tabs in preferences, so they are there again after the app restarts. */
internal class BrowserTabStore(
    private val preferences: SharedPreferences,
    lastUrl: String?,
) {
    private val mutableTabs = MutableStateFlow(initialBrowserTabs(preferences.getString(BROWSER_TABS_PREFERENCE, null), lastUrl))
    val tabs: StateFlow<BrowserTabs> = mutableTabs.asStateFlow()

    val value: BrowserTabs get() = mutableTabs.value

    fun update(change: (BrowserTabs) -> BrowserTabs) {
        val before = mutableTabs.value
        val after = change(before)
        if (after == before) return
        mutableTabs.value = after
        preferences.edit().putString(BROWSER_TABS_PREFERENCE, encodeBrowserTabs(after)).apply()
    }
}
