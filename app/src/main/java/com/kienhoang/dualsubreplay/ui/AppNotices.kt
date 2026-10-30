package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.AppUpdate
import com.kienhoang.dualsubreplay.data.ProblemLog
import com.kienhoang.dualsubreplay.data.RemoteNotice
import com.kienhoang.dualsubreplay.data.RemoteSwitchState
import com.kienhoang.dualsubreplay.data.UpdateReminder
import com.kienhoang.dualsubreplay.data.problemIssueUrl
import com.kienhoang.dualsubreplay.data.problemReport
import com.kienhoang.dualsubreplay.data.reminderVisible
import com.kienhoang.dualsubreplay.playback.WebVideoBridge
import com.kienhoang.dualsubreplay.playback.isPlaying
import com.kienhoang.dualsubreplay.update.AppUpdater
import com.kienhoang.dualsubreplay.update.UpdateState
import kotlinx.coroutines.launch

/** The remote notice last closed, as `id:key`, so it stays closed until a different one arrives. */
internal const val REMOTE_NOTICE_CLOSED_PREFERENCE = "remote_notice_closed"

private val RemoteNotice.textRes: Int
    get() =
        when (this) {
            RemoteNotice.YOUTUBE_CHANGED -> R.string.notice_youtube_changed
            RemoteNotice.GOOGLE_TRANSLATE_LIMITED -> R.string.notice_google_translate_limited
            RemoteNotice.UPDATE_RECOMMENDED -> R.string.notice_update_recommended
        }

/**
 * The update reminder, the security update dialog and a remote notice (owner request 2026-10-30).
 * They sit over the main screen at the bottom and never over a playing video: one found while a video
 * plays waits until it is paused.
 */
@Composable
internal fun AppNoticesHost() {
    val context = LocalContext.current
    val reminder by AppUpdater.reminder.collectAsState()
    val state by AppUpdater.state.collectAsState()
    val playback by WebVideoBridge.playback.collectAsState()
    val switches by RemoteSwitchState.switches.collectAsState()
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var closedNotice by remember { mutableStateOf(preferences.getString(REMOTE_NOTICE_CLOSED_PREFERENCE, null)) }
    val playing = playback.isPlaying()
    val update = (state as? UpdateState.Available)?.update
    val showReminder = update != null && reminderVisible(reminder, playing)
    LaunchedEffect(showReminder, update?.versionName) {
        if (showReminder) AppUpdater.onReminderShown(context)
    }
    if (showReminder && reminder == UpdateReminder.SECURITY && update != null) SecurityUpdateDialog(update)
    val notice = switches.notice
    val noticeId = notice?.let { "${it.id}:${switches.noticeKey}" }
    val showNotice = notice != null && !playing && noticeId != closedNotice
    if (!showNotice && !(showReminder && reminder == UpdateReminder.BANNER)) return
    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showNotice && notice != null) {
                NoticeCard(Icons.Default.Info, stringResource(notice.textRes), "remote_notice") {
                    TextButton(
                        onClick = {
                            closedNotice = noticeId
                            preferences.edit().putString(REMOTE_NOTICE_CLOSED_PREFERENCE, noticeId).apply()
                        },
                        modifier = Modifier.testTag("remote_notice_close"),
                    ) { Text(stringResource(R.string.notice_close)) }
                }
            }
            if (showReminder && reminder == UpdateReminder.BANNER && update != null) {
                NoticeCard(Icons.Default.SystemUpdate, stringResource(R.string.updates_banner, update.versionName), "update_banner") {
                    TextButton(onClick = { AppUpdater.skipReminderVersion(context) }, modifier = Modifier.testTag("update_banner_skip")) {
                        Text(stringResource(R.string.updates_skip_version))
                    }
                    TextButton(onClick = { AppUpdater.dismissReminder() }, modifier = Modifier.testTag("update_banner_not_now")) {
                        Text(stringResource(R.string.updates_not_now))
                    }
                    TextButton(onClick = { AppUpdater.updateFromReminder(context) }, modifier = Modifier.testTag("update_banner_update")) {
                        Text(stringResource(R.string.updates_update))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    text: String,
    tag: String,
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
    }
}

/**
 * A security update: shown at once, then daily for its first days and weekly after; it can always be
 * declined. What was fixed opens the release page (owner request 2026-10-30), so people can check the
 * warning themselves; the dialog stays for when they come back.
 */
@Composable
private fun SecurityUpdateDialog(update: AppUpdate) {
    val context = LocalContext.current
    var noBrowser by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { AppUpdater.dismissReminder() },
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
        title = { Text(stringResource(R.string.updates_security_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.updates_security_message, update.versionName, BuildConfig.VERSION_NAME),
                    modifier = Modifier.testTag("security_update_message"),
                )
                TextButton(
                    onClick = { noBrowser = !openExternalPage(context, update.pageUrl) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("security_update_details"),
                ) {
                    Text(stringResource(R.string.updates_security_details), style = MaterialTheme.typography.labelLarge)
                }
                if (noBrowser) Text(stringResource(R.string.navigation_no_browser), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { AppUpdater.updateFromReminder(context) }, modifier = Modifier.testTag("security_update_update")) {
                Text(stringResource(R.string.updates_update))
            }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdater.dismissReminder() }, modifier = Modifier.testTag("security_update_not_now")) {
                Text(stringResource(R.string.updates_not_now))
            }
        },
    )
}

/**
 * Menu → Report a problem (owner decision 2026-10-30, no server): shows exactly what would be sent, and
 * Open GitHub fills a new bug report with it for the person to read, change and send themselves.
 */
@Composable
internal fun ReportProblemDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val entries by ProblemLog.entries.collectAsState()
    val device = "${Build.MANUFACTURER} ${Build.MODEL}"
    val android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val report = remember(entries) { problemReport(BuildConfig.VERSION_NAME, android, device, entries) }
    var copied by remember { mutableStateOf(false) }
    var noBrowser by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_problem_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.report_problem_intro), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp).testTag("problem_report_text"),
                    )
                }
                if (noBrowser) {
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.navigation_no_browser), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { noBrowser = !openExternalPage(context, problemIssueUrl(report, BuildConfig.VERSION_NAME, device)) },
                modifier = Modifier.testTag("report_problem_open"),
            ) { Text(stringResource(R.string.report_problem_open)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DualSub Replay", report)))
                            copied = true
                        }
                    },
                    modifier = Modifier.testTag("report_problem_copy"),
                ) { Text(stringResource(if (copied) R.string.report_problem_copied else R.string.report_problem_copy)) }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("report_problem_close")) {
                    Text(stringResource(R.string.notice_close))
                }
            }
        },
    )
}
