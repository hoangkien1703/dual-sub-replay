package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.PROBLEM_REPORT_EMAIL
import com.kienhoang.dualsubreplay.data.aiAnswerReportBody
import com.kienhoang.dualsubreplay.data.aiAnswerReportEmailUri
import com.kienhoang.dualsubreplay.data.aiAnswerReportSubject

/**
 * Why the person reports an AI answer. [english] goes into the email, which is for the project;
 * the dialog shows [label] in the app's language.
 */
internal enum class AiAnswerReportReason(
    val english: String,
    @StringRes val label: Int,
) {
    OFFENSIVE("Offensive or harmful", R.string.ai_report_offensive),
    WRONG("Wrong or misleading", R.string.ai_report_wrong),
    OTHER("Something else", R.string.ai_report_other),
}

/**
 * Report under an AI answer (Google Play's rules for apps that make AI content, owner pick 2026-11-21,
 * docs/specs/2026-11-21-google-play-build.md). The app has no server, so the report goes by email to
 * the project's inbox: the person picks a reason and their mail app opens with the answer filled in.
 * Nothing is sent until they send it.
 */
@Composable
internal fun AiReportAnswerButton(answer: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(
        onClick = { open = true },
        modifier = Modifier.testTag("ai_report_answer"),
    ) {
        Icon(
            Icons.Outlined.Flag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.ai_report_answer),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) AiReportAnswerDialog(answer, onClose = { open = false })
}

@Composable
private fun AiReportAnswerDialog(
    answer: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var reason by remember { mutableStateOf(AiAnswerReportReason.OFFENSIVE) }
    var noEmailApp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { DialogTitle(stringResource(R.string.ai_report_title), onClose = onClose) },
        text = {
            Column {
                Text(stringResource(R.string.ai_report_intro), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.selectableGroup()) {
                    AiAnswerReportReason.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = option == reason, role = Role.RadioButton, onClick = { reason = option })
                                .padding(vertical = 2.dp)
                                .testTag("ai_report_reason_${option.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == reason, onClick = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(option.label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (noEmailApp) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ai_report_no_email, PROBLEM_REPORT_EMAIL),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ai_report_no_email"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (sendAiAnswerReport(context, reason, answer)) onClose() else noEmailApp = true
                },
                modifier = Modifier.testTag("ai_report_send"),
            ) { Text(stringResource(R.string.report_problem_email)) }
        },
    )
}

/** The phone's mail app with the project's address, subject and report filled in; false when there is none. */
private fun sendAiAnswerReport(
    context: Context,
    reason: AiAnswerReportReason,
    answer: String,
): Boolean {
    val body = aiAnswerReportBody(reason.english, answer, BuildConfig.VERSION_NAME)
    val email =
        Intent(Intent.ACTION_SENDTO, aiAnswerReportEmailUri(body, BuildConfig.VERSION_NAME).toUri())
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(PROBLEM_REPORT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, aiAnswerReportSubject(BuildConfig.VERSION_NAME))
            .putExtra(Intent.EXTRA_TEXT, body)
    return try {
        context.startActivity(email)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
