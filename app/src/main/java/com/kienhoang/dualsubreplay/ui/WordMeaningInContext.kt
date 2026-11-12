package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import java.util.Locale

/**
 * The tapped word's meaning as used in its sentence ("go" in "I go to school" is "gedirəm", not only
 * "getmək"), asked from the AI assistant's service and shown above the dictionary meaning (lion737,
 * discussion #81; owner picks 2026-11-10: off by default even with a key, since each tap sends the
 * sentence to that service and uses the key; 2026-11-11: a Show first choice puts either meaning first).
 */
internal const val MEANING_IN_CONTEXT_PREFERENCE = "meaning_in_context"

/**
 * Which meaning the word card shows big and saves first: "sentence_first" (default) or
 * "dictionary_first" (lion737 asked three times, owner pick 2026-11-11).
 */
internal const val MEANING_ORDER_PREFERENCE = "meaning_order"
internal const val MEANING_ORDER_SENTENCE_FIRST = "sentence_first"
internal const val MEANING_ORDER_DICTIONARY_FIRST = "dictionary_first"
internal val MEANING_ORDERS = listOf(MEANING_ORDER_SENTENCE_FIRST, MEANING_ORDER_DICTIONARY_FIRST)

internal fun storedMeaningOrder(value: String?): String = value?.takeIf { it in MEANING_ORDERS } ?: MEANING_ORDER_SENTENCE_FIRST

/** True when the meaning in the sentence comes first (the default). */
internal fun readMeaningInContextFirst(context: Context): Boolean =
    storedMeaningOrder(context.getSharedPreferences("dual_sub_preferences", 0).getString(MEANING_ORDER_PREFERENCE, null)) ==
        MEANING_ORDER_SENTENCE_FIRST

/** A longer reply is an explanation, not the few words asked for. */
private const val MAX_CONTEXT_MEANING_CHARS = 80

/** A selected phrase can take a longer piece of the translation. */
private const val MAX_PHRASE_MEANING_CHARS = 240

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

/**
 * What the AI reads to find the selection's meaning: the sentence it was selected in, that
 * sentence's translation as the line under it shows it (null until it is translated), and the lines
 * just before and after. With the translation the answer matches the line the learner reads
 * ("spitting" → "çiskin" where the translation says "çiskin", not the dictionary's "tüpürmək";
 * lion737, discussion #81, 2026-11-11).
 */
internal data class MeaningContext(
    val sentence: String,
    val translation: String? = null,
    val before: String? = null,
    val after: String? = null,
)

/** The [MeaningContext] of [selection], taking the translation and the lines around it from [segments]. */
internal fun meaningContext(
    selection: LearningWordSelection,
    segments: List<SubtitleSegment> = emptyList(),
): MeaningContext? {
    val segment = selection.segment ?: return null
    if (selection.token.text.isBlank()) return null
    val index = segments.indexOfFirst { it.id == segment.id }
    val rows = if (index >= 0) sentenceRows(segments, index) else IntRange.EMPTY
    val rowSegments = if (index >= 0) segments.subList(rows.first, rows.last + 1) else listOf(segment)
    val original =
        segment.sentence
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: segment.originalText.trim()
    // A sentence split over rows is only whole when every row of it is in the transcript window.
    val wholeSentence = segment.sentence == null || segment.sentence.cuts.size + 1 == rowSegments.size
    val translation =
        rowSegments
            .map { it.translatedText?.trim().orEmpty() }
            .takeIf { wholeSentence && it.none(String::isEmpty) }
            ?.joinToString(" ")

    fun lineText(row: SubtitleSegment?): String? =
        (if (selection.translated) row?.translatedText else row?.originalText)?.trim()?.takeIf { it.isNotEmpty() }
    val before = if (index >= 0) lineText(segments.getOrNull(rows.first - 1)) else null
    val after = if (index >= 0) lineText(segments.getOrNull(rows.last + 1)) else null
    return if (selection.translated) {
        // A word tapped in the translation line is read in the translated sentence.
        val sentence = translation ?: segment.translatedText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        MeaningContext(sentence, original.takeIf { it.isNotEmpty() }, before, after)
    } else {
        original.takeIf { it.isNotEmpty() }?.let { MeaningContext(it, translation, before, after) }
    }
}

/** The rows of [segments] around [index] that show parts of the same sentence, in order. */
private fun sentenceRows(
    segments: List<SubtitleSegment>,
    index: Int,
): IntRange {
    val sentence = segments[index].sentence ?: return index..index

    fun partIndex(row: SubtitleSegment?): Int? = row?.sentence?.takeIf { it.text == sentence.text && it.cuts == sentence.cuts }?.index
    var first = index
    while (partIndex(segments.getOrNull(first - 1)) == partIndex(segments[first])!! - 1) first--
    var last = index
    while (partIndex(segments.getOrNull(last + 1)) == partIndex(segments[last])!! + 1) last++
    return first..last
}

/** The system and user text of the request for [word]'s meaning in [context]; [phrase] for several selected words. */
internal fun contextMeaningRequest(
    word: String,
    context: MeaningContext,
    wordLanguage: String,
    meaningLanguage: String,
    phrase: Boolean = false,
): Pair<String, String> {
    val meaningName = englishLanguageName(meaningLanguage)
    val selected = if (phrase) "selected words" else "tapped word"
    // "One to four words" cut a selected phrase to its first words (lion737, 2026-11-11).
    val length = if (phrase) "the whole selection, as many words as it needs" else "one to four words"
    val system =
        "You help a language learner who ${if (phrase) "selected words" else "tapped a word"} in a video subtitle. " +
            "Reply with only the meaning of the $selected as used in this sentence, written in $meaningName: " +
            "$length, in the form that fits the sentence (tense, person, number, case), as a good translation " +
            "of the whole sentence would render it. When a translation of the sentence is given and it renders " +
            "the $selected, reply with exactly the words of that translation that render them, as written there; " +
            "if it leaves them out or gets them wrong, give your own. No quotes, no explanation, no other text."
    val user =
        buildString {
            context.before?.let { append("Line before: ").append(it).append('\n') }
            append("Sentence (").append(englishLanguageName(wordLanguage)).append("): ").append(context.sentence)
            context.after?.let { append("\nLine after: ").append(it) }
            context.translation?.let { append("\nTranslation of the sentence (").append(meaningName).append("): ").append(it) }
            append(if (phrase) "\nSelected words: " else "\nTapped word: ").append(word)
        }
    return system to user
}

/** The model's reply as the card shows it, or null when it is not a short meaning. */
internal fun cleanContextMeaning(
    reply: String?,
    phrase: Boolean = false,
): String? {
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
    val limit = if (phrase) MAX_PHRASE_MEANING_CHARS else MAX_CONTEXT_MEANING_CHARS
    return clean.takeIf { it.isNotEmpty() && it.length <= limit }
}

/** True when the meaning in context says something the dictionary meaning does not. */
internal fun differsFromDictionary(
    inContext: String?,
    dictionary: String,
): Boolean = !inContext.isNullOrBlank() && !inContext.trim().equals(dictionary.trim(), ignoreCase = true)

/**
 * The meaning saved with the word (Practice, Anki's Meaning field, exports): the meaning in the
 * sentence with the dictionary meaning after it, "gedirəm (getmək)", or the other way round when
 * the dictionary meaning comes first, "getmək (gedirəm)".
 */
internal fun combinedMeaning(
    inContext: String?,
    dictionary: String,
    contextFirst: Boolean = true,
): String =
    when {
        !differsFromDictionary(inContext, dictionary) -> dictionary
        dictionary.isBlank() -> inContext.orEmpty().trim()
        contextFirst -> "${inContext.orEmpty().trim()} (${dictionary.trim()})"
        else -> "${dictionary.trim()} (${inContext.orEmpty().trim()})"
    }

private fun englishLanguageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code

/** Settings → Word learning → Meaning in this sentence (AI). */
@Composable
internal fun MeaningInContextSetting() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var enabled by remember { mutableStateOf(readMeaningInContext(context)) }
    var contextFirst by remember { mutableStateOf(readMeaningInContextFirst(context)) }
    DisposableEffect(preferences) {
        // "Reset all settings" and the AI assistant change the key while this section is open.
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == MEANING_IN_CONTEXT_PREFERENCE || key == null) enabled = readMeaningInContext(context)
                if (key == MEANING_ORDER_PREFERENCE || key == null) contextFirst = readMeaningInContextFirst(context)
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
    if (enabled) {
        MeaningOrderChoice(contextFirst) { first ->
            contextFirst = first
            val order = if (first) MEANING_ORDER_SENTENCE_FIRST else MEANING_ORDER_DICTIONARY_FIRST
            preferences.edit().putString(MEANING_ORDER_PREFERENCE, order).apply()
        }
    }
}

/** Show first: the meaning in the sentence or the dictionary meaning, only while Meaning in this sentence is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeaningOrderChoice(
    contextFirst: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("meaning_order")) {
        Text(stringResource(R.string.settings_meaning_order_title), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                true to R.string.settings_meaning_order_sentence,
                false to R.string.settings_meaning_order_dictionary,
            ).forEach { (first, label) ->
                FilterChip(
                    selected = contextFirst == first,
                    onClick = { onChange(first) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag(if (first) "meaning_order_sentence" else "meaning_order_dictionary"),
                )
            }
        }
    }
}
