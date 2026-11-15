package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.GermanAdverbKind
import com.kienhoang.dualsubreplay.data.GermanCase
import com.kienhoang.dualsubreplay.data.GermanConjugationTable
import com.kienhoang.dualsubreplay.data.GermanDeclension
import com.kienhoang.dualsubreplay.data.GermanGovernment
import com.kienhoang.dualsubreplay.data.GermanRole
import com.kienhoang.dualsubreplay.data.GermanSelectionConjugation
import com.kienhoang.dualsubreplay.data.GermanSyntaxRole
import com.kienhoang.dualsubreplay.data.GermanTableTense
import com.kienhoang.dualsubreplay.data.GermanVerbKind
import com.kienhoang.dualsubreplay.data.GermanWordRole

/**
 * "Grammar in this sentence": what the tapped word is and why it has this form here, read from its line. It does not depend on a
 * grammar point being found; what the line leaves open is said so instead of being guessed.
 */
@Composable
internal fun GermanRoleSection(
    role: GermanWordRole,
    word: String,
) {
    val title = roleTitle(role)
    val lines = roleLines(role, word)
    Column(Modifier.fillMaxWidth().testTag("german_role"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.german_role_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("german_role_title"))
        lines.forEachIndexed { i, line -> Text(line, modifier = Modifier.testTag("german_role_line_$i")) }
        if (role.tentative) {
            Text(
                stringResource(R.string.german_why_tentative),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("german_role_tentative"),
            )
        }
    }
}

@Composable
private fun roleTitle(role: GermanWordRole): String {
    val name = stringResource(roleName(role.role))
    val kind = role.adverbKind?.let { stringResource(adverbKindName(it)) }
    val person =
        if (role.person > 0 && role.plural != null) {
            stringResource(
                R.string.german_word_person,
                role.person,
                stringResource(if (role.plural) R.string.german_word_plural_number else R.string.german_word_singular),
            )
        } else {
            null
        }
    val genders = if (role.plural == true) emptyList() else role.genders.sortedBy { it.ordinal }.map { stringResource(genderName(it)) }
    val cases = role.cases.sortedBy { it.ordinal }.map { stringResource(caseName(it)) }
    return listOfNotNull(
        name,
        kind,
        person,
        genders.takeIf { it.isNotEmpty() }?.joinToString("/"),
        cases.takeIf { it.isNotEmpty() }?.joinToString(" / "),
    ).joinToString(" · ")
}

@Composable
private fun roleLines(
    role: GermanWordRole,
    word: String,
): List<String> {
    val case = role.cases.minByOrNull { it.ordinal }?.let { stringResource(caseName(it)) }
    return when (role.role) {
        GermanRole.PERSONAL_PRONOUN -> pronounLines(role, case)
        GermanRole.REFLEXIVE_PRONOUN, GermanRole.RELATIVE_PRONOUN, GermanRole.DEMONSTRATIVE_PRONOUN, GermanRole.INTERROGATIVE ->
            pointerLines(
                role,
            )
        GermanRole.DEFINITE_ARTICLE, GermanRole.INDEFINITE_ARTICLE, GermanRole.NEGATIVE_ARTICLE, GermanRole.POSSESSIVE,
        GermanRole.DEMONSTRATIVE_DETERMINER, GermanRole.OTHER_DETERMINER,
        -> determinerLines(role)
        GermanRole.PREPOSITION -> prepositionLines(role, word, case)
        GermanRole.COORDINATOR -> listOf(stringResource(R.string.german_why_coord))
        GermanRole.SUBORDINATOR -> listOf(stringResource(R.string.german_why_subord, role.target.orEmpty()))
        GermanRole.ADVERB -> adverbLines(role)
        GermanRole.DEGREE_WORD -> listOfNotNull(role.target?.let { stringResource(R.string.german_why_degree, it) })
        GermanRole.ADJECTIVE_ATTRIBUTIVE, GermanRole.ADJECTIVE_PREDICATIVE, GermanRole.ADJECTIVE_ADVERBIAL -> adjectiveLines(role)
        GermanRole.INDEFINITE_PRONOUN -> emptyList()
        GermanRole.INFINITIVE_ZU -> listOf(stringResource(R.string.german_why_zu, role.target.orEmpty()))
    }
}

/** Reflexive, relative and demonstrative pronouns and question words: what they refer back to and where the verb goes. */
@Composable
private fun pointerLines(role: GermanWordRole): List<String> =
    buildList {
        val governor = role.governor
        when (role.role) {
            GermanRole.REFLEXIVE_PRONOUN ->
                add(
                    if (governor !=
                        null
                    ) {
                        stringResource(R.string.german_why_reflexive_verb, governor)
                    } else {
                        stringResource(R.string.german_why_reflexive)
                    },
                )
            GermanRole.RELATIVE_PRONOUN ->
                add(
                    if (governor !=
                        null
                    ) {
                        stringResource(R.string.german_why_relative, governor)
                    } else {
                        stringResource(R.string.german_why_relative_plain)
                    },
                )
            GermanRole.DEMONSTRATIVE_PRONOUN -> add(stringResource(R.string.german_why_demonstrative))
            else -> {
                if (role.verbLast) add(stringResource(R.string.german_why_question_sub))
                if (role.verbSecond) add(stringResource(R.string.german_why_question_main))
            }
        }
    }

@Composable
private fun determinerLines(role: GermanWordRole): List<String> =
    buildList {
        role.target?.let { add(stringResource(R.string.german_why_determiner, it)) }
        role.declension?.let { add(stringResource(R.string.german_why_after_determiner, stringResource(declensionName(it)))) }
    }

@Composable
private fun adjectiveLines(role: GermanWordRole): List<String> {
    val target = role.target.orEmpty()
    return when (role.role) {
        GermanRole.ADJECTIVE_ATTRIBUTIVE ->
            listOf(
                stringResource(
                    R.string.german_why_adj_attr,
                    target,
                    role.ending,
                    role.declension?.let { stringResource(declensionName(it)) }.orEmpty(),
                ),
            )
        GermanRole.ADJECTIVE_PREDICATIVE -> listOf(stringResource(R.string.german_why_adj_pred, target))
        else -> listOf(stringResource(R.string.german_why_adj_adv, target))
    }
}

@Composable
private fun pronounLines(
    role: GermanWordRole,
    case: String?,
): List<String> =
    buildList {
        when (role.function) {
            GermanSyntaxRole.SUBJECT -> add(stringResource(R.string.german_why_subject))
            GermanSyntaxRole.DIRECT_OBJECT -> add(stringResource(R.string.german_why_direct))
            GermanSyntaxRole.DATIVE_OBJECT ->
                add(
                    if (role.governor != null) {
                        stringResource(R.string.german_why_dative, role.governor)
                    } else {
                        stringResource(R.string.german_why_dative_plain)
                    },
                )
            GermanSyntaxRole.AFTER_PREPOSITION ->
                if (role.governor != null && case != null) add(stringResource(R.string.german_why_after_prep, role.governor, case))
            null -> Unit
        }
    }

@Composable
private fun prepositionLines(
    role: GermanWordRole,
    word: String,
    case: String?,
): List<String> =
    buildList {
        role.contractedWith?.let { add(stringResource(R.string.german_why_prep_contraction, it)) }
        val single = role.cases.singleOrNull()
        when {
            role.fixedWith != null -> {
                add(stringResource(R.string.german_why_prep_fixed, role.fixedWith))
                if (case != null && single != null) add(stringResource(R.string.german_why_prep_case, word, case))
            }
            role.government == GermanGovernment.TWO_WAY ->
                if (case != null &&
                    single != null
                ) {
                    add(stringResource(R.string.german_why_prep_twoway, case))
                }
            single != null && case != null -> add(stringResource(R.string.german_why_prep_case, word, case))
        }
    }

@Composable
private fun adverbLines(role: GermanWordRole): List<String> =
    buildList {
        if (role.target !=
            null
        ) {
            add(stringResource(R.string.german_why_adv_verb, role.target))
        } else {
            add(stringResource(R.string.german_why_adv_sentence))
        }
        if (role.verbSecond) add(stringResource(R.string.german_why_adv_front))
        role.comparable?.let { add(stringResource(if (it) R.string.german_why_comparable else R.string.german_why_not_comparable)) }
    }

/**
 * The tables of the tapped verb. They start closed (they are long); the chips pick the tense, and the form this sentence uses is
 * marked in its table.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GermanConjugationSection(selection: GermanSelectionConjugation) {
    val conjugation = selection.conjugation
    if (conjugation.tables.isEmpty()) return
    CollapsibleWordCardSection(
        title = stringResource(R.string.german_conj_heading),
        count = conjugation.tables.size,
        preferenceKey = WORD_CARD_GERMAN_CONJUGATION_OPEN_PREFERENCE,
        testTag = "german_conjugation",
        defaultOpen = false,
    ) {
        Text(stringResource(verbKindName(conjugation.kind)), modifier = Modifier.testTag("german_conj_kind"))
        if (conjugation.reflexive) {
            Text(stringResource(R.string.german_conj_reflexive), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val first = selection.tense?.takeIf { t -> conjugation.tables.any { it.tense == t } } ?: conjugation.tables.first().tense
        var tense by remember(conjugation.verb.infinitive, first) { mutableStateOf(first) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (table in conjugation.tables) {
                FilterChip(
                    selected = tense == table.tense,
                    onClick = { tense = table.tense },
                    label = { Text(stringResource(tenseTableName(table.tense))) },
                    modifier = Modifier.testTag("german_tense_tab_${table.tense.name}"),
                )
            }
        }
        conjugation.tables.firstOrNull { it.tense == tense }?.let { ConjugationRows(it, selection) }
        if (conjugation.participles.size > 1) {
            Text(
                stringResource(R.string.german_conj_ersatz, conjugation.participles.drop(1).joinToString(" / ")),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("german_conj_ersatz"),
            )
        }
    }
}

@Composable
private fun ConjugationRows(
    table: GermanConjugationTable,
    selection: GermanSelectionConjugation,
) {
    val used = stringResource(R.string.german_conj_this_form)
    Column(Modifier.fillMaxWidth().testTag("german_conj_table"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        table.rows.forEachIndexed { index, row ->
            val marked = selection.tense == table.tense && selection.row == index
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (marked) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics(mergeDescendants = true) { if (marked) stateDescription = used }
                        .testTag("german_conj_row_$index"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    row.pronoun,
                    modifier = Modifier.weight(0.35f),
                    color = if (marked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    row.forms.joinToString(" / "),
                    modifier = Modifier.weight(0.65f),
                    fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (marked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@StringRes
private fun roleName(role: GermanRole): Int =
    when (role) {
        GermanRole.PERSONAL_PRONOUN -> R.string.german_role_personal
        GermanRole.REFLEXIVE_PRONOUN -> R.string.german_role_reflexive
        GermanRole.RELATIVE_PRONOUN -> R.string.german_role_relative
        GermanRole.DEMONSTRATIVE_PRONOUN -> R.string.german_role_demonstrative_pronoun
        GermanRole.INDEFINITE_PRONOUN -> R.string.german_role_indefinite_pronoun
        GermanRole.INTERROGATIVE -> R.string.german_role_interrogative
        GermanRole.DEFINITE_ARTICLE -> R.string.german_role_def_article
        GermanRole.INDEFINITE_ARTICLE -> R.string.german_role_indef_article
        GermanRole.NEGATIVE_ARTICLE -> R.string.german_role_neg_article
        GermanRole.POSSESSIVE -> R.string.german_role_possessive
        GermanRole.DEMONSTRATIVE_DETERMINER -> R.string.german_role_demo_det
        GermanRole.OTHER_DETERMINER -> R.string.german_role_other_det
        GermanRole.PREPOSITION -> R.string.german_role_preposition
        GermanRole.COORDINATOR -> R.string.german_role_coordinator
        GermanRole.SUBORDINATOR -> R.string.german_role_subordinator
        GermanRole.ADVERB -> R.string.german_role_adverb
        GermanRole.DEGREE_WORD -> R.string.german_role_degree
        GermanRole.ADJECTIVE_ATTRIBUTIVE -> R.string.german_role_adj_attr
        GermanRole.ADJECTIVE_PREDICATIVE -> R.string.german_role_adj_pred
        GermanRole.ADJECTIVE_ADVERBIAL -> R.string.german_role_adj_adv
        GermanRole.INFINITIVE_ZU -> R.string.german_role_infinitive_zu
    }

@StringRes
private fun adverbKindName(kind: GermanAdverbKind): Int =
    when (kind) {
        GermanAdverbKind.TIME -> R.string.german_adverb_time
        GermanAdverbKind.PLACE -> R.string.german_adverb_place
        GermanAdverbKind.MANNER -> R.string.german_adverb_manner
        GermanAdverbKind.ATTITUDE -> R.string.german_adverb_attitude
        GermanAdverbKind.CONNECTIVE -> R.string.german_adverb_connective
        GermanAdverbKind.NEGATION -> R.string.german_adverb_negation
    }

@StringRes
private fun declensionName(declension: GermanDeclension): Int =
    when (declension) {
        GermanDeclension.WEAK -> R.string.german_decl_weak
        GermanDeclension.MIXED -> R.string.german_decl_mixed
        GermanDeclension.STRONG -> R.string.german_decl_strong
    }

@StringRes
private fun verbKindName(kind: GermanVerbKind): Int =
    when (kind) {
        GermanVerbKind.WEAK -> R.string.german_conj_kind_weak
        GermanVerbKind.STRONG -> R.string.german_conj_kind_strong
        GermanVerbKind.MIXED -> R.string.german_conj_kind_mixed
        GermanVerbKind.MODAL -> R.string.german_conj_kind_modal
        GermanVerbKind.PRETERITE_PRESENT -> R.string.german_conj_kind_preterite_present
        GermanVerbKind.AUXILIARY -> R.string.german_conj_kind_auxiliary
    }

@StringRes
private fun tenseTableName(tense: GermanTableTense): Int =
    when (tense) {
        GermanTableTense.PRESENT -> R.string.german_tense_table_present
        GermanTableTense.PRETERITE -> R.string.german_tense_table_preterite
        GermanTableTense.PERFECT -> R.string.german_tense_table_perfect
        GermanTableTense.PLUPERFECT -> R.string.german_tense_table_pluperfect
    }
