package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.playback.SLEEP_TIMER_MINUTES
import com.kienhoang.dualsubreplay.playback.SleepTimer
import com.kienhoang.dualsubreplay.playback.SleepTimerChoice
import com.kienhoang.dualsubreplay.playback.SleepTimerRun
import kotlinx.coroutines.delay

/** Settings → Playback → Show in quick settings: the Sleep timer also sits in the gear popup. Off by default. */
internal const val SLEEP_TIMER_QUICK_PREFERENCE = "sleep_timer_in_quick_settings"

/** Reset by Reset all settings. */
internal val PLAYBACK_SETTING_KEYS = listOf(SLEEP_TIMER_QUICK_PREFERENCE)

@Composable
internal fun rememberSleepTimerInQuickSettings(): State<Boolean> =
    rememberStoredSetting(SLEEP_TIMER_QUICK_PREFERENCE) { it.getBoolean(SLEEP_TIMER_QUICK_PREFERENCE, false) }

/** The whole minutes left while a minutes timer runs, read again every few seconds; 0 otherwise. */
@Composable
internal fun rememberSleepTimerMinutesLeft(run: SleepTimerRun?): Int {
    var minutesLeft by remember { mutableIntStateOf(SleepTimer.minutesLeft() ?: 0) }
    LaunchedEffect(run) {
        while (run?.endsAtMs != null) {
            minutesLeft = SleepTimer.minutesLeft() ?: 0
            delay(SLEEP_TIMER_ROW_REFRESH_MS)
        }
    }
    return minutesLeft
}

/**
 * Settings → Playback (owner pick 2026-11-16): the Sleep timer, and whether the gear popup shows it
 * too. The timer moved here from the popup, so the popup stays small unless the learner wants it.
 */
@Composable
internal fun PlaybackSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val inQuickSettings by rememberSleepTimerInQuickSettings()
    SleepTimerRow(Modifier.settingAnchor("sleep_timer").padding(vertical = 6.dp))
    SettingsHint(stringResource(R.string.sleep_timer_description))
    SettingsSwitchRow(
        title = stringResource(R.string.sleep_timer_quick_title),
        description = stringResource(R.string.sleep_timer_quick_description),
        checked = inQuickSettings,
        onCheckedChange = { preferences.edit().putBoolean(SLEEP_TIMER_QUICK_PREFERENCE, it).apply() },
        testTag = "sleep_timer_quick_switch",
    )
}

/**
 * The Sleep timer row (25kiuo, Discussion #81): Off, 15 to 60 minutes, or End of video. It sits in
 * Settings → Playback, and in the gear popup when Show in quick settings is on. The chip shows the
 * time left while minutes run.
 */
@Composable
internal fun SleepTimerRow(modifier: Modifier = Modifier) {
    val run by SleepTimer.run.collectAsState()
    var open by remember { mutableStateOf(false) }
    val minutesLeft = rememberSleepTimerMinutesLeft(run)
    val label =
        when (run?.choice) {
            null -> stringResource(R.string.sleep_timer_off)
            SleepTimerChoice.EndOfVideo -> stringResource(R.string.sleep_timer_end_of_video)
            is SleepTimerChoice.Minutes -> stringResource(R.string.sleep_timer_left, minutesLeft)
        }
    Row(
        modifier.fillMaxWidth().testTag("sleep_timer"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.sleep_timer_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Box {
            FilterChip(
                selected = run != null,
                onClick = { open = true },
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.testTag("sleep_timer_chip"),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                fun pick(choice: SleepTimerChoice?) {
                    SleepTimer.set(choice)
                    open = false
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_off)) },
                    onClick = { pick(null) },
                    modifier = Modifier.testTag("sleep_timer_off"),
                )
                SLEEP_TIMER_MINUTES.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sleep_timer_minutes, minutes)) },
                        onClick = { pick(SleepTimerChoice.Minutes(minutes)) },
                        modifier = Modifier.testTag("sleep_timer_$minutes"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_end_of_video)) },
                    onClick = { pick(SleepTimerChoice.EndOfVideo) },
                    modifier = Modifier.testTag("sleep_timer_end_of_video"),
                )
                Text(
                    stringResource(R.string.sleep_timer_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val SLEEP_TIMER_ROW_REFRESH_MS = 5_000L
