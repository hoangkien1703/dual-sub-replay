package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/** The drawer's language row: shows the interface language and opens the picker. */
@Composable
internal fun AppLanguageButton(onSelect: ((AppLanguageOption?) -> Unit)? = null) {
    val context = LocalContext.current
    val selected = remember { AppLanguageSettings.selected(context) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { choosing = true },
        modifier = Modifier.testTag("app_language_button"),
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = stringResource(R.string.navigation_app_language),
            modifier = Modifier.size(20.dp),
        )
        Text(
            selected?.nativeName ?: stringResource(R.string.navigation_device_language),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    if (choosing) {
        AppLanguageDialog(
            selected = selected,
            onDismiss = { choosing = false },
            onSelect = { option ->
                choosing = false
                if (option != selected) {
                    if (onSelect != null) {
                        onSelect(option)
                    } else {
                        context.findActivity()?.let { AppLanguageSettings.select(it, option) }
                    }
                }
            },
        )
    }
}

@Composable
private fun AppLanguageDialog(
    selected: AppLanguageOption?,
    onDismiss: () -> Unit,
    onSelect: (AppLanguageOption?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.navigation_app_language)) },
        text = {
            Column(
                Modifier
                    .selectableGroup()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                AppLanguageRow(
                    label = stringResource(R.string.navigation_device_language),
                    selected = selected == null,
                    testTag = "app_language_option_device",
                    onClick = { onSelect(null) },
                )
                APP_LANGUAGES.forEach { option ->
                    AppLanguageRow(
                        label = option.nativeName,
                        selected = selected == option,
                        testTag = "app_language_option_${option.tag}",
                        onClick = { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.navigation_cancel)) }
        },
    )
}

@Composable
private fun AppLanguageRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
