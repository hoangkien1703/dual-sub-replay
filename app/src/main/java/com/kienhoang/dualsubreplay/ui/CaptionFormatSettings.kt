package com.kienhoang.dualsubreplay.ui

import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun CaptionFormatSettings(
    format: CaptionFormat,
    onChange: (CaptionFormat) -> Unit,
) {
    Text("Caption format", style = MaterialTheme.typography.titleSmall)
    CaptionFormat.entries.forEach { option ->
        FilterChip(
            selected = format == option,
            onClick = { onChange(option) },
            label = { Text(option.label) },
            modifier = Modifier.testTag("caption_format_${option.storageValue}"),
        )
    }
    Text(
        "Short phrases pair each phrase with its translation. Whole sentences show more context.",
        style = MaterialTheme.typography.bodySmall,
    )
}
