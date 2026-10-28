package com.kienhoang.dualsubreplay.translation

internal const val TRANSLATION_ENGINE_PREFERENCE = "translation_engine"

/** Settings → Translation → Translate with AI; off by default (owner request 2026-10-28). */
internal const val AI_TRANSLATION_PREFERENCE = "ai_subtitle_translation"

/** With Translate with AI, rows wait for the AI instead of showing Google's lines first. */
internal const val AI_TRANSLATION_ONLY_PREFERENCE = "ai_subtitle_translation_only"

/** Which engine translates subtitles. Google online is the default. */
enum class TranslationEngine(
    val storageValue: String,
) {
    ON_DEVICE("on_device"),
    GOOGLE_WEB("google_web"),
}

internal val DEFAULT_TRANSLATION_ENGINE = TranslationEngine.GOOGLE_WEB

/** The saved engine, or Google when nothing valid is saved. */
internal fun storedTranslationEngine(raw: String?): TranslationEngine =
    TranslationEngine.entries.firstOrNull { it.storageValue == raw } ?: DEFAULT_TRANSLATION_ENGINE

/**
 * Settings → Translation → Translate subtitles with: one choice over the saved engine and the AI
 * settings (owner request 2026-10-28, so the section is not a row of switches that depend on each
 * other). Every online choice still falls back to Google, then the device, when it fails.
 */
enum class SubtitleTranslator(
    val storageValue: String,
) {
    GOOGLE("google"),

    /** Google's lines show first; each sentence switches to the AI's as it arrives. */
    AI_WITH_GOOGLE("ai_with_google"),

    /** Rows wait for the AI; Google translates only what the AI fails to. */
    AI_ONLY("ai_only"),
    ON_DEVICE("on_device"),
    ;

    val engine: TranslationEngine
        get() = if (this == ON_DEVICE) TranslationEngine.ON_DEVICE else TranslationEngine.GOOGLE_WEB

    val usesAi: Boolean
        get() = this == AI_WITH_GOOGLE || this == AI_ONLY
}

/** The choice the saved engine and AI settings make up. */
internal fun subtitleTranslator(
    engine: TranslationEngine,
    ai: Boolean,
    aiOnly: Boolean,
): SubtitleTranslator =
    when {
        engine == TranslationEngine.ON_DEVICE -> SubtitleTranslator.ON_DEVICE
        !ai -> SubtitleTranslator.GOOGLE
        aiOnly -> SubtitleTranslator.AI_ONLY
        else -> SubtitleTranslator.AI_WITH_GOOGLE
    }
