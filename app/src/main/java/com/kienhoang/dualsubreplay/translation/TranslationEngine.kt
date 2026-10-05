package com.kienhoang.dualsubreplay.translation

internal const val TRANSLATION_ENGINE_PREFERENCE = "translation_engine"

/** Which engine translates subtitles. On-device is the default; Google is an opt-in online service. */
enum class TranslationEngine(
    val storageValue: String,
) {
    ON_DEVICE("on_device"),
    GOOGLE_WEB("google_web"),
}

/** The saved engine, or on-device when nothing is saved or this build cannot translate online. */
internal fun storedTranslationEngine(
    raw: String?,
    onlineAvailable: Boolean,
): TranslationEngine {
    val stored = TranslationEngine.entries.firstOrNull { it.storageValue == raw } ?: TranslationEngine.ON_DEVICE
    return if (stored == TranslationEngine.GOOGLE_WEB && !onlineAvailable) TranslationEngine.ON_DEVICE else stored
}
