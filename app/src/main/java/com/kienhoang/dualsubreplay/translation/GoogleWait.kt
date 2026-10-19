package com.kienhoang.dualsubreplay.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/*
 * A row waits for Google only briefly (owner decision 2026-10-19): on mobile networks Google can be
 * slow or unreachable, so after the wait the video translates without Google at once while Google's
 * request keeps going, and the video goes back to Google when it answers.
 */

internal const val GOOGLE_WAIT_PREFERENCE = "google_wait"

/** How long a row waits for Google on mobile data, and on Wi-Fi, when the setting is Automatic. */
internal const val GOOGLE_WAIT_MOBILE_MS = 2_000L
internal const val GOOGLE_WAIT_WIFI_MS = 4_000L
private const val GOOGLE_WAIT_LONG_MS = 8_000L

/** A load that started without Google asks Google again as soon as a row plays: it looks this often. */
internal const val GOOGLE_EARLY_CHECK_POLL_MS = 1_000L

/** Settings → Translation → Wait for Google. */
enum class GoogleWait(
    val storageValue: String,
) {
    AUTO("auto"),
    TWO_SECONDS("2"),
    FOUR_SECONDS("4"),
    EIGHT_SECONDS("8"),
}

internal fun storedGoogleWait(raw: String?): GoogleWait = GoogleWait.entries.firstOrNull { it.storageValue == raw } ?: GoogleWait.AUTO

internal fun googleWaitMs(
    wait: GoogleWait,
    onWifi: Boolean,
): Long =
    when (wait) {
        GoogleWait.AUTO -> if (onWifi) GOOGLE_WAIT_WIFI_MS else GOOGLE_WAIT_MOBILE_MS
        GoogleWait.TWO_SECONDS -> GOOGLE_WAIT_MOBILE_MS
        GoogleWait.FOUR_SECONDS -> GOOGLE_WAIT_WIFI_MS
        GoogleWait.EIGHT_SECONDS -> GOOGLE_WAIT_LONG_MS
    }

/** Wi-Fi or Ethernet; anything else (mobile data, no network) counts as mobile data. */
internal fun Context.onWifi(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

/** Google did not answer within [waitMs]; [pending] is its request, still running. */
class GoogleSlowException(
    val waitMs: Long,
    val pending: Deferred<*>,
) : IOException(googleSlowDetail(waitMs))

/** The problem detail while Google is waited for in the background, with what its addresses did [soFar]. */
internal fun googleSlowDetail(
    waitMs: Long,
    soFar: String? = null,
): String =
    "Google Translate did not answer within ${waitMs / 1_000} s; the app keeps waiting for it in the background." +
        soFar?.let { " So far: $it." }.orEmpty()

/**
 * Starts [request] in [scope], which outlives the caller, and waits at most [waitMs] for it: returns
 * its result or rethrows its failure, and after [waitMs] throws [GoogleSlowException] while it runs on.
 * [scope] needs a supervisor job, so a failing request stays in its [Deferred].
 */
internal suspend fun <T> awaitWithin(
    scope: CoroutineScope,
    waitMs: Long,
    request: suspend () -> T,
): T {
    val pending = scope.async { request() }
    withTimeoutOrNull(waitMs) { pending.join() } ?: throw GoogleSlowException(waitMs, pending)
    return pending.await()
}
