package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.TranslationEngine

/** Chooses the translation engine from Settings → Translation, or tries Google again from the top-right icon. */
internal class TranslationEngineActions(
    val select: (TranslationEngine) -> Unit,
    val tryGoogleAgain: () -> Unit = {},
)

/** What Settings → Translation shows about the online engine. */
internal data class OnlineTranslationSettings(
    /** False in the F-Droid build, which never offers the online engine. */
    val available: Boolean = false,
    val engine: TranslationEngine = TranslationEngine.ON_DEVICE,
)

internal val LocalTranslationEngineActions = staticCompositionLocalOf<TranslationEngineActions?> { null }

/** A translation problem the top-right icon reports; [detail] is the English diagnostic text, if any. */
internal sealed interface TranslationIssue {
    val detail: String?

    /** Google failed, so this video translates on the device until Google answers again. */
    data class OnDeviceFallback(
        override val detail: String?,
    ) : TranslationIssue

    /** Translation stopped; the original captions keep playing. */
    data class Unavailable(
        override val detail: String,
    ) : TranslationIssue
}

/** The problem to show for the current video, or null when translation works. */
internal fun DualSubUiState.translationIssue(): TranslationIssue? =
    when {
        activeVideoId == null -> null
        translationError != null -> TranslationIssue.Unavailable(translationError)
        onDeviceFallback -> TranslationIssue.OnDeviceFallback(onlineTranslationFailureDetail)
        else -> null
    }

private val FallbackTint = Color(0xFFFFC857)
private val UnavailableTint = Color(0xFFFFB4AB)

/**
 * A small icon at the top right of the app while translation has a problem. Tapping it shows what
 * happened and one action: try Google again, or retry translation.
 */
@Composable
internal fun TranslationIssueButton(
    issue: TranslationIssue?,
    onTryGoogleAgain: () -> Unit,
    onRetryTranslation: () -> Unit,
) {
    if (issue == null) return
    var expanded by remember { mutableStateOf(false) }
    val fallback = issue is TranslationIssue.OnDeviceFallback
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("translation_issue_button")) {
            Icon(
                if (fallback) Icons.Default.CloudOff else Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.translation_issue_button),
                tint = if (fallback) FallbackTint else UnavailableTint,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationIssueDetails(
                issue = issue,
                onAction = {
                    expanded = false
                    if (fallback) onTryGoogleAgain() else onRetryTranslation()
                },
            )
        }
    }
}

@Composable
internal fun TranslationIssueDetails(
    issue: TranslationIssue,
    onAction: () -> Unit,
) {
    val fallback = issue is TranslationIssue.OnDeviceFallback
    Column(
        Modifier.widthIn(max = 300.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp).testTag("translation_issue_details"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(if (fallback) R.string.translation_issue_on_device_title else R.string.player_translation_unavailable),
            style = MaterialTheme.typography.titleSmall,
        )
        if (fallback) {
            Text(stringResource(R.string.translation_issue_on_device_message), style = MaterialTheme.typography.bodyMedium)
        }
        // Diagnostic text (for example the HTTP status) so a report says why it failed.
        issue.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag("translation_issue_detail"),
            )
        }
        TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End).testTag("translation_issue_action")) {
            Text(stringResource(if (fallback) R.string.translation_issue_try_google else R.string.player_retry_translation))
        }
    }
}
