package com.kienhoang.dualsubreplay.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R

@Composable
internal fun CaptionFormatSettings(
    format: CaptionFormat,
    onChange: (CaptionFormat) -> Unit,
) {
    Text(stringResource(R.string.settings_caption_format_title), style = MaterialTheme.typography.titleSmall)
    CaptionFormat.entries.forEach { option ->
        FilterChip(
            selected = format == option,
            onClick = { onChange(option) },
            label = { Text(stringResource(option.labelRes)) },
            modifier = Modifier.testTag("caption_format_${option.storageValue}"),
        )
    }
    Text(
        stringResource(R.string.settings_caption_format_hint),
        style = MaterialTheme.typography.bodySmall,
    )
}
