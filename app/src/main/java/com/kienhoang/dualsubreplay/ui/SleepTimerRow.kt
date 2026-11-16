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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.playback.SLEEP_TIMER_MINUTES
import com.kienhoang.dualsubreplay.playback.SleepTimer
import com.kienhoang.dualsubreplay.playback.SleepTimerChoice
import kotlinx.coroutines.delay

/**
 * The gear popup's Sleep timer row (25kiuo, Discussion #81): Off, 15 to 60 minutes, or End of video.
 * The chip shows the time left while minutes run.
 */
@Composable
internal fun SleepTimerRow() {
    val run by SleepTimer.run.collectAsState()
    var open by remember { mutableStateOf(false) }
    var minutesLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        while (run?.endsAtMs != null) {
            minutesLeft = SleepTimer.minutesLeft() ?: 0
            delay(SLEEP_TIMER_ROW_REFRESH_MS)
        }
    }
    val label =
        when (run?.choice) {
            null -> stringResource(R.string.sleep_timer_off)
            SleepTimerChoice.EndOfVideo -> stringResource(R.string.sleep_timer_end_of_video)
            is SleepTimerChoice.Minutes -> stringResource(R.string.sleep_timer_left, minutesLeft)
        }
    Row(
        Modifier.fillMaxWidth().testTag("sleep_timer"),
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
