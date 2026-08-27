package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeSyncPreferencesTest {
    @Test fun unknownModeFallsBackToPr33Control() {
        assertEquals(
            KaraokeSyncMode.PR33_CURRENT,
            KaraokeSyncMode.fromPreference("future-mode"),
        )
        assertEquals(
            KaraokeSyncMode.SOFT_ANCHOR,
            KaraokeSyncMode.fromPreference("soft_anchor"),
        )
        assertEquals(
            KaraokeSyncMode.ENHANCED,
            KaraokeSyncMode.fromPreference("enhanced"),
        )
        assertEquals(
            KaraokeSyncMode.ESTIMATED_ONLY,
            KaraokeSyncMode.fromPreference("estimated_only"),
        )
    }

    @Test fun highlightLeadIsClampedToSafeExperimentRange() {
        assertEquals(-200L, KaraokeSyncPreferences.normalizeHighlightLeadMs(-900L))
        assertEquals(20L, KaraokeSyncPreferences.normalizeHighlightLeadMs(20L))
        assertEquals(300L, KaraokeSyncPreferences.normalizeHighlightLeadMs(900L))
    }
}
