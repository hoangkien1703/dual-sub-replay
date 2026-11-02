package com.kienhoang.dualsubreplay.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.DeckSummary
import com.kienhoang.dualsubreplay.data.MAX_DECK_NAME_LENGTH
import com.kienhoang.dualsubreplay.data.PracticeDeck
import com.kienhoang.dualsubreplay.data.cleanDeckName
import com.kienhoang.dualsubreplay.data.resolveDeckName

private const val DECK_PREFERENCES = "practice_decks"

/**
 * The deck last picked for each word language, so saving stays one tap (owner decision 2026-11-02).
 * Kept apart from settings, like saved words, so Reset all settings keeps it.
 */
internal fun rememberedDeck(
    context: Context,
    language: String,
): String? = context.getSharedPreferences(DECK_PREFERENCES, Context.MODE_PRIVATE).getString("deck_$language", null)

internal fun rememberDeck(
    context: Context,
    language: String,
    deck: String?,
) {
    val editor = context.getSharedPreferences(DECK_PREFERENCES, Context.MODE_PRIVATE).edit()
    if (deck == null) editor.remove("deck_$language") else editor.putString("deck_$language", deck)
    editor.apply()
}

/** After a rename or delete, languages that remembered [old] remember [new] instead (null: their language deck). */
internal fun replaceRememberedDeck(
    context: Context,
    old: String,
    new: String?,
) {
    val preferences = context.getSharedPreferences(DECK_PREFERENCES, Context.MODE_PRIVATE)
    val editor = preferences.edit()
    preferences.all.filterValues { it == old }.keys.forEach { key ->
        if (new == null) editor.remove(key) else editor.putString(key, new)
    }
    editor.apply()
}

/** A named deck's name, or the language name of an automatic deck. */
@Composable
internal fun deckLabel(deck: PracticeDeck): String = deck.name ?: languageDisplayName(deck.language.orEmpty(), currentInterfaceLocale())

/**
 * "Deck: Arabic ▾" on the word card and on a saved word: the menu lists the word language's own
 * deck, the named decks, and New deck….
 */
@Composable
internal fun DeckChip(
    deck: String?,
    language: String,
    namedDecks: List<String>,
    onPick: (String?) -> Unit,
    enabled: Boolean = true,
    tag: String = "deck_chip",
) {
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val languageDeck = deckLabel(PracticeDeck(language = language))
    Box {
        AssistChip(
            onClick = { menu = true },
            enabled = enabled,
            label = {
                Text(
                    stringResource(R.string.practice_deck_label, deck ?: languageDeck),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.practice_deck_choose)) },
            modifier = Modifier.testTag(tag),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(languageDeck) },
                onClick = {
                    menu = false
                    onPick(null)
                },
                modifier = Modifier.testTag("deck_option_language"),
            )
            namedDecks.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        menu = false
                        onPick(name)
                    },
                    modifier = Modifier.testTag("deck_option_$index"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.practice_deck_new)) },
                onClick = {
                    menu = false
                    naming = true
                },
                modifier = Modifier.testTag("deck_option_new"),
            )
        }
    }
    if (naming) {
        DeckNameDialog(
            title = stringResource(R.string.practice_deck_new_title),
            initial = "",
            confirmLabel = stringResource(R.string.practice_deck_create),
            onConfirm = { typed ->
                naming = false
                resolveDeckName(typed, namedDecks)?.let(onPick)
            },
            onDismiss = { naming = false },
        )
    }
}

/** Names a new deck or renames one; asks before dropping a changed name. */
@Composable
internal fun DeckNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var askDiscard by remember { mutableStateOf(false) }
    val close = { if (text != initial) askDiscard = true else onDismiss() }
    AlertDialog(
        onDismissRequest = close,
        title = { DialogTitle(title, onClose = close) },
        text = {
            OutlinedTextField(
                text,
                { text = it.take(MAX_DECK_NAME_LENGTH) },
                label = { Text(stringResource(R.string.practice_deck_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("deck_name_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = cleanDeckName(text) != null && cleanDeckName(text) != cleanDeckName(initial),
                modifier = Modifier.testTag("deck_name_confirm"),
            ) { Text(confirmLabel) }
        },
    )
    if (askDiscard) DiscardChangesDialog(onDiscard = onDismiss, onKeepEditing = { askDiscard = false })
}

/** Practice's first page once words sit in more than one deck: each deck with its word and due counts. */
@Composable
internal fun DeckList(
    summaries: List<DeckSummary>,
    onOpen: (PracticeDeck) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(summaries, key = { _, summary -> summary.deck.toString() }) { index, summary ->
                ListItem(
                    headlineContent = { Text(deckLabel(summary.deck)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.practice_word_summary,
                                pluralStringResource(R.plurals.practice_word_count, summary.words, summary.words),
                                pluralStringResource(R.plurals.practice_due_count, summary.due, summary.due),
                            ),
                        )
                    },
                    modifier = Modifier.clickable { onOpen(summary.deck) }.testTag("deck_$index"),
                )
                HorizontalDivider()
            }
        }
        Text(stringResource(R.string.practice_deck_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** The open deck's name, a way back to all decks, and Rename / Delete for a named deck. */
@Composable
internal fun DeckHeader(
    deck: PracticeDeck,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("decks_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.practice_deck_all))
        }
        Text(
            deckLabel(deck),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("open_deck_name"),
        )
        if (deck.isNamed) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("deck_menu")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.practice_deck_menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.practice_deck_rename)) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                        modifier = Modifier.testTag("deck_rename"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.practice_deck_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("deck_delete"),
                    )
                }
            }
        }
    }
}

/** Deleting a deck only moves its words back to their language decks, with their review history. */
@Composable
internal fun DeleteDeckDialog(
    name: String,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.practice_deck_delete_title, name)) },
        text = { Text(stringResource(R.string.practice_deck_delete_body)) },
        confirmButton = {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("deck_delete_confirm")) {
                Text(stringResource(R.string.practice_delete))
            }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.practice_keep)) } },
    )
}
