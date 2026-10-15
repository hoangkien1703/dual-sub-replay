package com.kienhoang.dualsubreplay.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Termux's package, its permission for other apps, and its `RUN_COMMAND` service (Termux 0.118+ for stdin). */
internal const val TERMUX_PACKAGE = "com.termux"
internal const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
private const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
private const val RESULT_BUNDLE = "result"
private const val RESULT_STDOUT = "stdout"
private const val RESULT_STDERR = "stderr"
private const val RESULT_EXIT_CODE = "exitCode"
private const val RESULT_ERROR_MESSAGE = "errmsg"

/** The name bash gives the script (`$0`), shown in Termux's own logs. */
private const val SCRIPT_NAME = "dualsub-replay"

internal fun termuxInstalled(context: Context): Boolean =
    try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

internal fun termuxCommandsAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Runs a script in Termux in the background through its `RUN_COMMAND` intent and waits for the
 * result, which Termux sends back through a one-time [PendingIntent] to a receiver only this app
 * can reach. The script and its arguments go as an argument list, never through a shell line.
 */
internal class TermuxCommandRunner(
    context: Context,
) : TermuxRunner {
    private val context = context.applicationContext
    private val requestCodes = AtomicInteger()

    override suspend fun run(
        script: String,
        args: List<String>,
        stdin: String?,
        timeoutMs: Long,
    ): TermuxResult {
        if (!termuxInstalled(context)) throw AiChatException(AiErrorKind.TERMUX_MISSING)
        if (!termuxCommandsAllowed(context)) throw AiChatException(AiErrorKind.TERMUX_PERMISSION)
        return try {
            withTimeout(timeoutMs) { runAndWait(script, args, stdin) }
        } catch (_: TimeoutCancellationException) {
            throw AiChatException(AiErrorKind.TIMEOUT, "Termux did not answer within ${timeoutMs / 1000} s.")
        }
    }

    private suspend fun runAndWait(
        script: String,
        args: List<String>,
        stdin: String?,
    ): TermuxResult =
        suspendCancellableCoroutine { continuation ->
            val action = "${context.packageName}.TERMUX_RESULT.${UUID.randomUUID()}"
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        receiverContext: Context,
                        intent: Intent,
                    ) {
                        unregister(this)
                        if (continuation.isActive) continuation.resume(termuxResult(intent.getBundleExtra(RESULT_BUNDLE)))
                    }
                }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { unregister(receiver) }
            // Termux fills in the result, so the intent must be mutable; naming the package keeps it explicit.
            val result =
                PendingIntent.getBroadcast(
                    context,
                    requestCodes.incrementAndGet(),
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
                )
            val command =
                Intent(TERMUX_ACTION_RUN_COMMAND)
                    .setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                    .putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
                    // A login shell, so `opencode` is found wherever the user's profile puts it.
                    .putExtra(EXTRA_ARGUMENTS, (listOf("-lc", script, SCRIPT_NAME) + args).toTypedArray())
                    .putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                    .putExtra(EXTRA_BACKGROUND, true)
                    .putExtra(EXTRA_COMMAND_LABEL, "DualSub Replay: OpenCode")
                    .putExtra(EXTRA_PENDING_INTENT, result)
                    .apply { stdin?.let { putExtra(EXTRA_STDIN, it) } }
            try {
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {
                    context.startForegroundService(command)
                } else {
                    context.startService(command)
                }
            } catch (error: SecurityException) {
                unregister(receiver)
                continuation.resumeWithException(AiChatException(AiErrorKind.TERMUX_PERMISSION, error.message, error))
            } catch (error: IllegalStateException) {
                unregister(receiver)
                continuation.resumeWithException(AiChatException(AiErrorKind.TERMUX_FAILED, error.message, error))
            }
        }

    private fun unregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered: the result and a cancellation can both arrive.
        }
    }
}

/** Termux's result bundle; an error message without an exit code means the command never ran. */
private fun termuxResult(bundle: Bundle?): TermuxResult {
    if (bundle == null) return TermuxResult("", "", null, "Termux sent no result.")
    val error = bundle.getString(RESULT_ERROR_MESSAGE)?.takeIf { it.isNotBlank() }
    val exitCode = if (bundle.containsKey(RESULT_EXIT_CODE)) bundle.getInt(RESULT_EXIT_CODE) else null
    return TermuxResult(
        stdout = bundle.getString(RESULT_STDOUT).orEmpty(),
        stderr = bundle.getString(RESULT_STDERR).orEmpty(),
        exitCode = exitCode.takeIf { error == null || !bundle.getString(RESULT_STDOUT).isNullOrEmpty() },
        error = error,
    )
}
