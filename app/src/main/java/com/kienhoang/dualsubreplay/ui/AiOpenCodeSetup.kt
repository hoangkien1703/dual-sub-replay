package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiAssistantUiState
import com.kienhoang.dualsubreplay.assistant.AiConnectionTest
import com.kienhoang.dualsubreplay.assistant.TERMUX_ALLOW_APPS_COMMAND
import com.kienhoang.dualsubreplay.assistant.TERMUX_RUN_COMMAND_PERMISSION
import com.kienhoang.dualsubreplay.assistant.termuxCommandsAllowed
import kotlinx.coroutines.launch

/**
 * OpenCode on this phone needs no key: install Termux and OpenCode, let Termux take commands from
 * other apps, allow this app to send them, then Connect, which asks OpenCode for one word.
 */
@Composable
internal fun AiOpenCodeSetup(
    aiState: AiAssistantUiState,
    controller: AiAssistantController,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(termuxCommandsAllowed(context)) }
    val clipLabel = stringResource(R.string.ai_provider_opencode_phone)
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Column(Modifier.fillMaxWidth().testTag("ai_opencode_setup"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiOpenCodeStep(1, R.string.ai_opencode_step_install)
        AiOpenCodeStep(2, R.string.ai_opencode_step_command)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                TERMUX_ALLOW_APPS_COMMAND,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp).testTag("ai_opencode_command"),
            )
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, TERMUX_ALLOW_APPS_COMMAND)))
                    copied = true
                }
            },
            modifier = Modifier.testTag("ai_opencode_copy"),
        ) { Text(stringResource(R.string.ai_opencode_copy)) }
        if (copied) AiOpenCodeHint(stringResource(R.string.ai_opencode_copied))
        AiOpenCodeStep(3, R.string.ai_opencode_step_allow)
        if (allowed) {
            Text(
                stringResource(R.string.ai_opencode_allowed),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("ai_opencode_allowed"),
            )
        } else {
            OutlinedButton(
                onClick = { askPermission.launch(TERMUX_RUN_COMMAND_PERMISSION) },
                modifier = Modifier.testTag("ai_opencode_allow"),
            ) {
                Text(stringResource(R.string.ai_opencode_allow))
            }
        }
        AiOpenCodeStep(4, R.string.ai_opencode_step_connect)
        Button(
            onClick = controller::testConnection,
            enabled = aiState.connectionTest != AiConnectionTest.Testing,
            modifier = Modifier.testTag("ai_opencode_connect"),
        ) { Text(stringResource(R.string.ai_opencode_connect)) }
        AiOpenCodeStatus(aiState)
        AiOpenCodeHint(stringResource(R.string.ai_opencode_note))
    }
}

@Composable
private fun AiOpenCodeStep(
    number: Int,
    @StringRes text: Int,
) = Text("$number. " + stringResource(text), style = MaterialTheme.typography.bodyMedium)

@Composable
private fun AiOpenCodeHint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Asking OpenCode, why it did not answer, or connected. */
@Composable
private fun AiOpenCodeStatus(aiState: AiAssistantUiState) {
    when (val test = aiState.connectionTest) {
        AiConnectionTest.Testing ->
            Row(Modifier.testTag("ai_opencode_checking"), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ai_opencode_checking))
            }
        is AiConnectionTest.Failed -> {
            Text(
                stringResource(test.failure.kind.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("ai_opencode_failed"),
            )
            test.failure.detail?.let { AiOpenCodeHint(it) }
        }
        AiConnectionTest.Idle, AiConnectionTest.Passed ->
            if (aiState.keyChecked) {
                Text(
                    stringResource(R.string.ai_settings_test_passed),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("ai_opencode_connected"),
                )
            }
    }
}
