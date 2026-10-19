package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.GoogleWait

/** The chip text of each Wait for Google choice. */
@get:StringRes
internal val GoogleWait.labelRes: Int
    get() =
        when (this) {
            GoogleWait.AUTO -> R.string.settings_google_wait_auto
            GoogleWait.TWO_SECONDS -> R.string.settings_google_wait_2
            GoogleWait.FOUR_SECONDS -> R.string.settings_google_wait_4
            GoogleWait.EIGHT_SECONDS -> R.string.settings_google_wait_8
        }

/** Settings → Translation → Wait for Google, shown while Google Translate is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoogleWaitSettings(
    wait: GoogleWait,
    onChange: (GoogleWait) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_google_wait_title))
        Text(
            stringResource(R.string.settings_google_wait_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoogleWait.entries.forEach { option ->
                FilterChip(
                    selected = wait == option,
                    onClick = { onChange(option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.testTag("google_wait_${option.storageValue}"),
                )
            }
        }
    }
}
