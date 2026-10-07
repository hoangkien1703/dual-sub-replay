package com.kienhoang.dualsubreplay.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.GrammarMatch
import com.kienhoang.dualsubreplay.data.GrammarMeaning
import com.kienhoang.dualsubreplay.data.JapaneseMorphology
import com.kienhoang.dualsubreplay.data.JlptLevel
import com.kienhoang.dualsubreplay.data.LanguageAwareTokenizer
import com.kienhoang.dualsubreplay.data.LearningWordSelection
import com.kienhoang.dualsubreplay.data.grammarForSelection
import com.kienhoang.dualsubreplay.data.japaneseGrammar

/** How each grammar meaning reads to the learner: the rule points here, the catalogue in [GRAMMAR_POINT_TEXT]. */
internal val GRAMMAR_MEANING_TEXT: Map<GrammarMeaning, Int> by lazy { GRAMMAR_RULE_TEXT + GRAMMAR_POINT_TEXT }

private val GRAMMAR_RULE_TEXT: Map<GrammarMeaning, Int> =
    mapOf(
        GrammarMeaning.TOPIC to R.string.grammar_topic,
        GrammarMeaning.CONTRAST to R.string.grammar_contrast,
        GrammarMeaning.SUBJECT to R.string.grammar_subject,
        GrammarMeaning.OBJECT_OF_FEELING to R.string.grammar_object_of_feeling,
        GrammarMeaning.BUT to R.string.grammar_but,
        GrammarMeaning.OBJECT to R.string.grammar_object,
        GrammarMeaning.THROUGH to R.string.grammar_through,
        GrammarMeaning.PLACE_EXIST to R.string.grammar_place_exist,
        GrammarMeaning.TIME to R.string.grammar_time,
        GrammarMeaning.DESTINATION to R.string.grammar_destination,
        GrammarMeaning.RECIPIENT to R.string.grammar_recipient,
        GrammarMeaning.PURPOSE to R.string.grammar_purpose,
        GrammarMeaning.BY_PASSIVE to R.string.grammar_by_passive,
        GrammarMeaning.PLACE_OF_ACTION to R.string.grammar_place_of_action,
        GrammarMeaning.MEANS to R.string.grammar_means,
        GrammarMeaning.CAUSE to R.string.grammar_cause,
        GrammarMeaning.LIMIT to R.string.grammar_limit,
        GrammarMeaning.TOWARD to R.string.grammar_toward,
        GrammarMeaning.WITH_AND to R.string.grammar_with_and,
        GrammarMeaning.QUOTE to R.string.grammar_quote,
        GrammarMeaning.WHEN_NATURAL to R.string.grammar_when_natural,
        GrammarMeaning.ALSO to R.string.grammar_also,
        GrammarMeaning.EVEN to R.string.grammar_even,
        GrammarMeaning.FROM to R.string.grammar_from,
        GrammarMeaning.BECAUSE to R.string.grammar_because,
        GrammarMeaning.UNTIL to R.string.grammar_until,
        GrammarMeaning.THAN to R.string.grammar_than,
        GrammarMeaning.FROM_FORMAL to R.string.grammar_from_formal,
        GrammarMeaning.POSSESSIVE to R.string.grammar_possessive,
        GrammarMeaning.NOMINALIZER to R.string.grammar_nominalizer,
        GrammarMeaning.EXPLAIN to R.string.grammar_explain,
        GrammarMeaning.AND_AMONG to R.string.grammar_and_among,
        GrammarMeaning.QUESTION to R.string.grammar_question,
        GrammarMeaning.OR to R.string.grammar_or,
        GrammarMeaning.SEEK_AGREEMENT to R.string.grammar_seek_agreement,
        GrammarMeaning.NEW_INFO to R.string.grammar_new_info,
        GrammarMeaning.ALTHOUGH to R.string.grammar_although,
        GrammarMeaning.SO to R.string.grammar_so,
        GrammarMeaning.EVEN_THOUGH to R.string.grammar_even_though,
        GrammarMeaning.WHILE to R.string.grammar_while,
        GrammarMeaning.THINGS_LIKE to R.string.grammar_things_like,
        GrammarMeaning.ONLY_NEGATIVE to R.string.grammar_only_negative,
        GrammarMeaning.ONLY to R.string.grammar_only,
        GrammarMeaning.IF to R.string.grammar_if,
        GrammarMeaning.TE_AND to R.string.grammar_te_and,
        GrammarMeaning.IN_AT_FORMAL to R.string.grammar_in_at_formal,
        GrammarMeaning.ABOUT to R.string.grammar_about,
        GrammarMeaning.BY_DEPENDING to R.string.grammar_by_depending,
        GrammarMeaning.AS_ROLE to R.string.grammar_as_role,
        GrammarMeaning.FOR_VIEWPOINT to R.string.grammar_for_viewpoint,
        GrammarMeaning.TOWARD_CONTRAST to R.string.grammar_toward_contrast,
        GrammarMeaning.MUST to R.string.grammar_must,
        GrammarMeaning.MUST_NOT to R.string.grammar_must_not,
        GrammarMeaning.MAY to R.string.grammar_may,
        GrammarMeaning.CAN to R.string.grammar_can,
        GrammarMeaning.PROGRESSIVE to R.string.grammar_progressive,
        GrammarMeaning.COMPLETION to R.string.grammar_completion,
        GrammarMeaning.FAVOR_RECEIVE to R.string.grammar_favor_receive,
        GrammarMeaning.FAVOR_GIVE to R.string.grammar_favor_give,
        GrammarMeaning.FAVOR_HAVE to R.string.grammar_favor_have,
        GrammarMeaning.PLEASE to R.string.grammar_please,
        GrammarMeaning.TRY to R.string.grammar_try,
        GrammarMeaning.IN_ADVANCE to R.string.grammar_in_advance,
        GrammarMeaning.RESULT_STATE to R.string.grammar_result_state,
        GrammarMeaning.CHANGE_COMING to R.string.grammar_change_coming,
        GrammarMeaning.CHANGE_GOING to R.string.grammar_change_going,
        GrammarMeaning.POLITE to R.string.grammar_polite,
        GrammarMeaning.POLITE_PAST to R.string.grammar_polite_past,
        GrammarMeaning.POLITE_NEGATIVE to R.string.grammar_polite_negative,
        GrammarMeaning.POLITE_NEGATIVE_PAST to R.string.grammar_polite_negative_past,
        GrammarMeaning.LETS_POLITE to R.string.grammar_lets_polite,
        GrammarMeaning.PROBABLY to R.string.grammar_probably,
        GrammarMeaning.IF_WHEN to R.string.grammar_if_when,
        GrammarMeaning.PAST to R.string.grammar_past,
        GrammarMeaning.NEGATIVE to R.string.grammar_negative,
        GrammarMeaning.WANT to R.string.grammar_want,
        GrammarMeaning.PASSIVE to R.string.grammar_passive,
        GrammarMeaning.POTENTIAL to R.string.grammar_potential,
        GrammarMeaning.RESPECT to R.string.grammar_respect,
        GrammarMeaning.CAUSATIVE to R.string.grammar_causative,
        GrammarMeaning.VOLITIONAL to R.string.grammar_volitional,
        GrammarMeaning.LOOKS_LIKE to R.string.grammar_looks_like,
        GrammarMeaning.COPULA_POLITE to R.string.grammar_copula_polite,
        GrammarMeaning.COPULA to R.string.grammar_copula,
        GrammarMeaning.NA_ADJECTIVE to R.string.grammar_na_adjective,
    )

@StringRes
private fun meaningText(meaning: GrammarMeaning): Int = GRAMMAR_MEANING_TEXT.getValue(meaning)

/**
 * Where the selected word sits in [line]. A tap's offsets point into the line it came from; if the
 * line was reformatted since, the first occurrence of the word is used instead.
 */
internal fun selectionRangeIn(
    line: String,
    word: String,
    start: Int,
    end: Int,
): IntRange? {
    if (start in 0..end && end <= line.length && line.substring(start, end) == word) return start until end
    val found = line.indexOf(word)
    return if (found >= 0 && word.isNotEmpty()) found until found + word.length else null
}

/** The grammar of a Japanese selection, read from its whole subtitle line; empty for other languages. */
@Composable
internal fun GrammarExplanations(selection: LearningWordSelection) {
    val segment = selection.segment
    val line = (if (selection.translated) segment?.translatedText else segment?.originalText) ?: selection.token.text
    if (!LanguageAwareTokenizer.isJapanese(line, selection.wordLanguage)) return
    val revision = tokenizerRevision()
    val matches =
        remember(line, selection.token, revision) {
            val range = selectionRangeIn(line, selection.token.text, selection.token.startIndex, selection.token.endIndex)
            val morphemes = JapaneseMorphology.morphemes(line)
            if (range == null || morphemes == null) {
                emptyList()
            } else {
                grammarForSelection(japaneseGrammar(morphemes), range.first, range.last + 1)
            }
        }
    GrammarPointList(matches)
}

/** The "Grammar" heading and one card per grammar point; nothing when there are none. */
@Composable
internal fun GrammarPointList(matches: List<GrammarMatch>) {
    if (matches.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("grammar_section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.grammar_title), style = MaterialTheme.typography.titleSmall)
        matches.forEach { GrammarPointCard(it) }
    }
}

/** A small "N3" label, so learners see how advanced each point is. */
@Composable
private fun JlptBadge(level: JlptLevel) {
    val description = stringResource(R.string.grammar_jlpt_level_description, level.number)
    Surface(
        modifier = Modifier.padding(top = 2.dp).testTag("grammar_level").semantics { contentDescription = description },
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            stringResource(R.string.grammar_jlpt_level, level.number),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp).clearAndSetSemantics { },
        )
    }
}

@Composable
private fun GrammarPointCard(match: GrammarMatch) {
    var showAll by remember(match) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("grammar_point"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(match.form, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    JlptBadge(match.level)
                }
                Text(stringResource(meaningText(match.meanings.first())), modifier = Modifier.weight(1f))
            }
            val others = match.meanings.drop(1)
            if (others.isNotEmpty()) {
                if (showAll) {
                    others.forEach { meaning ->
                        Text(
                            stringResource(meaningText(meaning)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).testTag("grammar_alternate"),
                        )
                    }
                }
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.testTag("grammar_toggle_alternates")) {
                    Text(stringResource(if (showAll) R.string.grammar_hide_alternates else R.string.grammar_show_alternates))
                }
            }
        }
    }
}
