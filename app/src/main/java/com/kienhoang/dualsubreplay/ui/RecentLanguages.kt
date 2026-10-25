package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.translation.TranslationLanguages

/** The last "Translate to" picks, newest first, comma-separated. Reset all settings clears it. */
const val RECENT_TARGET_LANGUAGES_PREFERENCE = "recent_target_languages"

/** The picker's Recent group shows at most this many languages. */
internal const val MAX_RECENT_LANGUAGES = 3

/** Stored recent languages, newest first, without unknown or repeated codes. */
internal fun parseRecentLanguages(stored: String?): List<String> =
    stored
        .orEmpty()
        .split(',')
        .map { TranslationLanguages.normalize(it.trim()) }
        .filter { it.isNotEmpty() && TranslationLanguages.isSupported(it) }
        .distinct()
        .take(MAX_RECENT_LANGUAGES)

internal fun encodeRecentLanguages(codes: List<String>): String = codes.joinToString(",")

/**
 * Puts [picked] first. The language it replaces ([previous]) is kept too, so the first switch
 * after an update already shows both languages.
 */
internal fun rememberRecentLanguage(
    recent: List<String>,
    previous: String,
    picked: String,
): List<String> =
    (listOf(picked, previous) + recent)
        .map(TranslationLanguages::normalize)
        .filter(TranslationLanguages::isSupported)
        .distinct()
        .take(MAX_RECENT_LANGUAGES)

/**
 * The Recent group: the current language first, then the other recent picks. It is left out
 * (empty) until there is a second language to switch to.
 */
internal fun recentLanguagesToShow(
    recent: List<String>,
    selected: String,
): List<String> {
    val shown =
        (listOf(selected) + recent)
            .map(TranslationLanguages::normalize)
            .filter(TranslationLanguages::isSupported)
            .distinct()
            .take(MAX_RECENT_LANGUAGES)
    return if (shown.size >= 2) shown else emptyList()
}
