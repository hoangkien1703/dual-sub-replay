package com.kienhoang.dualsubreplay.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Android says sound is about to move to the phone's speaker: wired earphones out or Bluetooth gone. */
internal fun isEarphonesUnplugged(action: String?): Boolean = action == AudioManager.ACTION_AUDIO_BECOMING_NOISY

/**
 * Calls [onUnplugged] each time earphones are unplugged or a Bluetooth headset drops (owner request
 * 2026-11-28), so the video stops instead of playing out loud. Always on, like music apps. The
 * receiver lives as long as the page does, so it also works while the video plays off screen.
 * Returns the call that stops watching.
 */
internal fun watchEarphonesUnplugged(
    context: Context,
    onUnplugged: () -> Unit,
): () -> Unit {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (isEarphonesUnplugged(intent.action)) onUnplugged()
            }
        }
    val appContext = context.applicationContext
    // A system broadcast: the app never needs to receive it from other apps.
    ContextCompat.registerReceiver(
        appContext,
        receiver,
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return {
        try {
            appContext.unregisterReceiver(receiver)
        } catch (error: IllegalArgumentException) {
            Log.w("DualSubPlayback", "The earphone receiver was already gone.", error)
        }
    }
}
