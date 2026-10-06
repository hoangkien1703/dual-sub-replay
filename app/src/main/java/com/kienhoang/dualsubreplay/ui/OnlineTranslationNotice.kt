package com.kienhoang.dualsubreplay.ui

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.translation.TranslationEngine

/** Chooses the translation engine from Settings, the failure dialog, or the transcript's "translation unavailable" bar. */
internal class TranslationEngineActions(
    val select: (TranslationEngine) -> Unit,
    val setAutoSwitchToOnDevice: (Boolean) -> Unit = {},
) {
    fun useOnDevice() = select(TranslationEngine.ON_DEVICE)
}

/** What Settings → Translation shows about the online engine. */
internal data class OnlineTranslationSettings(
    /** False in the F-Droid build, which never offers the online engine. */
    val available: Boolean = false,
    val engine: TranslationEngine = TranslationEngine.ON_DEVICE,
    val autoSwitchToOnDevice: Boolean = false,
)

internal val LocalTranslationEngineActions = staticCompositionLocalOf<TranslationEngineActions?> { null }

/** Shown once when Google Translate fails, in every player mode, so the user can switch back right away. */
@Composable
internal fun OnlineTranslationFailedDialog(
    onUseOnDevice: () -> Unit,
    onKeepGoogle: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepGoogle,
        modifier = Modifier.testTag("online_translation_failed"),
        title = { Text(stringResource(R.string.status_google_translate_failed_title)) },
        text = { Text(stringResource(R.string.status_google_translate_failed_message)) },
        confirmButton = {
            TextButton(onClick = onUseOnDevice, modifier = Modifier.testTag("use_on_device_translation")) {
                Text(stringResource(R.string.status_use_on_device_translation))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepGoogle, modifier = Modifier.testTag("keep_google_translate")) {
                Text(stringResource(R.string.status_keep_google_translate))
            }
        },
    )
}

/** A short notice when Google failed and the automatic switch moved this video to on-device translation. */
@Composable
internal fun OnDeviceFallbackNotice(
    show: Boolean,
    onShown: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(show) {
        if (show) {
            Toast.makeText(context, R.string.status_switched_to_on_device, Toast.LENGTH_LONG).show()
            onShown()
        }
    }
}
