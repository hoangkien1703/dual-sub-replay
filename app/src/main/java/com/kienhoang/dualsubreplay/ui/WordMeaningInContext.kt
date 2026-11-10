package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import java.util.Locale

/**
 * The tapped word's meaning as used in its sentence ("go" in "I go to school" is "gedirəm", not only
 * "getmək"), asked from the AI assistant's service and shown above the dictionary meaning (lion737,
 * discussion #81; owner picks 2026-11-10: in-context meaning first with no order setting, and off by
 * default even with a key, since each tap sends the sentence to that service and uses the key).
 */
internal const val MEANING_IN_CONTEXT_PREFERENCE = "meaning_in_context"

/** A longer reply is an explanation, not the few words asked for. */
private const val MAX_CONTEXT_MEANING_CHARS = 80

internal fun readMeaningInContext(context: Context): Boolean =
    context.getSharedPreferences("dual_sub_preferences", 0).getBoolean(MEANING_IN_CONTEXT_PREFERENCE, false)

/** What the word card gets from the AI for the tapped word's sentence. */
internal sealed interface ContextMeaning {
    data class Found(
        val text: String,
    ) : ContextMeaning

    /** The switch is on, but the AI assistant has no key: the card says where to add one. */
    data object NoKey : ContextMeaning

    /** Off, no sentence, or the service did not answer: the card shows only the dictionary meaning. */
    data object None : ContextMeaning
}

/** The sentence [selection]'s word was tapped in: the whole sentence when the row is part of one. */
internal fun contextSentence(selection: LearningWordSelection): String? {
    val segment = selection.segment ?: return null
    val text =
        if (selection.translated) {
            segment.translatedText
        } else {
            segment.sentence?.text?.takeIf { it.isNotBlank() } ?: segment.originalText
        }
    return text?.trim()?.takeIf { it.isNotEmpty() && selection.token.text.isNotBlank() }
}

/** The system and user text of the request for [word]'s meaning in [sentence]. */
internal fun contextMeaningRequest(
    word: String,
    sentence: String,
    wordLanguage: String,
    meaningLanguage: String,
): Pair<String, String> {
    val meaningName = englishLanguageName(meaningLanguage)
    val system =
        "You help a language learner who tapped a word in a video subtitle. Reply with only the meaning " +
            "of the tapped word as it is used in this sentence, written in $meaningName: one to four " +
            "words, in the form that fits the sentence (tense, person, number, case), as a good " +
            "translation of the whole sentence would render it. No quotes, no explanation, no other text."
    val user = "Sentence (${englishLanguageName(wordLanguage)}): $sentence\nTapped word: $word"
    return system to user
}

/** The model's reply as the card shows it, or null when it is not a short meaning. */
internal fun cleanContextMeaning(reply: String?): String? {
    val line =
        reply
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
    val clean =
        line
            .trim('"', '\'', '“', '”', '«', '»', '「', '」', '`', '*', ' ')
            .removeSuffix(".")
            .trim()
    return clean.takeIf { it.isNotEmpty() && it.length <= MAX_CONTEXT_MEANING_CHARS }
}

/** True when the meaning in context says something the dictionary meaning does not. */
internal fun differsFromDictionary(
    inContext: String?,
    dictionary: String,
): Boolean = !inContext.isNullOrBlank() && !inContext.trim().equals(dictionary.trim(), ignoreCase = true)

/**
 * The meaning saved with the word (Practice, Anki's Meaning field, exports): the meaning in the
 * sentence with the dictionary meaning after it, "gedirəm (getmək)".
 */
internal fun combinedMeaning(
    inContext: String?,
    dictionary: String,
): String =
    when {
        !differsFromDictionary(inContext, dictionary) -> dictionary
        dictionary.isBlank() -> inContext.orEmpty().trim()
        else -> "${inContext.orEmpty().trim()} (${dictionary.trim()})"
    }

private fun englishLanguageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code

/** Settings → Word learning → Meaning in this sentence (AI). */
@Composable
internal fun MeaningInContextSetting() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var enabled by remember { mutableStateOf(readMeaningInContext(context)) }
    DisposableEffect(preferences) {
        // "Reset all settings" and the AI assistant change the key while this section is open.
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == MEANING_IN_CONTEXT_PREFERENCE || key == null) enabled = readMeaningInContext(context)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_meaning_in_context_title),
        description = stringResource(R.string.settings_meaning_in_context_description),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            preferences.edit().putBoolean(MEANING_IN_CONTEXT_PREFERENCE, it).apply()
        },
        testTag = "meaning_in_context_switch",
    )
}
