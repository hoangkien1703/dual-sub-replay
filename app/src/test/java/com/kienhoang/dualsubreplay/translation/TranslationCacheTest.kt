package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationCacheTest {
    @Test fun exactTextAndLanguagePairIsolation() {
        val cache = TranslationCache()
        cache.put("en", "vi", "Hi", "Chào")
        assertEquals("Chào", cache.get("en", "vi", "Hi"))
        assertNull(cache.get("en", "ja", "Hi"))
        assertNull(cache.get("en", "vi", "hi"))
    }

    @Test fun evictsLeastRecentlyUsedAndBoundsCharacters() {
        val cache = TranslationCache(2, 20)
        cache.put("en", "vi", "1", "one")
        cache.put("en", "vi", "2", "two")
        cache.get("en", "vi", "1")
        cache.put("en", "vi", "3", "three")
        assertNull(cache.get("en", "vi", "2"))
        cache.put("en", "vi", "long", "x".repeat(21))
        assertNull(cache.get("en", "vi", "long"))
    }
}
