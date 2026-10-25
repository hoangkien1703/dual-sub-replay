package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideOpenAppScriptTest {
    @Test
    fun theScriptRunsOnlyOnYouTubeAndOnlyAddsAStyle() {
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("window.location.protocol !== 'https:'"))
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("host.endsWith('.youtube.com')"))
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("style.id = '$YOUTUBE_OPEN_APP_STYLE_ID';"))
        assertTrue(
            "Added once",
            WEB_HIDE_OPEN_APP_SCRIPT.contains("if (document.getElementById('$YOUTUBE_OPEN_APP_STYLE_ID')) return true;"),
        )
        listOf("fetch(", "XMLHttpRequest", "preventDefault", ".remove()", ".click()").forEach {
            assertFalse(it, WEB_HIDE_OPEN_APP_SCRIPT.contains(it))
        }
    }

    @Test
    fun atDocumentStartItWaitsForThePageRoot() {
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("const root = document.head || document.documentElement;"))
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("if (!root) return false;"))
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("observer.observe(document, { childList: true });"))
        assertTrue(WEB_HIDE_OPEN_APP_SCRIPT.contains("if (add()) observer.disconnect();"))
    }

    @Test
    fun buttonsAreFoundByWhereTheyGoNotByTheirWords() {
        val rules = YOUTUBE_OPEN_APP_CSS.lines()
        assertEquals(2, rules.size)
        // The links themselves, without :has(), so an older WebView still hides them.
        assertFalse(rules[0].contains(":has("))
        listOf(
            "a[href^='intent:'][href*='com.google.android.youtube']",
            "a[href^='vnd.youtube:']",
            "a[href*='mweb_c3_open_app']",
            "a[href*='mweb_to_native_app']",
        ).forEach { assertTrue(it, rules[0].contains(it)) }
        assertTrue(rules[0].endsWith("{ display: none !important; }"))
        // The header pill's wrapper goes only when it holds such a link, so a plain "Sign in" stays.
        assertTrue(rules[1].contains(".mobile-topbar-header-sign-in-button:has(a[href^='intent:']"))
        assertTrue(rules[1].contains("ytm-button-renderer:has(> a[href^='vnd.youtube:'])"))
        assertFalse(YOUTUBE_OPEN_APP_CSS.contains("Open App"))
        assertFalse("Search and menu stay", YOUTUBE_OPEN_APP_CSS.contains("topbar-button-search-button"))
        assertFalse(YOUTUBE_OPEN_APP_CSS.contains("topbar-menu-button"))
        assertTrue("The CSS goes in as one JS string", WEB_HIDE_OPEN_APP_SCRIPT.contains("style.textContent = \"a[href^='intent:']"))
    }
}
