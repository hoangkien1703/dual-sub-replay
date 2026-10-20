package com.kienhoang.dualsubreplay.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.UPDATE_PREVIEW_PAGE_URL
import com.kienhoang.dualsubreplay.data.UPDATE_RELEASES_PAGE_URL
import com.kienhoang.dualsubreplay.data.UpdateChannel
import com.kienhoang.dualsubreplay.update.APP_AUTO_UPDATE_PREFERENCE
import com.kienhoang.dualsubreplay.update.AppUpdater
import com.kienhoang.dualsubreplay.update.UpdateProblem
import com.kienhoang.dualsubreplay.update.UpdateState

/** The drawer's Check for updates row: it asks GitHub only when tapped. */
@Composable
internal fun CheckForUpdatesRow(onOpen: () -> Unit) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val available =
        when (val current = state) {
            is UpdateState.Available -> current.update.versionName
            is UpdateState.Ready -> current.update.versionName
            else -> null
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .clickable(role = Role.Button) {
                    onOpen()
                    AppUpdater.showDialog()
                    if (state !is UpdateState.Available && state !is UpdateState.Ready && state !is UpdateState.Downloading) {
                        AppUpdater.check(context)
                    }
                }.testTag("check_for_updates")
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.updates_check), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (available != null) {
                    stringResource(R.string.updates_available_short, available)
                } else {
                    stringResource(R.string.updates_version, BuildConfig.VERSION_NAME)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (available != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The update dialog, shown from the drawer or when an automatic update waits for a tap. */
@Composable
internal fun AppUpdateDialogHost(channel: UpdateChannel = AppUpdater.channel(LocalContext.current)) {
    val visible by AppUpdater.dialogVisible.collectAsState()
    if (!visible) return
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    var noBrowser by remember { mutableStateOf(false) }
    val close = { AppUpdater.hideDialog() }
    val open = { url: String -> noBrowser = !openExternalPage(context, url) }
    if (channel == UpdateChannel.None) {
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.updates_check)) },
            text = { UpdateMessage(stringResource(R.string.updates_unsupported), noBrowser) },
            confirmButton = { UpdateButton(R.string.updates_open_github, "update_open_github") { open(UPDATE_RELEASES_PAGE_URL) } },
            dismissButton = { UpdateButton(R.string.updates_close, "update_close", close) },
        )
        return
    }
    val current = state
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.updates_check)) },
        text = { UpdateDialogText(current, noBrowser) },
        confirmButton = { UpdateConfirmButton(current, close) },
        dismissButton = {
            when (current) {
                is UpdateState.Available ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        UpdateButton(R.string.updates_whats_new, "update_whats_new") { open(current.update.pageUrl) }
                        UpdateButton(R.string.updates_not_now, "update_not_now", close)
                    }
                is UpdateState.Ready, is UpdateState.Failed -> UpdateButton(R.string.updates_not_now, "update_not_now", close)
                UpdateState.UpToDate ->
                    UpdateButton(R.string.updates_whats_new, "update_whats_new") {
                        open(if (channel is UpdateChannel.Preview) UPDATE_PREVIEW_PAGE_URL else UPDATE_RELEASES_PAGE_URL)
                    }
                else -> Unit
            }
        },
    )
}

@Composable
private fun UpdateButton(
    label: Int,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(stringResource(label)) }
}

@Composable
private fun UpdateDialogText(
    current: UpdateState,
    noBrowser: Boolean,
) {
    val context = LocalContext.current
    when (current) {
        UpdateState.Idle, UpdateState.Checking -> Busy(stringResource(R.string.updates_checking), null)
        UpdateState.UpToDate -> UpdateMessage(stringResource(R.string.updates_up_to_date, BuildConfig.VERSION_NAME), noBrowser)
        is UpdateState.Available ->
            UpdateMessage(
                stringResource(R.string.updates_available, current.update.versionName, BuildConfig.VERSION_NAME) +
                    sizeLine(context, current.update.sizeBytes) + "\n\n" + stringResource(R.string.updates_first_time_note),
                noBrowser,
            )
        is UpdateState.Downloading ->
            Busy(stringResource(R.string.updates_downloading, current.update.versionName), current.progress ?: -1f)
        is UpdateState.Ready -> UpdateMessage(stringResource(R.string.updates_ready, current.update.versionName), noBrowser)
        is UpdateState.Installing -> Busy(stringResource(R.string.updates_installing), null)
        is UpdateState.Failed -> UpdateMessage(stringResource(current.problem.messageRes), noBrowser)
    }
}

@Composable
private fun UpdateConfirmButton(
    current: UpdateState,
    close: () -> Unit,
) {
    val context = LocalContext.current
    when (current) {
        is UpdateState.Available ->
            UpdateButton(R.string.updates_update, "update_download") { AppUpdater.download(context, current.update) }
        is UpdateState.Ready ->
            UpdateButton(R.string.updates_install, "update_install") { AppUpdater.install(context, current.update, current.file) }
        is UpdateState.Failed ->
            UpdateButton(R.string.updates_try_again, "update_try_again") {
                when {
                    current.file != null && current.update != null -> AppUpdater.install(context, current.update, current.file)
                    current.update != null -> AppUpdater.download(context, current.update)
                    else -> AppUpdater.check(context)
                }
            }
        UpdateState.UpToDate -> UpdateButton(R.string.updates_close, "update_close", close)
        // Hiding keeps a download or an install going.
        else -> UpdateButton(R.string.updates_hide, "update_hide", close)
    }
}

private fun sizeLine(
    context: android.content.Context,
    bytes: Long,
): String = if (bytes > 0) " (" + Formatter.formatShortFileSize(context, bytes) + ")" else ""

private val UpdateProblem.messageRes: Int
    get() =
        when (this) {
            UpdateProblem.OFFLINE -> R.string.updates_problem_offline
            UpdateProblem.GITHUB_BUSY -> R.string.updates_problem_busy
            UpdateProblem.GITHUB_ERROR -> R.string.updates_problem_github
            UpdateProblem.CHECKSUM -> R.string.updates_problem_checksum
            UpdateProblem.WRONG_APP -> R.string.updates_problem_wrong_app
            UpdateProblem.INSTALL_FAILED -> R.string.updates_problem_install
            UpdateProblem.CANCELLED -> R.string.updates_problem_cancelled
        }

@Composable
private fun UpdateMessage(
    text: String,
    noBrowser: Boolean,
) {
    Column {
        Text(text, modifier = Modifier.testTag("update_message"))
        if (noBrowser) {
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.navigation_no_browser), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** [progress] null spins; below 0 is a download of unknown size. */
@Composable
private fun Busy(
    text: String,
    progress: Float?,
) {
    Column {
        Text(text, modifier = Modifier.testTag("update_message"))
        Spacer(Modifier.size(12.dp))
        when {
            progress == null -> CircularProgressIndicator(Modifier.size(28.dp))
            progress < 0f -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** More settings → App updates. */
@Composable
internal fun AppUpdateSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var auto by remember { mutableStateOf(preferences.getBoolean(APP_AUTO_UPDATE_PREFERENCE, false)) }
    val channel = remember(context) { AppUpdater.channel(context) }
    SettingsSwitchRow(
        title = stringResource(R.string.updates_auto_title),
        description = stringResource(R.string.updates_auto_description),
        checked = auto,
        onCheckedChange = {
            auto = it
            preferences.edit().putBoolean(APP_AUTO_UPDATE_PREFERENCE, it).apply()
        },
        testTag = "auto_update_switch",
    )
    if (channel == UpdateChannel.None) SettingsHint(stringResource(R.string.updates_auto_unavailable))
}
