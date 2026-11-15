package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.GermanCase
import com.kienhoang.dualsubreplay.data.GermanCell
import com.kienhoang.dualsubreplay.data.GermanDegree
import com.kienhoang.dualsubreplay.data.GermanFeature
import com.kienhoang.dualsubreplay.data.GermanGender
import com.kienhoang.dualsubreplay.data.GermanMorphology
import com.kienhoang.dualsubreplay.data.GermanNoun
import com.kienhoang.dualsubreplay.data.GermanTense
import com.kienhoang.dualsubreplay.data.GermanWordDetails
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearningWordSelection

/**
 * What the word card says about a tapped German word, read from its whole line: its dictionary form,
 * the tense, mood and person of a verb, the gender and plural of a noun, the degree of an adjective, and
 * the case the word has in its noun phrase (several when the sentence does not decide). Nothing for
 * other languages, and nothing while the German data loads.
 */
@Composable
internal fun GermanWordForm(selection: LearningWordSelection) {
    if (!LanguageAwareTokenizer.isGermanCode(selection.wordLanguage)) return
    val segment = selection.segment
    val line = (if (selection.translated) segment?.translatedText else segment?.originalText) ?: selection.token.text
    val revision = tokenizerRevision()
    val range =
        remember(
            line,
            selection.token,
        ) { selectionRangeIn(line, selection.token.text, selection.token.startIndex, selection.token.endIndex) }
    val details = remember(line, range, revision) { range?.let { GermanMorphology.wordDetails(line, it.first, it.last + 1) } }
    // The word's job in its sentence and the verb tables do not depend on a grammar point being found, so they stand on their own.
    val role = remember(line, range, revision) { range?.let { GermanMorphology.wordRole(line, it.first, it.last + 1) } }
    val conjugation = remember(line, range, revision) { range?.let { GermanMorphology.conjugation(line, it.first, it.last + 1) } }
    if (details == null && role == null && conjugation == null) return
    details?.let { GermanFormLines(it) }
    role?.let { GermanRoleSection(it, selection.token.text) }
    conjugation?.let { GermanConjugationSection(it) }
}

@Composable
private fun GermanFormLines(details: GermanWordDetails) {
    details.verb?.let { verb ->
        Text(stringResource(R.string.german_word_lemma, verb.infinitive), modifier = Modifier.testTag("german_lemma"))
        val readings = details.cells.map { germanCellLabel(it) }
        if (readings.isNotEmpty()) Text(readings.joinToString(" / "), modifier = Modifier.testTag("german_verb_form"))
        // Only what the data says: a verb with no auxiliary listed shows none, and one that takes both shows both.
        val auxiliaries =
            listOfNotNull(if ('h' in verb.auxiliaries) "haben" else null, if ('s' in verb.auxiliaries) "sein" else null).joinToString(" / ")
        val notes =
            listOfNotNull(
                if (verb.modal) stringResource(R.string.german_word_modal) else null,
                if (verb.separable) stringResource(R.string.german_word_separable, verb.prefix) else null,
                if (verb.reflexive) stringResource(R.string.german_word_reflexive) else null,
                if (auxiliaries.isNotEmpty()) stringResource(R.string.german_word_auxiliary, auxiliaries) else null,
            )
        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), modifier = Modifier.testTag("german_verb_notes"))
    }
    details.noun?.let { noun -> GermanNounLines(noun) }
    details.adjective?.let { adjective ->
        val degree =
            when (details.degree) {
                GermanDegree.COMPARATIVE -> " · " + stringResource(R.string.german_degree_comparative)
                GermanDegree.SUPERLATIVE -> " · " + stringResource(R.string.german_degree_superlative)
                else -> ""
            }
        Text(stringResource(R.string.german_word_adjective, adjective.lemma) + degree, modifier = Modifier.testTag("german_adjective"))
    }
    if (details.features.isNotEmpty()) {
        Text(details.features.map { featureLabel(it) }.joinToString(" / "), modifier = Modifier.testTag("german_case"))
    }
}

@Composable
private fun GermanNounLines(noun: GermanNoun) {
    val genders = noun.genders.map { stringResource(genderName(it)) }.joinToString(" / ")
    Text(stringResource(R.string.german_word_noun, genders), modifier = Modifier.testTag("german_noun"))
    val singular = noun.genders.map { stringResource(articleOf(it)) + " " + noun.lemma }.joinToString(" / ")
    val plural = noun.plurals.firstOrNull { it != noun.lemma } ?: noun.plurals.firstOrNull()
    val line =
        if (plural ==
            null
        ) {
            singular
        } else {
            singular + " · " +
                stringResource(R.string.german_word_plural, stringResource(R.string.german_article_feminine) + " " + plural)
        }
    Text(line, modifier = Modifier.testTag("german_noun_forms"))
    val genitive = noun.genitives.firstOrNull { it != noun.lemma }
    if (genitive !=
        null
    ) {
        Text(stringResource(R.string.german_word_noun_genitive, genitive), modifier = Modifier.testTag("german_noun_genitive"))
    }
}

@Composable
private fun germanCellLabel(cell: GermanCell): String {
    val name = stringResource(tenseName(cell.tense))
    if (cell.person == 0) return name
    val number = stringResource(if (cell.plural) R.string.german_word_plural_number else R.string.german_word_singular)
    return name + " · " + stringResource(R.string.german_word_person, cell.person, number)
}

@Composable
private fun featureLabel(feature: GermanFeature): String {
    val case = stringResource(caseName(feature.case))
    val gender = feature.gender
    return if (feature.plural || gender == null) {
        stringResource(R.string.german_feature_plural, case)
    } else {
        stringResource(R.string.german_feature_singular, case, stringResource(genderLetter(gender)))
    }
}

@StringRes
internal fun caseName(case: GermanCase): Int =
    when (case) {
        GermanCase.NOMINATIVE -> R.string.german_case_nominative
        GermanCase.GENITIVE -> R.string.german_case_genitive
        GermanCase.DATIVE -> R.string.german_case_dative
        GermanCase.ACCUSATIVE -> R.string.german_case_accusative
    }

@StringRes
private fun tenseName(tense: GermanTense): Int =
    when (tense) {
        GermanTense.PRESENT -> R.string.german_tense_present
        GermanTense.PRETERITE -> R.string.german_tense_preterite
        GermanTense.SUBJUNCTIVE_I -> R.string.german_tense_subjunctive_i
        GermanTense.SUBJUNCTIVE_II -> R.string.german_tense_subjunctive_ii
        GermanTense.IMPERATIVE -> R.string.german_tense_imperative
        GermanTense.PARTICIPLE_I -> R.string.german_tense_participle_i
        GermanTense.PARTICIPLE_II -> R.string.german_tense_participle_ii
        GermanTense.INFINITIVE -> R.string.german_tense_infinitive
    }

@StringRes
internal fun genderName(gender: GermanGender): Int =
    when (gender) {
        GermanGender.MASCULINE -> R.string.german_word_masculine
        GermanGender.FEMININE -> R.string.german_word_feminine
        GermanGender.NEUTER -> R.string.german_word_neuter
    }

@StringRes
private fun genderLetter(gender: GermanGender): Int =
    when (gender) {
        GermanGender.MASCULINE -> R.string.german_gender_letter_masculine
        GermanGender.FEMININE -> R.string.german_gender_letter_feminine
        GermanGender.NEUTER -> R.string.german_gender_letter_neuter
    }

@StringRes
private fun articleOf(gender: GermanGender): Int =
    when (gender) {
        GermanGender.MASCULINE -> R.string.german_article_masculine
        GermanGender.FEMININE -> R.string.german_article_feminine
        GermanGender.NEUTER -> R.string.german_article_neuter
    }
