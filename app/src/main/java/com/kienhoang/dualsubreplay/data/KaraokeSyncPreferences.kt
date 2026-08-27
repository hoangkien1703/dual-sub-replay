package com.kienhoang.dualsubreplay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class KaraokeSyncMode(val preferenceValue: String) {
    PR33_CURRENT("current"),
    SOFT_ANCHOR("soft_anchor"),
    ENHANCED("enhanced"),
    ESTIMATED_ONLY("estimated_only");

    companion object {
        fun fromPreference(value: String?): KaraokeSyncMode =
            entries.firstOrNull { it.preferenceValue == value } ?: PR33_CURRENT
    }
}

internal data class KaraokeSyncDiagnostic(
    val word: String,
    val originalSource: SubtitleTimingSource,
    val originalStartMs: Long,
    val acousticStartMs: Long,
    val usedStartMs: Long,
)

internal object KaraokeSyncDiagnostics {
    private const val MAX_ENTRIES = 8
    private val _entries = MutableStateFlow<List<KaraokeSyncDiagnostic>>(emptyList())
    val entries: StateFlow<List<KaraokeSyncDiagnostic>> = _entries.asStateFlow()

    fun record(diagnostic: KaraokeSyncDiagnostic) {
        _entries.value = (_entries.value + diagnostic).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}

/**
 * Preferences used by the experimental spoken-word synchronization controls.
 *
 * The acoustic model remains outside the APK. It is downloaded only after the
 * user explicitly asks for it, while the lightweight timing controls work with
 * the normal application install.
 */
internal object KaraokeSyncPreferences {
    const val DEFAULT_HIGHLIGHT_LEAD_MS = 20L
    const val MIN_HIGHLIGHT_LEAD_MS = -200L
    const val MAX_HIGHLIGHT_LEAD_MS = 300L
    const val SOFT_ANCHOR_RANGE_MS = 400L

    private const val PREFERENCES_NAME = "dual_sub_preferences"
    private const val MODE_KEY = "karaoke_sync_mode"
    private const val HIGHLIGHT_LEAD_KEY = "karaoke_highlight_lead_ms"
    private const val DIAGNOSTICS_KEY = "karaoke_sync_diagnostics"
    private const val ACOUSTIC_MODEL_ENABLED_KEY = "karaoke_acoustic_model_enabled"

    @Volatile
    private var runtimeHighlightLeadMs = DEFAULT_HIGHLIGHT_LEAD_MS

    fun initialize(context: Context) {
        runtimeHighlightLeadMs = normalizeHighlightLeadMs(
            preferences(context).getLong(HIGHLIGHT_LEAD_KEY, DEFAULT_HIGHLIGHT_LEAD_MS),
        )
    }

    fun mode(context: Context): KaraokeSyncMode =
        KaraokeSyncMode.fromPreference(preferences(context).getString(MODE_KEY, null))

    fun setMode(context: Context, mode: KaraokeSyncMode) {
        preferences(context).edit().putString(MODE_KEY, mode.preferenceValue).apply()
    }

    fun highlightLeadMs(): Long = runtimeHighlightLeadMs

    fun persistedHighlightLeadMs(context: Context): Long = normalizeHighlightLeadMs(
        preferences(context).getLong(HIGHLIGHT_LEAD_KEY, DEFAULT_HIGHLIGHT_LEAD_MS),
    )

    fun setHighlightLeadMs(context: Context, value: Long) {
        val normalized = normalizeHighlightLeadMs(value)
        runtimeHighlightLeadMs = normalized
        preferences(context).edit().putLong(HIGHLIGHT_LEAD_KEY, normalized).apply()
    }

    fun diagnosticsEnabled(context: Context): Boolean =
        preferences(context).getBoolean(DIAGNOSTICS_KEY, false)

    fun setDiagnosticsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(DIAGNOSTICS_KEY, enabled).apply()
        if (!enabled) KaraokeSyncDiagnostics.clear()
    }

    fun acousticModelEnabled(context: Context): Boolean =
        preferences(context).getBoolean(ACOUSTIC_MODEL_ENABLED_KEY, false)

    fun setAcousticModelEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ACOUSTIC_MODEL_ENABLED_KEY, enabled).apply()
    }

    internal fun normalizeHighlightLeadMs(value: Long): Long =
        value.coerceIn(MIN_HIGHLIGHT_LEAD_MS, MAX_HIGHLIGHT_LEAD_MS)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, 0)
}
