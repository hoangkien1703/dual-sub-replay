package com.kienhoang.dualsubreplay.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.data.AppUpdate
import com.kienhoang.dualsubreplay.data.MAX_UPDATE_BYTES
import com.kienhoang.dualsubreplay.data.UPDATE_LATEST_RELEASE_URL
import com.kienhoang.dualsubreplay.data.UPDATE_PREVIEW_RELEASE_URL
import com.kienhoang.dualsubreplay.data.UpdateChannel
import com.kienhoang.dualsubreplay.data.isAllowedUpdateHost
import com.kienhoang.dualsubreplay.data.parseOfficialRelease
import com.kienhoang.dualsubreplay.data.parsePreviewRelease
import com.kienhoang.dualsubreplay.data.parseSha256File
import com.kienhoang.dualsubreplay.data.updateChannelFor
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import com.kienhoang.dualsubreplay.playback.isPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Settings → App updates → Update automatically, off by default (owner request 2026-10-20). */
internal const val APP_AUTO_UPDATE_PREFERENCE = "app_auto_update"

/** When Update automatically last asked GitHub; not a setting, so Reset all settings keeps it. */
private const val APP_UPDATE_LAST_CHECK_PREFERENCE = "app_update_last_check"
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Update automatically asks GitHub at most once a day. */
internal fun isAutoUpdateCheckDue(
    lastCheckMs: Long,
    nowMs: Long,
): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= DAY_MS || nowMs < lastCheckMs

/** What went wrong, shown in the update dialog. */
internal enum class UpdateProblem { OFFLINE, GITHUB_BUSY, GITHUB_ERROR, CHECKSUM, WRONG_APP, INSTALL_FAILED, CANCELLED }

internal sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(
        val update: AppUpdate,
    ) : UpdateState

    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(
        val update: AppUpdate,
        val progress: Float?,
    ) : UpdateState

    /** Downloaded and verified, waiting for Install (or for the user to leave the app). */
    data class Ready(
        val update: AppUpdate,
        val file: File,
    ) : UpdateState

    data class Installing(
        val update: AppUpdate,
    ) : UpdateState

    data class Failed(
        val problem: UpdateProblem,
        /** What Install or Try again retries. */
        val update: AppUpdate?,
        val file: File? = null,
    ) : UpdateState
}

private class UpdateException(
    val problem: UpdateProblem,
    message: String,
) : IOException(message)

/**
 * Checks GitHub for a newer version, downloads and verifies it, and hands it to Android's package
 * installer. Asks GitHub only when the user taps Check for updates, or once a day with Update
 * automatically on. It is never a store: the APK must be this app, newer, with GitHub's checksum.
 */
internal object AppUpdater {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val mutableDialogVisible = MutableStateFlow(false)

    /** The update dialog; the drawer opens it, and an automatic update waiting for a tap opens it too. */
    val dialogVisible: StateFlow<Boolean> = mutableDialogVisible.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var foreground = false
    private var automatic = false

    /** Android's "Update this app?" screen, kept while the app is in the background. */
    private var pendingConfirmation: Intent? = null

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun channel(context: Context): UpdateChannel = updateChannelFor(context.packageName, BuildConfig.DEBUG, BuildConfig.VERSION_NAME)

    /** Device tests show a dialog state without asking GitHub. */
    @VisibleForTesting
    fun showForTest(shown: UpdateState) {
        mutableState.value = shown
        mutableDialogVisible.value = true
    }

    fun showDialog() {
        mutableDialogVisible.value = true
    }

    fun hideDialog() {
        mutableDialogVisible.value = false
        // A finished answer is not kept; a download or a ready update is.
        when (state.value) {
            UpdateState.UpToDate, is UpdateState.Failed -> mutableState.value = UpdateState.Idle
            else -> Unit
        }
    }

    /** Check for updates: asks GitHub now. */
    fun check(context: Context) {
        val app = context.applicationContext
        if (busy()) return
        automatic = false
        job = scope.launch { checkNow(app) }
    }

    fun download(
        context: Context,
        update: AppUpdate,
    ) {
        val app = context.applicationContext
        if (busy()) return
        automatic = false
        job =
            scope.launch {
                downloadNow(app, update)
                // The user tapped Update: install as soon as the download is verified.
                val ready = state.value as? UpdateState.Ready ?: return@launch
                installNow(app, ready.update, ready.file)
            }
    }

    fun install(
        context: Context,
        update: AppUpdate,
        file: File,
    ) {
        val app = context.applicationContext
        val confirmation = pendingConfirmation
        if (confirmation != null && foreground) {
            pendingConfirmation = null
            mutableState.value = UpdateState.Installing(update)
            startConfirmation(app, confirmation)
            return
        }
        scope.launch { installNow(app, update, file) }
    }

    /** The app came to the screen: an automatic check once a day, and a waiting update shows. */
    fun onAppStarted(context: Context) {
        foreground = true
        val app = context.applicationContext
        if (pendingConfirmation != null || (automatic && state.value is UpdateState.Ready)) {
            mutableDialogVisible.value = true
        }
        if (!autoUpdateEnabled(app) || channel(app) == UpdateChannel.None || busy()) return
        val preferences = preferences(app)
        val now = System.currentTimeMillis()
        if (!isAutoUpdateCheckDue(preferences.getLong(APP_UPDATE_LAST_CHECK_PREFERENCE, 0), now)) return
        preferences.edit().putLong(APP_UPDATE_LAST_CHECK_PREFERENCE, now).apply()
        automatic = true
        job =
            scope.launch {
                checkNow(app)
                val available = state.value as? UpdateState.Available ?: return@launch
                // About 34 MB: only on Wi-Fi or another unmetered network.
                if (unmetered(app)) downloadNow(app, available.update)
            }
    }

    /** The app left the screen: an automatic update installs now, unless a video is playing. */
    fun onAppStopped(context: Context) {
        foreground = false
        val app = context.applicationContext
        val ready = state.value as? UpdateState.Ready ?: return
        if (!automatic || !autoUpdateEnabled(app) || WebVideoBridge.playback.value.isPlaying()) return
        scope.launch { installNow(app, ready.update, ready.file) }
    }

    private fun busy(): Boolean =
        job?.isActive == true ||
            state.value is UpdateState.Installing

    private suspend fun checkNow(context: Context) {
        mutableState.value = UpdateState.Checking
        mutableState.value =
            try {
                val update =
                    when (val channel = channel(context)) {
                        UpdateChannel.Official -> parseOfficialRelease(fetchText(UPDATE_LATEST_RELEASE_URL), BuildConfig.VERSION_NAME)
                        is UpdateChannel.Preview -> parsePreviewRelease(fetchText(UPDATE_PREVIEW_RELEASE_URL), channel)
                        UpdateChannel.None -> null
                    }
                if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
            } catch (error: UpdateException) {
                UpdateState.Failed(error.problem, null)
            } catch (_: IOException) {
                UpdateState.Failed(UpdateProblem.OFFLINE, null)
            } catch (_: org.json.JSONException) {
                UpdateState.Failed(UpdateProblem.GITHUB_ERROR, null)
            }
    }

    private suspend fun downloadNow(
        context: Context,
        update: AppUpdate,
    ) {
        mutableState.value = UpdateState.Downloading(update, null)
        val folder = File(context.cacheDir, "app-update").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val part = File(folder, "update.apk.part")
        val apk = File(folder, "update.apk")
        mutableState.value =
            try {
                val expected =
                    update.sha256 ?: update.checksumUrl?.let { parseSha256File(fetchText(it)) }
                        ?: throw UpdateException(UpdateProblem.CHECKSUM, "GitHub lists no checksum for this download")
                val actual = downloadTo(update, part)
                if (actual != expected) throw UpdateException(UpdateProblem.CHECKSUM, "The download's SHA-256 does not match GitHub's")
                verifyArchive(context, part)
                if (!part.renameTo(apk)) throw IOException("Could not keep the download")
                UpdateState.Ready(update, apk)
            } catch (error: UpdateException) {
                part.delete()
                UpdateState.Failed(error.problem, update)
            } catch (_: IOException) {
                part.delete()
                UpdateState.Failed(UpdateProblem.OFFLINE, update)
            }
    }

    /** Streams the APK to [file] and returns its SHA-256 in lowercase hex. */
    private fun downloadTo(
        update: AppUpdate,
        file: File,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open(update.downloadUrl).use { response ->
            val body = response.body ?: throw UpdateException(UpdateProblem.GITHUB_ERROR, "GitHub sent no file")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes.takeIf { it > 0 }
            if (total != null && total > MAX_UPDATE_BYTES) throw UpdateException(UpdateProblem.WRONG_APP, "The download is too big")
            var read = 0L
            var lastShown = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > MAX_UPDATE_BYTES) throw UpdateException(UpdateProblem.WRONG_APP, "The download is too big")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = total?.let { (read * 100 / it).toInt() } ?: 0
                        if (percent != lastShown) {
                            lastShown = percent
                            mutableState.value = UpdateState.Downloading(update, total?.let { (read.toFloat() / it).coerceAtMost(1f) })
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The download must be this app and newer than it; Android checks the signature itself. */
    private fun verifyArchive(
        context: Context,
        file: File,
    ) {
        val info =
            context.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: throw UpdateException(UpdateProblem.WRONG_APP, "The download is not an Android app")
        if (info.packageName != context.packageName || PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE) {
            throw UpdateException(UpdateProblem.WRONG_APP, "The download is not a newer version of this app")
        }
    }

    private fun installNow(
        context: Context,
        update: AppUpdate,
        file: File,
    ) {
        mutableState.value = UpdateState.Installing(update)
        lastInstall = update to file
        try {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    // An app updating itself may skip Android's confirmation on Android 12 and newer.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val flags =
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val status =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, AppUpdateReceiver::class.java).setPackage(context.packageName),
                        flags,
                    )
                session.commit(status.intentSender)
            }
        } catch (_: IOException) {
            mutableState.value = UpdateState.Failed(UpdateProblem.INSTALL_FAILED, update, file)
        } catch (_: SecurityException) {
            mutableState.value = UpdateState.Failed(UpdateProblem.INSTALL_FAILED, update, file)
        }
    }

    /** The update the installer is answering about. */
    private var lastInstall: Pair<AppUpdate, File>? = null

    /** Android needs the user's tap: show its screen now, or when the app is next opened. */
    fun onNeedsConfirmation(
        context: Context,
        confirmation: Intent?,
    ) {
        val (update, file) = lastInstall ?: return
        if (confirmation == null) {
            mutableState.value = UpdateState.Failed(UpdateProblem.INSTALL_FAILED, update, file)
            return
        }
        if (foreground) {
            startConfirmation(context, confirmation)
        } else {
            pendingConfirmation = confirmation
            mutableState.value = UpdateState.Ready(update, file)
        }
    }

    fun onInstallFinished(status: Int) {
        val (update, file) = lastInstall ?: return
        mutableState.value =
            when (status) {
                // The new version replaces this process; nothing is left to show.
                PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed(UpdateProblem.CANCELLED, update, file)
                else -> UpdateState.Failed(UpdateProblem.INSTALL_FAILED, update, file)
            }
    }

    private fun startConfirmation(
        context: Context,
        confirmation: Intent,
    ) {
        try {
            context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            onInstallFinished(PackageInstaller.STATUS_FAILURE)
        }
    }

    private fun fetchText(url: String): String =
        open(url).use { response ->
            response.body?.string() ?: throw UpdateException(UpdateProblem.GITHUB_ERROR, "GitHub sent nothing")
        }

    private fun open(url: String): Response {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", if ("api.github.com" in url) "application/vnd.github+json" else "application/octet-stream")
                .header("User-Agent", "DualSub-Replay/${BuildConfig.VERSION_NAME}")
                .build()
        if (!isAllowedUpdateHost(request.url.host) || !request.url.isHttps) {
            throw UpdateException(UpdateProblem.GITHUB_ERROR, "Not a GitHub address")
        }
        val response = client.newCall(request).execute()
        val problem =
            when {
                !isAllowedUpdateHost(response.request.url.host) || !response.request.url.isHttps -> UpdateProblem.GITHUB_ERROR
                response.code == 403 || response.code == 429 -> UpdateProblem.GITHUB_BUSY
                !response.isSuccessful -> UpdateProblem.GITHUB_ERROR
                else -> null
            }
        if (problem != null) {
            response.close()
            throw UpdateException(problem, "GitHub answered ${response.code}")
        }
        return response
    }

    private fun unmetered(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.activeNetwork != null && !connectivity.isActiveNetworkMetered
    }

    private fun preferences(context: Context) = context.getSharedPreferences("dual_sub_preferences", 0)

    fun autoUpdateEnabled(context: Context): Boolean = preferences(context).getBoolean(APP_AUTO_UPDATE_PREFERENCE, false)
}

/** Android's answer to an install session [AppUpdater] committed. */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                AppUpdater.onNeedsConfirmation(context, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
            else -> AppUpdater.onInstallFinished(status)
        }
    }
}
