package com.kienhoang.dualsubreplay.update

import android.content.Context
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.data.MAX_REMOTE_SWITCHES_BYTES
import com.kienhoang.dualsubreplay.data.ProblemArea
import com.kienhoang.dualsubreplay.data.ProblemLog
import com.kienhoang.dualsubreplay.data.REMOTE_SWITCHES_HOST
import com.kienhoang.dualsubreplay.data.REMOTE_SWITCHES_URL
import com.kienhoang.dualsubreplay.data.RemoteSwitchState
import com.kienhoang.dualsubreplay.data.RemoteSwitches
import com.kienhoang.dualsubreplay.data.ResponseLimitExceededException
import com.kienhoang.dualsubreplay.data.parseRemoteSwitches
import com.kienhoang.dualsubreplay.data.readUtf8WithLimit
import com.kienhoang.dualsubreplay.data.usableRemoteSwitches
import com.kienhoang.dualsubreplay.translation.GoogleEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Its own preferences file: Reset all settings never clears the switches. */
private const val REMOTE_SWITCH_PREFERENCES = "remote_switches"
private const val SAVED_JSON_KEY = "json"
private const val SAVED_AT_KEY = "saved_at"
private const val LAST_CHECK_KEY = "last_check"

/**
 * Reads `remote-config/switches.json` once a day while Tell me about new versions is on (owner
 * decision 2026-10-30). The request carries no cookies or identifiers; the saved copy is used for up
 * to 30 days, so a phone that stays offline goes back to the app's own behaviour.
 */
internal object RemoteSwitchFetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    private val knownAddresses = GoogleEndpoint.entries.map { it.name }

    fun onAppStarted(context: Context) {
        val app = context.applicationContext
        val preferences = app.getSharedPreferences(REMOTE_SWITCH_PREFERENCES, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        apply(usableRemoteSwitches(preferences.getString(SAVED_JSON_KEY, null), preferences.getLong(SAVED_AT_KEY, 0), now))
        if (!AppUpdater.remindersEnabled(app) || job?.isActive == true) return
        if (!isAutoUpdateCheckDue(preferences.getLong(LAST_CHECK_KEY, 0), now)) return
        preferences.edit().putLong(LAST_CHECK_KEY, now).apply()
        job =
            scope.launch {
                val json =
                    try {
                        fetch()
                    } catch (error: IOException) {
                        ProblemLog.record(ProblemArea.REMOTE_SWITCHES, error.javaClass.simpleName)
                        return@launch
                    }
                preferences
                    .edit()
                    .putString(SAVED_JSON_KEY, json)
                    .putLong(SAVED_AT_KEY, System.currentTimeMillis())
                    .apply()
                apply(json)
            }
    }

    private fun apply(json: String?) {
        RemoteSwitchState.set(
            if (json == null) {
                RemoteSwitches()
            } else {
                parseRemoteSwitches(json, BuildConfig.VERSION_CODE.toLong(), knownAddresses)
            },
        )
    }

    private fun fetch(): String {
        val request =
            Request
                .Builder()
                .url(REMOTE_SWITCHES_URL)
                .header("User-Agent", "DualSub-Replay/${BuildConfig.VERSION_NAME}")
                .build()
        if (request.url.host != REMOTE_SWITCHES_HOST || !request.url.isHttps) throw IOException("Not the switches address")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty reply")
            return try {
                body.byteStream().use { readUtf8WithLimit(it, MAX_REMOTE_SWITCHES_BYTES) }
            } catch (_: ResponseLimitExceededException) {
                throw IOException("Reply larger than 64 KiB")
            }
        }
    }
}
