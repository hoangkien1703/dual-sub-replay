package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

private const val TRANSLATE_APP_PACKAGE = "com.google.android.apps.translate"

internal enum class TranslateSendResult { SENT, NOT_INSTALLED, CANNOT_RECEIVE }

/** Plain text shared straight to the Google Translate app only; null when there is nothing to send. */
internal fun googleTranslateSendIntent(text: String): Intent? =
    text.takeIf { it.isNotBlank() }?.let {
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, it)
            .setPackage(TRANSLATE_APP_PACKAGE)
            // CLEAR_TASK restarts an already open Translate app, which otherwise ignores the new text.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

internal fun translateSendResult(
    installed: Boolean,
    hasReceiver: Boolean,
): TranslateSendResult =
    when {
        !installed -> TranslateSendResult.NOT_INSTALLED
        !hasReceiver -> TranslateSendResult.CANNOT_RECEIVE
        else -> TranslateSendResult.SENT
    }

/**
 * Hands [text] to the Google Translate app. It never opens a web page; the result says why not.
 * SENT means the app was started, not that its input was filled: that needs a device check.
 */
internal fun sendToGoogleTranslate(
    context: Context,
    text: String,
): TranslateSendResult {
    val intent = googleTranslateSendIntent(text) ?: return TranslateSendResult.CANNOT_RECEIVE
    val packages = context.packageManager
    val installed =
        try {
            packages.getApplicationInfo(TRANSLATE_APP_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    val receiver = installed && packages.queryIntentActivities(intent, 0).isNotEmpty()
    val result = translateSendResult(installed, receiver)
    if (result != TranslateSendResult.SENT) return result
    return try {
        context.startActivity(intent)
        TranslateSendResult.SENT
    } catch (_: ActivityNotFoundException) {
        TranslateSendResult.CANNOT_RECEIVE
    } catch (_: SecurityException) {
        TranslateSendResult.CANNOT_RECEIVE
    }
}
