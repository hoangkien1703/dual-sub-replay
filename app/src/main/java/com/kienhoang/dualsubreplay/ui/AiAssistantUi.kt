package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AiAssistantController
import com.kienhoang.dualsubreplay.assistant.AiErrorKind
import com.kienhoang.dualsubreplay.assistant.aiSubtitleLineContext
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.ui.theme.DualSubTheme

/** The assistant and the bundled app guide it reads. Null in the F-Droid build, which has no assistant. */
internal class AiAssistantHost(
    val controller: AiAssistantController,
    val guide: () -> String,
)

internal val LocalAiAssistant = staticCompositionLocalOf<AiAssistantHost?> { null }

/** A problem the app reports, shown at the top of the assistant panel. */
internal data class AppProblem(
    val key: String,
    val title: String,
    val message: String?,
    /** English diagnostic text, for example the HTTP status. */
    val detail: String?,
    val actionLabel: String,
    val action: () -> Unit,
    /** Translation still works on the device: amber, not red. */
    val mild: Boolean,
)

/** The amber of the translation fallback notice: translation still works on the device. */
internal val MildProblemTint = Color(0xFFFFC857)

@Composable
internal fun appProblems(
    state: DualSubUiState,
    onTryGoogleAgain: () -> Unit,
    onRetry: () -> Unit,
): List<AppProblem> {
    val problems = mutableListOf<AppProblem>()
    when (val issue = state.translationIssue()) {
        is TranslationIssue.OnDeviceFallback ->
            problems +=
                AppProblem(
                    key = "translation_fallback",
                    title = stringResource(R.string.translation_issue_on_device_title),
                    message = stringResource(R.string.translation_issue_on_device_message),
                    detail = issue.detail,
                    actionLabel = stringResource(R.string.translation_issue_try_google),
                    action = onTryGoogleAgain,
                    mild = true,
                )
        is TranslationIssue.Unavailable ->
            problems +=
                AppProblem(
                    key = "translation_unavailable",
                    title = stringResource(R.string.player_translation_unavailable),
                    message = null,
                    detail = issue.detail,
                    actionLabel = stringResource(R.string.player_retry_translation),
                    action = onRetry,
                    mild = false,
                )
        null -> Unit
    }
    if (state.activeVideoId != null && state.errorMessage != null) {
        problems +=
            AppProblem(
                key = "captions",
                title = stringResource(R.string.ai_problem_captions_title),
                message = null,
                detail = state.errorMessage,
                actionLabel = stringResource(R.string.ai_problem_retry),
                action = onRetry,
                mild = false,
            )
    }
    return problems
}

/**
 * The top-right button: the assistant when it is on, otherwise the translation problem icon exactly
 * as before. A dot on the assistant button means a problem is waiting in the panel.
 */
@Composable
internal fun TopBarAssistantOrIssueButton(
    state: DualSubUiState,
    problems: List<AppProblem>,
    onTryGoogleAgain: () -> Unit,
    onRetry: () -> Unit,
) {
    val host = LocalAiAssistant.current
    if (host == null) {
        TranslationIssueButton(state.translationIssue(), onTryGoogleAgain, onRetry)
        return
    }
    val aiState by host.controller.state.collectAsStateWithLifecycle()
    if (!aiState.settings.enabled) {
        TranslationIssueButton(state.translationIssue(), onTryGoogleAgain, onRetry)
        return
    }
    val tint =
        when {
            problems.isEmpty() -> null
            problems.all { it.mild } -> MildProblemTint
            else -> MaterialTheme.colorScheme.error
        }
    IconButton(onClick = host.controller::openPanel, modifier = Modifier.testTag("ai_assistant_button")) {
        Box {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription =
                    stringResource(if (tint == null) R.string.ai_button else R.string.ai_button_with_problem),
            )
            if (tint != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(10.dp)
                        .background(tint, CircleShape)
                        .testTag("ai_assistant_problem_dot"),
                )
            }
        }
    }
}

@StringRes
internal fun AiErrorKind.messageRes(): Int =
    when (this) {
        AiErrorKind.NO_KEY -> R.string.ai_error_no_key
        AiErrorKind.KEY_UNREADABLE -> R.string.ai_error_key_unreadable
        AiErrorKind.BAD_ADDRESS -> R.string.ai_error_bad_address
        AiErrorKind.INVALID_KEY -> R.string.ai_error_invalid_key
        AiErrorKind.NO_CREDIT -> R.string.ai_error_no_credit
        AiErrorKind.UNKNOWN_MODEL -> R.string.ai_error_unknown_model
        AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
        AiErrorKind.BAD_REQUEST -> R.string.ai_error_bad_request
        AiErrorKind.SERVER -> R.string.ai_error_server
        AiErrorKind.NETWORK -> R.string.ai_error_network
        AiErrorKind.TIMEOUT -> R.string.ai_error_timeout
        AiErrorKind.BAD_REPLY -> R.string.ai_error_bad_reply
    }

/** Opens [url] in the browser; false when no browser can open it. */
internal fun openExternalPage(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/**
 * The panel with this screen's problems, the current subtitle line, and what the app knows right
 * now. It sits next to [DualSubApp] rather than inside it, so it applies the app theme itself;
 * without it, Material's light purple defaults would show.
 */
@Composable
internal fun AiAssistantOverlay(
    host: AiAssistantHost,
    state: DualSubUiState,
    playerMode: PlayerExperienceMode,
    fullscreen: Boolean,
    onTryGoogleAgain: () -> Unit,
    onRetry: () -> Unit,
) = DualSubTheme {
    // The button is not reachable in fullscreen video, so the panel does not stay over it either.
    LaunchedEffect(fullscreen) { if (fullscreen) host.controller.closePanel() }
    val locale = currentInterfaceLocale()
    val explainLine = stringResource(R.string.ai_chip_explain_line)
    val currentLine =
        state.segments.getOrNull(state.currentIndex)?.takeIf { state.activeVideoId != null }?.let { segment ->
            AiQuickQuestion(
                text = explainLine,
                context =
                    aiSubtitleLineContext(
                        original = segment.originalText,
                        originalLanguage = TranslationLanguages.displayName(state.resolvedSourceLanguage ?: state.sourcePreference),
                        translation = segment.translatedText,
                        translationLanguage = TranslationLanguages.displayName(state.targetLanguage),
                    ),
                contextLabel = segment.originalText,
            )
        }
    AiAssistantPanel(
        host = host,
        problems = appProblems(state, onTryGoogleAgain, onRetry),
        snapshot = { aiAppSnapshot(state, playerMode, locale, BuildConfig.VERSION_NAME) },
        currentLineQuestion = currentLine,
    )
}
