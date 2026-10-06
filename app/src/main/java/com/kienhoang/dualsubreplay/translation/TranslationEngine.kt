package com.kienhoang.dualsubreplay.translation

internal const val TRANSLATION_ENGINE_PREFERENCE = "translation_engine"

/** Settings → Translation: when Google fails, quietly use on-device translation for that video. Off by default. */
internal const val AUTO_SWITCH_TO_ON_DEVICE_PREFERENCE = "auto_switch_to_on_device"

/** Which engine translates subtitles. Google online is the default where the build offers it. */
enum class TranslationEngine(
    val storageValue: String,
) {
    ON_DEVICE("on_device"),
    GOOGLE_WEB("google_web"),
}

/** Google online in builds that offer it; the F-Droid build only translates on the device. */
internal fun defaultTranslationEngine(onlineAvailable: Boolean): TranslationEngine =
    if (onlineAvailable) TranslationEngine.GOOGLE_WEB else TranslationEngine.ON_DEVICE

/** The saved engine, or the build's default when nothing valid is saved. Never Google when this build cannot translate online. */
internal fun storedTranslationEngine(
    raw: String?,
    onlineAvailable: Boolean,
): TranslationEngine {
    val stored = TranslationEngine.entries.firstOrNull { it.storageValue == raw } ?: defaultTranslationEngine(onlineAvailable)
    return if (stored == TranslationEngine.GOOGLE_WEB && !onlineAvailable) TranslationEngine.ON_DEVICE else stored
}
