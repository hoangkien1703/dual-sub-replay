package com.kienhoang.dualsubreplay.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.SpanishMorphology
import com.kienhoang.dualsubreplay.data.SpanishPerson
import com.kienhoang.dualsubreplay.data.SpanishVerbDetails

/**
 * How a tapped Spanish verb is conjugated, read from its whole line (Preterite · nosotros), and the
 * pronouns written onto its end (dámelo: me, lo). Nothing for other words and languages.
 */
@Composable
internal fun SpanishVerbForm(selection: LearningWordSelection) {
    if (!LanguageAwareTokenizer.isSpanishCode(selection.wordLanguage)) return
    val segment = selection.segment
    val line = (if (selection.translated) segment?.translatedText else segment?.originalText) ?: selection.token.text
    val revision = tokenizerRevision()
    val details =
        remember(line, selection.token, revision) {
            selectionRangeIn(line, selection.token.text, selection.token.startIndex, selection.token.endIndex)
                ?.let { SpanishMorphology.verbDetails(line, it.first, it.last + 1) }
        } ?: return
    val readings =
        spanishReadingLabels(details).map { (tense, person) ->
            val name = stringResource(SPANISH_TENSE_TEXT.getValue(tense))
            if (person == SpanishPerson.NONE) name else "$name · ${person.pronoun}"
        }
    if (readings.isNotEmpty()) {
        Text(
            stringResource(R.string.practice_spanish_verb_form, readings.joinToString(" / ")),
            modifier = Modifier.testTag("spanish_verb_form"),
        )
    }
    if (details.attachedPronouns.isNotEmpty()) {
        Text(
            stringResource(R.string.practice_spanish_attached_pronouns, details.attachedPronouns.joinToString(", ")),
            modifier = Modifier.testTag("spanish_attached_pronouns"),
        )
    }
}

/** The readings of the likeliest verb (its first reading's), at most [MAX_READINGS] of them. */
internal fun spanishReadingLabels(details: SpanishVerbDetails) =
    details.readings
        .filter { it.lemma == details.readings.first().lemma }
        .map { it.tense to it.person }
        .distinct()
        .take(MAX_READINGS)

private const val MAX_READINGS = 3
