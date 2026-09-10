package com.kienhoang.dualsubreplay.ui

import android.os.SystemClock
import android.util.Log
import com.kienhoang.dualsubreplay.BuildConfig

/** Debug logcat only: at most 3,000 entries, five per second per channel; never logs caption text. */
internal object CaptionTimingDiagnostics {
    private var remaining = 3000
    private val lastLog = mutableMapOf<String, Long>()

    fun record(
        channel: String = "sample",
        message: () -> String,
    ) {
        if (!BuildConfig.DEBUG || remaining <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastLog[channel] ?: 0L) < 200) return
        lastLog[channel] = now
        remaining--
        Log.d("CaptionTiming", "nativeMs=$now ${message()}")
    }
}
