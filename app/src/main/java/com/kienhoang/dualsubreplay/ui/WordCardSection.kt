package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/** Whether the word card's Kanji section is open (owner request 2026-10-24); open by default. */
internal const val WORD_CARD_KANJI_OPEN_PREFERENCE = "word_card_kanji_open"

/** Whether the word card's Characters section (Chinese) is open; open by default. */
internal const val WORD_CARD_HANZI_OPEN_PREFERENCE = "word_card_hanzi_open"

/** Whether the word card's Grammar section is open; open by default. */
internal const val WORD_CARD_GRAMMAR_OPEN_PREFERENCE = "word_card_grammar_open"

/** The word card's remembered open/closed sections, all reset by Reset all settings. */
internal val WORD_CARD_SECTION_KEYS =
    listOf(
        WORD_CARD_KANJI_OPEN_PREFERENCE,
        WORD_CARD_HANZI_OPEN_PREFERENCE,
        WORD_CARD_GRAMMAR_OPEN_PREFERENCE,
    )

internal fun wordCardSectionOpen(
    preferences: SharedPreferences,
    key: String,
): Boolean = preferences.getBoolean(key, true)

/**
 * A word card section with an arrow heading: a tap on the heading hides or shows [content], and
 * the choice is kept under [preferenceKey] for every later card, also after a restart. While
 * hidden, the heading shows how many items ([count]) the section holds.
 */
@Composable
internal fun CollapsibleWordCardSection(
    title: String,
    count: Int,
    preferenceKey: String,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    var open by remember(preferences, preferenceKey) { mutableStateOf(wordCardSectionOpen(preferences, preferenceKey)) }
    val expansionState = stringResource(if (open) R.string.settings_section_expanded else R.string.settings_section_collapsed)
    Column(Modifier.fillMaxWidth().testTag(testTag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        open = !open
                        preferences.edit().putBoolean(preferenceKey, open).apply()
                    }.semantics { stateDescription = expansionState }
                    .testTag("${testTag}_header")
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
            if (!open) {
                Surface(
                    modifier = Modifier.padding(start = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp).testTag("${testTag}_count"),
                    )
                }
            }
        }
        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}
