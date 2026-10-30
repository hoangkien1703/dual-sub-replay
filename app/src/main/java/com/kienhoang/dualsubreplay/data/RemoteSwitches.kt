package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote switches (owner decision 2026-10-30, switches only): a small file on `main` that can choose
 * among behaviour already in the app, so a fix for a YouTube or Google change can reach phones
 * without a new APK. It can never add code, scripts or addresses; at worst it turns things off.
 */
internal const val REMOTE_SWITCHES_URL = "https://raw.githubusercontent.com/$UPDATE_REPOSITORY/main/remote-config/switches.json"
internal const val REMOTE_SWITCHES_HOST = "raw.githubusercontent.com"
internal const val MAX_REMOTE_SWITCHES_BYTES = 64 * 1024

/** A saved file older than this is no longer used, so a forgotten switch cannot stay on forever. */
internal const val REMOTE_SWITCHES_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

/** The background translation pace a switch may set, in seconds; the app's own pace is 3 s. */
internal val REMOTE_BACKGROUND_PAUSE_SECONDS = 3L..60L

/** The notices a switch may show; their text is in the app's strings, never in the file. */
internal enum class RemoteNotice(
    val id: String,
) {
    YOUTUBE_CHANGED("youtube_changed"),
    GOOGLE_TRANSLATE_LIMITED("google_translate_limited"),
    UPDATE_RECOMMENDED("update_recommended"),
}

internal data class RemoteSwitches(
    /** Google Translate addresses (`GoogleEndpoint` names) in the order to try; null is the app's own order. */
    val googleTranslateAddresses: List<String>? = null,
    /** A slower pace for background translation; null is the app's own 3 s. */
    val backgroundPauseMs: Long? = null,
    val skipAdsOff: Boolean = false,
    val skipSponsorsOff: Boolean = false,
    val pageTokenOff: Boolean = false,
    val notice: RemoteNotice? = null,
    /** Tells one notice apart from an earlier one with the same [notice], so Close is remembered per incident. */
    val noticeKey: String = "",
)

private val NOTICE_KEY = Regex("^[A-Za-z0-9_.-]{1,40}$")

/**
 * The switches for an app with [versionCode]. Rules apply in order, each only within its
 * `min_version_code`..`max_version_code`, and a later rule's value replaces an earlier one. Another
 * schema, a broken file, unknown keys, unknown addresses or notices, and paces outside
 * [REMOTE_BACKGROUND_PAUSE_SECONDS] change nothing.
 */
internal fun parseRemoteSwitches(
    json: String,
    versionCode: Long,
    knownAddresses: Collection<String>,
): RemoteSwitches {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return RemoteSwitches()
    if (root.optInt("schema") != 1) return RemoteSwitches()
    val rules = root.optJSONArray("rules") ?: return RemoteSwitches()
    var result = RemoteSwitches()
    for (index in 0 until rules.length()) {
        val rule = rules.optJSONObject(index) ?: continue
        val min = rule.optLong("min_version_code", 0)
        val max = rule.optLong("max_version_code", Long.MAX_VALUE)
        if (versionCode !in min..max) continue
        val switches = rule.optJSONObject("switches") ?: continue
        result = result.withRule(switches, knownAddresses)
    }
    return result
}

private fun RemoteSwitches.withRule(
    switches: JSONObject,
    knownAddresses: Collection<String>,
): RemoteSwitches {
    var result = this
    switches.optJSONArray("google_translate_addresses")?.let { list ->
        val addresses = list.strings().filter { it in knownAddresses }.distinct()
        result = result.copy(googleTranslateAddresses = addresses.takeIf { it.isNotEmpty() })
    }
    if (switches.has("background_translate_pause_seconds")) {
        val seconds = switches.optLong("background_translate_pause_seconds", -1)
        result = result.copy(backgroundPauseMs = seconds.takeIf { it in REMOTE_BACKGROUND_PAUSE_SECONDS }?.let { it * 1_000 })
    }
    switches.offSwitch("skip_ads")?.let { result = result.copy(skipAdsOff = it) }
    switches.offSwitch("skip_sponsors")?.let { result = result.copy(skipSponsorsOff = it) }
    switches.offSwitch("page_token_captions")?.let { result = result.copy(pageTokenOff = it) }
    if (switches.has("notice")) {
        val notice = RemoteNotice.entries.firstOrNull { it.id == switches.optString("notice") }
        val key = switches.optString("notice_key").takeIf(NOTICE_KEY::matches).orEmpty()
        result = result.copy(notice = notice, noticeKey = if (notice == null) "" else key)
    }
    return result
}

/** `"off"` turns the feature off, `"default"` gives it back to the user's setting; anything else is ignored. */
private fun JSONObject.offSwitch(name: String): Boolean? =
    when (optString(name)) {
        "off" -> true
        "default" -> false
        else -> null
    }

private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

/** The saved file, when it is still young enough to use. */
internal fun usableRemoteSwitches(
    savedJson: String?,
    savedAtMs: Long,
    nowMs: Long,
): String? = savedJson?.takeIf { savedAtMs in 1..nowMs && nowMs - savedAtMs < REMOTE_SWITCHES_MAX_AGE_MS }

/** The switches in force, for the parts of the app that follow them. */
internal object RemoteSwitchState {
    private val mutableSwitches = MutableStateFlow(RemoteSwitches())
    val switches: StateFlow<RemoteSwitches> = mutableSwitches.asStateFlow()

    fun set(switches: RemoteSwitches) {
        mutableSwitches.value = switches
    }
}
