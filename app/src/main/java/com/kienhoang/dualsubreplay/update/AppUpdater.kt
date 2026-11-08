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
import com.kienhoang.dualsubreplay.data.ProblemArea
import com.kienhoang.dualsubreplay.data.ProblemLog
import com.kienhoang.dualsubreplay.data.ReminderMemory
import com.kienhoang.dualsubreplay.data.STALE_PART_MS
import com.kienhoang.dualsubreplay.data.UPDATE_INFO_URL
import com.kienhoang.dualsubreplay.data.UPDATE_LATEST_RELEASE_URL
import com.kienhoang.dualsubreplay.data.UPDATE_PREVIEW_RELEASE_URL
import com.kienhoang.dualsubreplay.data.UpdateChannel
import com.kienhoang.dualsubreplay.data.UpdateDownloadRecord
import com.kienhoang.dualsubreplay.data.UpdatePatch
import com.kienhoang.dualsubreplay.data.UpdateReminder
import com.kienhoang.dualsubreplay.data.applyUpdatePatch
import com.kienhoang.dualsubreplay.data.contentRangeStart
import com.kienhoang.dualsubreplay.data.isAllowedUpdateHost
import com.kienhoang.dualsubreplay.data.isSameUpdateDownload
import com.kienhoang.dualsubreplay.data.parseOfficialRelease
import com.kienhoang.dualsubreplay.data.parsePreviewRelease
import com.kienhoang.dualsubreplay.data.parseSha256File
import com.kienhoang.dualsubreplay.data.parseUpdateDownloadRecord
import com.kienhoang.dualsubreplay.data.parseUpdateInfo
import com.kienhoang.dualsubreplay.data.updateChannelFor
import com.kienhoang.dualsubreplay.data.updateDownloadRecordJson
import com.kienhoang.dualsubreplay.data.updateFromInfo
import com.kienhoang.dualsubreplay.data.updateReminder
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import com.kienhoang.dualsubreplay.playback.isPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Settings → App updates → Update automatically, off by default (owner request 2026-10-20). */
internal const val APP_AUTO_UPDATE_PREFERENCE = "app_auto_update"

/**
 * Settings → App updates → Tell me about new versions, on by default (owner request 2026-10-30): once a
 * day the app reads the latest release's `update-info.json` (and the remote switches) and shows a
 * reminder for a newer version. Off, it asks GitHub only when Check for updates is tapped.
 */
internal const val UPDATE_REMINDERS_PREFERENCE = "update_reminders"

/** When the daily check last asked GitHub; not a setting, so Reset all settings keeps it. */
private const val APP_UPDATE_LAST_CHECK_PREFERENCE = "app_update_last_check"
private const val DAY_MS = 24L * 60 * 60 * 1000

/** The update download in the app's cache: the APK, the half-downloaded part, and what they are. */
private const val UPDATE_FOLDER = "app-update"
private const val UPDATE_APK = "update.apk"
private const val UPDATE_PART = "update.apk.part"
private const val UPDATE_RECORD = "update.json"

/** A small patch (owner pick 2026-11-08) and the APK it rebuilds from the installed one, until checked. */
private const val UPDATE_PATCH = "update.patch"
private const val UPDATE_PATCHED = "update.apk.patched"

/** A connection that drops mid-download is tried again after these waits, carrying on where it stopped. */
private val DOWNLOAD_RETRY_DELAYS_MS = longArrayOf(2_000, 5_000)

/** The reminder last shown, when, and the version Skip this version hid (kept by Reset all settings). */
private const val REMINDER_VERSION_PREFERENCE = "update_reminder_version"
private const val REMINDER_SHOWN_AT_PREFERENCE = "update_reminder_shown_at"
private const val REMINDER_SKIPPED_PREFERENCE = "update_reminder_skipped"
private const val REMINDER_SECURITY_FIX_PREFERENCE = "update_reminder_security_fix"
private const val REMINDER_SECURITY_SINCE_PREFERENCE = "update_reminder_security_since"

/** The daily check asks GitHub at most once a day. */
internal fun isAutoUpdateCheckDue(
    lastCheckMs: Long,
    nowMs: Long,
): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= DAY_MS || nowMs < lastCheckMs

/** What went wrong, shown in the update dialog. */
internal enum class UpdateProblem { OFFLINE, INTERRUPTED, GITHUB_BUSY, GITHUB_ERROR, CHECKSUM, WRONG_APP, INSTALL_FAILED, CANCELLED }

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

    private val mutableReminder = MutableStateFlow(UpdateReminder.NONE)

    /** The banner or security dialog the daily check found; the screen shows it once no video plays. */
    val reminder: StateFlow<UpdateReminder> = mutableReminder.asStateFlow()

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

    fun channel(context: Context): UpdateChannel =
        updateChannelFor(context.packageName, BuildConfig.DEBUG, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.orEmpty().toList())

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
        job = scope.launch { downloadAndInstall(app, update) }
    }

    /**
     * Try again after a download stopped: GitHub is asked first, so a version released meanwhile
     * replaces the half download instead of being offered right after it; the same version carries on.
     */
    fun retryDownload(context: Context) {
        val app = context.applicationContext
        if (busy()) return
        automatic = false
        job =
            scope.launch {
                checkNow(app)
                when (val found = state.value) {
                    is UpdateState.Available -> downloadAndInstall(app, found.update)
                    is UpdateState.Ready -> installNow(app, found.update, found.file)
                    else -> Unit
                }
            }
    }

    /** The user tapped Update: install as soon as the download is verified. */
    private suspend fun downloadAndInstall(
        context: Context,
        update: AppUpdate,
    ) {
        downloadNow(context, update, asked = true)
        val ready = state.value as? UpdateState.Ready ?: return
        installNow(context, ready.update, ready.file)
    }

    fun install(
        context: Context,
        update: AppUpdate,
        file: File,
    ) {
        val app = context.applicationContext
        val confirmation = pendingConfirmation
        pendingConfirmation = null
        // Android's "Update this app?" screen of the last try, while that try still waits for it.
        if (confirmation != null && foreground && sessionWaiting(app, installSessionId)) {
            mutableState.value = UpdateState.Installing(update)
            // Home on it again brings Install back again.
            lastConfirmation = confirmation
            startConfirmation(app, confirmation)
            return
        }
        scope.launch { installNow(app, update, file) }
    }

    /**
     * The app came to the screen: once a day it checks for a newer version (with reminders or Update
     * automatically on), and a waiting update shows.
     */
    fun onAppStarted(context: Context) {
        foreground = true
        val app = context.applicationContext
        if (state.value is UpdateState.Installing) recoverUnansweredInstall(app)
        if (pendingConfirmation != null || (automatic && state.value is UpdateState.Ready)) {
            mutableDialogVisible.value = true
        }
        if (busy()) return
        val restore = !restored
        restored = true
        val reminders = remindersEnabled(app)
        val auto = autoUpdateEnabled(app)
        val dailyCheck = (reminders || auto) && canUpdateItself(channel(app)) && dailyCheckDue(app)
        if (!restore && !dailyCheck) return
        job =
            scope.launch {
                if (restore) restoreDownload(app)
                if (!dailyCheck) return@launch
                automatic = auto
                checkNow(app)
                val available = state.value as? UpdateState.Available ?: return@launch
                // Update automatically installs an ordinary update itself; a security update is still shown.
                if (reminders && (!auto || available.update.critical)) {
                    mutableReminder.value =
                        updateReminder(available.update, reminderMemory(app, preferences(app)), System.currentTimeMillis())
                }
                // Up to 25 MB when no patch fits: only on Wi-Fi or another unmetered network.
                if (auto && unmetered(app)) downloadNow(app, available.update, asked = false)
            }
    }

    /** The first start of this process looks at the download a closed app left. */
    private var restored = false

    /** Once a day; asking now starts the next day's wait. */
    private fun dailyCheckDue(context: Context): Boolean {
        val preferences = preferences(context)
        val now = System.currentTimeMillis()
        if (!isAutoUpdateCheckDue(preferences.getLong(APP_UPDATE_LAST_CHECK_PREFERENCE, 0), now)) return false
        preferences.edit().putLong(APP_UPDATE_LAST_CHECK_PREFERENCE, now).apply()
        return true
    }

    /**
     * The app was closed with an update in its cache. An installed one is deleted; a finished one is
     * ready to install again, and a half one carries on from where it stopped when Update is tapped.
     * Android's install screens of the closed app cannot be shown again, so their sessions go too.
     */
    private fun restoreDownload(context: Context) {
        abandonOwnSessions(context)
        val folder = File(context.cacheDir, UPDATE_FOLDER)
        if (!folder.exists()) return
        val record = readRecord(folder)
        val apk = File(folder, UPDATE_APK)
        val part = File(folder, UPDATE_PART)
        when {
            record == null -> clearFolder(folder)
            apk.exists() -> {
                // Not newer than this app: it is the version now installed (or an older one), so it goes.
                if (runCatching { verifyArchive(context, apk) }.isFailure) {
                    clearFolder(folder)
                    return
                }
                mutableState.value = UpdateState.Ready(record.update, apk)
                automatic = !record.asked && autoUpdateEnabled(context)
                if (record.asked) showAgainOnce(folder, record)
            }
            part.exists() && System.currentTimeMillis() - part.lastModified() < STALE_PART_MS -> {
                if (!record.asked) return
                mutableState.value = UpdateState.Failed(UpdateProblem.INTERRUPTED, record.update)
                showAgainOnce(folder, record)
            }
            else -> clearFolder(folder)
        }
    }

    /** The update the user started shows when the app opens again, once. */
    private fun showAgainOnce(
        folder: File,
        record: UpdateDownloadRecord,
    ) {
        writeRecord(folder, record.copy(asked = false))
        mutableDialogVisible.value = true
    }

    /**
     * Back on the screen while Android never answered the install: the person left its "Update this
     * app?" screen with Home, or the try ended without a word. Install shows again instead of a spinner
     * that never ends; a silent install still in progress keeps its spinner.
     */
    private fun recoverUnansweredInstall(context: Context) {
        val (update, file) = lastInstall ?: return
        // The session is still being written; Android answers once it is committed.
        if (installSessionId < 0) return
        val confirmation = lastConfirmation
        val waiting = sessionWaiting(context, installSessionId)
        if (waiting && confirmation == null) return
        pendingConfirmation = confirmation?.takeIf { waiting }
        lastConfirmation = null
        mutableState.value = UpdateState.Ready(update, file)
        mutableDialogVisible.value = true
    }

    /** The update the reminder is about, while one is known. */
    private fun knownUpdate(): AppUpdate? =
        when (val current = state.value) {
            is UpdateState.Available -> current.update
            is UpdateState.Downloading -> current.update
            is UpdateState.Ready -> current.update
            is UpdateState.Failed -> current.update
            else -> null
        }

    private fun reminderMemory(
        context: Context,
        preferences: android.content.SharedPreferences,
    ): ReminderMemory =
        ReminderMemory(
            shownVersion = preferences.getString(REMINDER_VERSION_PREFERENCE, null),
            shownAtMs = preferences.getLong(REMINDER_SHOWN_AT_PREFERENCE, 0),
            skippedVersion = preferences.getString(REMINDER_SKIPPED_PREFERENCE, null),
            securityFixCode = preferences.getLong(REMINDER_SECURITY_FIX_PREFERENCE, 0),
            securitySinceMs = preferences.getLong(REMINDER_SECURITY_SINCE_PREFERENCE, 0),
            // Preview builds are installed for each test, so the week after an update would hide the banner there.
            installedAtMs =
                if (channel(context) is UpdateChannel.Preview) {
                    0L
                } else {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                },
        )

    /**
     * The reminder appeared on screen: the wait before it shows again starts now, and a security fix seen
     * for the first time starts its daily days (see `updateReminder`).
     */
    fun onReminderShown(context: Context) {
        val update = knownUpdate() ?: return
        val preferences = preferences(context.applicationContext)
        val now = System.currentTimeMillis()
        val editor =
            preferences
                .edit()
                .putString(REMINDER_VERSION_PREFERENCE, update.versionName)
                .putLong(REMINDER_SHOWN_AT_PREFERENCE, now)
        if (update.critical && preferences.getLong(REMINDER_SECURITY_FIX_PREFERENCE, 0) != update.securityFixCode) {
            editor.putLong(REMINDER_SECURITY_FIX_PREFERENCE, update.securityFixCode).putLong(REMINDER_SECURITY_SINCE_PREFERENCE, now)
        }
        editor.apply()
    }

    /** Not now, or the reminder led to the update dialog. */
    fun dismissReminder() {
        mutableReminder.value = UpdateReminder.NONE
    }

    /** Skip this version: never a banner for it; a newer one still waits for the week (a security update cannot be skipped). */
    fun skipReminderVersion(context: Context) {
        val update = knownUpdate()
        if (update != null && !update.critical) {
            preferences(context.applicationContext).edit().putString(REMINDER_SKIPPED_PREFERENCE, update.versionName).apply()
        }
        dismissReminder()
    }

    /** Update from a reminder: the dialog shows the version, and a security update starts downloading at once. */
    fun updateFromReminder(context: Context) {
        val update = knownUpdate()
        dismissReminder()
        showDialog()
        if (update != null && update.critical && state.value is UpdateState.Available) download(context, update)
    }

    /** Device tests show a reminder without asking GitHub. */
    @VisibleForTesting
    fun showReminderForTest(
        update: AppUpdate,
        shown: UpdateReminder,
    ) {
        mutableState.value = UpdateState.Available(update)
        mutableReminder.value = shown
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
                        UpdateChannel.Official -> officialUpdate()
                        is UpdateChannel.Preview -> parsePreviewRelease(fetchText(UPDATE_PREVIEW_RELEASE_URL), channel)
                        UpdateChannel.None, UpdateChannel.UnsupportedDevice -> null
                    }
                when {
                    update == null -> {
                        // Nothing newer: whatever the cache holds is this version or an older one.
                        clearFolder(File(context.cacheDir, UPDATE_FOLDER))
                        UpdateState.UpToDate
                    }
                    else -> downloadedUpdate(context, update) ?: UpdateState.Available(update)
                }
            } catch (error: UpdateException) {
                failed(error.problem, null)
            } catch (_: IOException) {
                failed(UpdateProblem.OFFLINE, null)
            } catch (_: org.json.JSONException) {
                failed(UpdateProblem.GITHUB_ERROR, null)
            }
    }

    /**
     * The latest release's `update-info.json` first: GitHub's download hosts carry no per-address limit,
     * and the file says whether the installed version misses a security fix. A release from before that
     * file (or a missing one) falls back to GitHub's API.
     */
    private fun officialUpdate(): AppUpdate? {
        val info =
            try {
                parseUpdateInfo(fetchText(UPDATE_INFO_URL))
            } catch (error: UpdateException) {
                if (error.problem != UpdateProblem.GITHUB_ERROR) throw error
                null
            }
        return if (info != null) {
            updateFromInfo(info, BuildConfig.VERSION_CODE.toLong())
        } else {
            parseOfficialRelease(fetchText(UPDATE_LATEST_RELEASE_URL), BuildConfig.VERSION_NAME)
        }
    }

    /** A failed step, also kept in the problem log that Report a problem shows. */
    private fun failed(
        problem: UpdateProblem,
        update: AppUpdate?,
        file: File? = null,
    ): UpdateState.Failed {
        ProblemLog.record(ProblemArea.APP_UPDATE, problem.name)
        return UpdateState.Failed(problem, update, file)
    }

    /**
     * Downloads [update] into the cache, or keeps the copy already there: a finished one is reused and a
     * half one carries on from where it stopped. [asked] is a tap on Update, not Update automatically.
     */
    private suspend fun downloadNow(
        context: Context,
        update: AppUpdate,
        asked: Boolean,
    ) {
        mutableState.value = UpdateState.Downloading(update, null)
        val folder = File(context.cacheDir, UPDATE_FOLDER).apply { mkdirs() }
        val part = File(folder, UPDATE_PART)
        mutableState.value =
            try {
                val expected =
                    update.sha256 ?: update.checksumUrl?.let { parseSha256File(fetchText(it)) }
                        ?: throw UpdateException(UpdateProblem.CHECKSUM, "GitHub lists no checksum for this download")
                val record = readRecord(folder)
                if (record == null || !isSameUpdateDownload(record, update, expected)) clearFolder(folder)
                writeRecord(folder, UpdateDownloadRecord(update, expected, asked))
                UpdateState.Ready(update, downloadVerified(context, update, expected, folder))
            } catch (error: UpdateException) {
                // A wrong file goes; a busy or odd answer from GitHub keeps the part for Try again.
                if (error.problem == UpdateProblem.CHECKSUM || error.problem == UpdateProblem.WRONG_APP) clearFolder(folder)
                failed(error.problem, update)
            } catch (_: IOException) {
                failed(if (part.length() > 0) UpdateProblem.INTERRUPTED else UpdateProblem.OFFLINE, update)
            }
    }

    /** The checked APK in [folder]: the one already there, or the part downloaded to its end. */
    private suspend fun downloadVerified(
        context: Context,
        update: AppUpdate,
        expected: String,
        folder: File,
    ): File {
        val apk = File(folder, UPDATE_APK)
        if (apk.exists()) {
            if (sha256Of(apk) == expected) {
                verifyArchive(context, apk)
                return apk
            }
            apk.delete()
        }
        val part = File(folder, UPDATE_PART)
        // A small patch first, unless a whole download was already under way.
        if (part.length() == 0L) update.patch?.let { patch -> patchedApk(context, update, patch, expected, folder)?.let { return it } }
        if (downloadWithRetries(update, part) != expected) {
            throw UpdateException(UpdateProblem.CHECKSUM, "The download's SHA-256 does not match GitHub's")
        }
        verifyArchive(context, part)
        if (!part.renameTo(apk)) throw IOException("Could not keep the download")
        return apk
    }

    /**
     * The new APK rebuilt from the installed one with [patch], checked like a whole download; null when
     * the patch cannot be used, so the whole APK is downloaded instead. Both files go either way.
     */
    private suspend fun patchedApk(
        context: Context,
        update: AppUpdate,
        patch: UpdatePatch,
        expected: String,
        folder: File,
    ): File? {
        val patchFile = File(folder, UPDATE_PATCH)
        val patched = File(folder, UPDATE_PATCHED)
        return try {
            val installed = context.applicationInfo
            // An app installed in parts has no single APK to start from.
            if (!installed.splitSourceDirs.isNullOrEmpty()) return null
            if (downloadWithRetries(update, patchFile, patch.downloadUrl, patch.sizeBytes) != patch.sha256) {
                throw IOException("The patch's SHA-256 does not match")
            }
            if (applyUpdatePatch(File(installed.sourceDir), patchFile, patched) != expected) {
                throw IOException("The patched APK's SHA-256 does not match the release")
            }
            verifyArchive(context, patched)
            val apk = File(folder, UPDATE_APK)
            if (!patched.renameTo(apk)) throw IOException("Could not keep the patched APK")
            apk
        } catch (_: IOException) {
            // Kept in the problem log; the whole download follows.
            ProblemLog.record(ProblemArea.APP_UPDATE, "PATCH_FAILED")
            null
        } finally {
            patchFile.delete()
            patched.delete()
        }
    }

    /** A connection that drops after some bytes arrived is tried again; one that never connects is not. */
    private suspend fun downloadWithRetries(
        update: AppUpdate,
        part: File,
        url: String = update.downloadUrl,
        sizeBytes: Long = update.sizeBytes,
    ): String {
        var attempt = 0
        while (true) {
            val before = part.length()
            try {
                return downloadTo(update, part, url, sizeBytes)
            } catch (error: IOException) {
                val retry = error !is UpdateException && part.length() > before && attempt < DOWNLOAD_RETRY_DELAYS_MS.size
                if (!retry) throw error
                delay(DOWNLOAD_RETRY_DELAYS_MS[attempt++])
            }
        }
    }

    /** The finished download of [update] already in the cache, from before the app was closed. */
    private fun downloadedUpdate(
        context: Context,
        update: AppUpdate,
    ): UpdateState.Ready? {
        val folder = File(context.cacheDir, UPDATE_FOLDER)
        val record = readRecord(folder) ?: return null
        val apk = File(folder, UPDATE_APK)
        if (!apk.exists() || !isSameUpdateDownload(record, update)) return null
        if (runCatching { verifyArchive(context, apk) }.isFailure) return null
        return UpdateState.Ready(update, apk)
    }

    /**
     * Streams the APK to [file], after the bytes a broken download left there when GitHub sends the rest,
     * and returns the whole file's SHA-256 in lowercase hex.
     */
    private fun downloadTo(
        update: AppUpdate,
        file: File,
        url: String,
        sizeBytes: Long,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val have = keptBytes(sizeBytes, file)
        if (have > 0) digest.updateFrom(file)
        // Killed after the last byte, before the check: nothing is left to download.
        if (have > 0 && have == sizeBytes) return digest.hex()
        open(url, from = have).use { response ->
            val body = response.body ?: throw UpdateException(UpdateProblem.GITHUB_ERROR, "GitHub sent no file")
            val resumed = have > 0 && response.code == 206 && contentRangeStart(response.header("Content-Range")) == have
            if (response.code == 206 && !resumed) {
                file.delete()
                throw IOException("GitHub sent another part of the file")
            }
            // GitHub sent the whole file again: start over.
            val start = if (resumed) have else 0L
            if (!resumed) digest.reset()
            val total = body.contentLength().takeIf { it > 0 }?.plus(start) ?: sizeBytes.takeIf { it > 0 }
            if (total != null && total > MAX_UPDATE_BYTES) throw UpdateException(UpdateProblem.WRONG_APP, "The download is too big")
            body.byteStream().use { input ->
                FileOutputStream(file, resumed).use { output -> copyWithProgress(input, output, digest, update, start, total) }
            }
        }
        return digest.hex()
    }

    /** The bytes of an earlier try that can carry on; a part bigger than the APK can be is deleted. */
    private fun keptBytes(
        sizeBytes: Long,
        file: File,
    ): Long {
        val have = file.length()
        if (have <= MAX_UPDATE_BYTES && (sizeBytes <= 0 || have <= sizeBytes)) return have
        file.delete()
        return 0
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        update: AppUpdate,
        start: Long,
        total: Long?,
    ) {
        var read = start
        var lastShown = -1
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

    private fun sha256Of(file: File): String = MessageDigest.getInstance("SHA-256").apply { updateFrom(file) }.hex()

    private fun MessageDigest.updateFrom(file: File) {
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private fun readRecord(folder: File): UpdateDownloadRecord? =
        runCatching { File(folder, UPDATE_RECORD).readText() }.getOrNull()?.let(::parseUpdateDownloadRecord)

    private fun writeRecord(
        folder: File,
        record: UpdateDownloadRecord,
    ) {
        // Without it the download only lasts while the app stays open.
        runCatching { File(folder, UPDATE_RECORD).writeText(updateDownloadRecordJson(record)) }
    }

    private fun clearFolder(folder: File) {
        folder.listFiles()?.forEach { it.delete() }
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
        installSessionId = -1
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
            // An earlier try's session holds a copy of the APK until it is answered or abandoned.
            abandonOwnSessions(context)
            val sessionId = installer.createSession(params)
            installSessionId = sessionId
            lastConfirmation = null
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
            mutableState.value = failed(UpdateProblem.INSTALL_FAILED, update, file)
        } catch (_: SecurityException) {
            mutableState.value = failed(UpdateProblem.INSTALL_FAILED, update, file)
        }
    }

    /** The update the installer is answering about. */
    private var lastInstall: Pair<AppUpdate, File>? = null

    /** The session of [lastInstall], and Android's "Update this app?" screen while it waits for a tap. */
    private var installSessionId = -1
    private var lastConfirmation: Intent? = null

    /** Android still holds that session, waiting for the person or still installing. */
    private fun sessionWaiting(
        context: Context,
        sessionId: Int,
    ): Boolean = sessionId >= 0 && runCatching { context.packageManager.packageInstaller.getSessionInfo(sessionId) }.getOrNull() != null

    private fun abandonOwnSessions(context: Context) {
        val installer = context.packageManager.packageInstaller
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach { session ->
            runCatching { installer.abandonSession(session.sessionId) }
        }
    }

    /** Android needs the user's tap: show its screen now, or when the app is next opened. */
    fun onNeedsConfirmation(
        context: Context,
        confirmation: Intent?,
    ) {
        val (update, file) = lastInstall ?: return
        if (confirmation == null) {
            mutableState.value = failed(UpdateProblem.INSTALL_FAILED, update, file)
            return
        }
        lastConfirmation = confirmation
        if (foreground) {
            startConfirmation(context, confirmation)
        } else {
            pendingConfirmation = confirmation
            mutableState.value = UpdateState.Ready(update, file)
        }
    }

    fun onInstallFinished(status: Int) {
        val (update, file) = lastInstall ?: return
        // Android answered: its screen of this try is done.
        lastConfirmation = null
        pendingConfirmation = null
        mutableState.value =
            when (status) {
                // The new version replaces this process; nothing is left to show.
                PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed(UpdateProblem.CANCELLED, update, file)
                else -> failed(UpdateProblem.INSTALL_FAILED, update, file)
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

    /** [from] above 0 asks only for the bytes after it, to carry on a broken download. */
    private fun open(
        url: String,
        from: Long = 0,
    ): Response {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", if ("api.github.com" in url) "application/vnd.github+json" else "application/octet-stream")
                .header("User-Agent", "DualSub-Replay/${BuildConfig.VERSION_NAME}")
                .apply { if (from > 0) header("Range", "bytes=$from-") }
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

    fun remindersEnabled(context: Context): Boolean = preferences(context).getBoolean(UPDATE_REMINDERS_PREFERENCE, true)
}

/** Official and preview builds find newer versions of themselves; the others only open GitHub. */
internal fun canUpdateItself(channel: UpdateChannel): Boolean = channel is UpdateChannel.Official || channel is UpdateChannel.Preview

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
