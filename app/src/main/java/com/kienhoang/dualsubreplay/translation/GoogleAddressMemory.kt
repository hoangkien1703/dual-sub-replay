package com.kienhoang.dualsubreplay.translation

import android.content.Context

/*
 * The Google address that last worked is tried first, and stays first until it fails. It is kept
 * across app restarts (owner request 2026-10-19), so a network that blocks the first address does
 * not wait on it again after every restart.
 */

private const val GOOGLE_ADDRESS_PREFERENCES = "google_addresses"
internal const val GOOGLE_TRANSLATE_ADDRESS_KEY = "translate"
internal const val GOOGLE_SPEECH_ADDRESS_KEY = "speech"

/** Keeps the name of the Google address that last worked. */
interface GoogleAddressMemory {
    fun read(): String?

    fun write(name: String)
}

/** A [GoogleAddressMemory] in the app's own preferences file, under [key]. */
internal fun Context.googleAddressMemory(key: String): GoogleAddressMemory {
    val preferences = applicationContext.getSharedPreferences(GOOGLE_ADDRESS_PREFERENCES, Context.MODE_PRIVATE)
    return object : GoogleAddressMemory {
        override fun read(): String? = preferences.getString(key, null)

        override fun write(name: String) {
            preferences.edit().putString(key, name).apply()
        }
    }
}

/** The index in [entries] of the address [memory] holds, or 0 (the first address) when it holds none we know. */
internal fun <E : Enum<E>> rememberedAddress(
    memory: GoogleAddressMemory?,
    entries: List<E>,
): Int = entries.indexOfFirst { it.name == memory?.read() }.coerceAtLeast(0)
