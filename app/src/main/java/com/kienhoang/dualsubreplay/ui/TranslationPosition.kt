package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

internal const val TRANSLATION_POSITION_PREFERENCE = "translation_position"

/** Where the translation line sits next to the original line (issue #115). */
enum class TranslationPosition(
    val storageValue: String,
    @StringRes val labelRes: Int,
) {
    BELOW("below", R.string.settings_translation_position_below),
    ABOVE("above", R.string.settings_translation_position_above),
}

internal fun storedTranslationPosition(raw: String?): TranslationPosition =
    TranslationPosition.entries.firstOrNull { it.storageValue == raw } ?: TranslationPosition.BELOW

/** The subtitle lines to draw, top first; a hidden line is null and left out. */
internal fun <T> subtitleLineOrder(
    original: T?,
    translation: T?,
    position: TranslationPosition,
): List<T> = if (position == TranslationPosition.ABOVE) listOfNotNull(translation, original) else listOfNotNull(original, translation)

@Composable
internal fun TranslationPositionSettings(
    position: TranslationPosition,
    onChange: (TranslationPosition) -> Unit,
) {
    Text(stringResource(R.string.settings_translation_position_title), style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TranslationPosition.entries.forEach { option ->
            FilterChip(
                selected = position == option,
                onClick = { onChange(option) },
                label = { Text(stringResource(option.labelRes)) },
                modifier = Modifier.testTag("translation_position_${option.storageValue}"),
            )
        }
    }
}
