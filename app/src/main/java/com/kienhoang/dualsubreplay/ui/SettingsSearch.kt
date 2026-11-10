package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_MODEL_PREFERENCE
import com.kienhoang.dualsubreplay.assistant.AI_TRANSLATION_THINKING_PREFERENCE
import com.kienhoang.dualsubreplay.data.CardFront
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_ONLY_PREFERENCE
import com.kienhoang.dualsubreplay.translation.AI_TRANSLATION_PREFERENCE
import com.kienhoang.dualsubreplay.translation.GOOGLE_WAIT_PREFERENCE
import com.kienhoang.dualsubreplay.translation.TRANSLATION_ENGINE_PREFERENCE
import com.kienhoang.dualsubreplay.update.APP_AUTO_UPDATE_PREFERENCE
import com.kienhoang.dualsubreplay.update.UPDATE_REMINDERS_PREFERENCE
import java.text.Normalizer
import java.util.Locale

/**
 * A setting that Search settings finds and Often changed lists (owner request 2026-11-06): its
 * name, the More settings section it is in, the tag of the place on the
 * page to scroll to ([settingAnchor]; [SettingsSwitchRow] uses its test tag), and the preference
 * keys a change of it writes.
 */
internal enum class SearchableSetting(
    @StringRes val titleRes: Int,
    val section: MoreSettingsSection,
    val anchor: String?,
    val keys: Set<String>,
    /** Names of the setting's choices, so "pause" or "dictation" finds Play mode (2026-11-09). */
    val alsoFinds: List<Int> = emptyList(),
) {
    ORIGINAL_LANGUAGE(
        R.string.settings_original_language,
        MoreSettingsSection.LANGUAGES,
        "source_language_picker",
        setOf("preferred_caption_language"),
    ),
    TRANSLATE_TO(R.string.settings_translate_to, MoreSettingsSection.LANGUAGES, "target_language_picker", setOf("target_language")),
    TEXT_SIZE(R.string.settings_text_size_name, MoreSettingsSection.READING, "font_scale", setOf("font_scale")),
    ORIGINAL_CAPTIONS(
        R.string.settings_original_captions_name,
        MoreSettingsSection.READING,
        ORIGINAL_VISIBILITY,
        setOf(ORIGINAL_VISIBILITY),
    ),
    TRANSLATED_CAPTIONS(
        R.string.settings_translated_captions_name,
        MoreSettingsSection.READING,
        TRANSLATED_VISIBILITY,
        setOf(TRANSLATED_VISIBILITY),
    ),
    TRANSLATION_POSITION(
        R.string.settings_translation_position_title,
        MoreSettingsSection.READING,
        "translation_position",
        setOf(TRANSLATION_POSITION_PREFERENCE),
    ),
    TRANSCRIPT_FOLLOW(
        R.string.settings_transcript_follow_title,
        MoreSettingsSection.READING,
        "transcript_follow",
        setOf(TRANSCRIPT_FOLLOW_PREFERENCE),
    ),
    HIGHLIGHT_WORDS(
        R.string.settings_highlight_spoken_words_title,
        MoreSettingsSection.READING,
        "word_highlight_switch",
        setOf(WORD_HIGHLIGHT_ENABLED_PREFERENCE),
    ),
    DEFAULT_VIEW(
        R.string.settings_group_default_view,
        MoreSettingsSection.READING,
        "default_view",
        setOf(PLAYER_EXPERIENCE_MODE_PREFERENCE),
    ),
    PANEL_POSITION(
        R.string.settings_portrait_panel_position,
        MoreSettingsSection.LAYOUT,
        "portrait_panel_position",
        setOf(PORTRAIT_PANEL_OFFSET_PREFERENCE),
    ),
    CAPTION_FORMAT(R.string.settings_caption_format_title, MoreSettingsSection.LAYOUT, "caption_format", setOf(CAPTION_FORMAT_PREFERENCE)),
    THIRD_LINE(
        R.string.settings_third_line_title,
        MoreSettingsSection.LAYOUT,
        "third_line_switch",
        setOf(THIRD_LINE_ENABLED_PREFERENCE, THIRD_LANGUAGE_PREFERENCE),
    ),
    LANDSCAPE_SPLIT(
        R.string.settings_landscape_split_title,
        MoreSettingsSection.LAYOUT,
        "landscape_split_switch",
        setOf("landscape_split_enabled"),
    ),
    TRANSLATOR(
        R.string.settings_translator_title,
        MoreSettingsSection.TRANSLATION,
        "translator",
        setOf(TRANSLATION_ENGINE_PREFERENCE, AI_TRANSLATION_PREFERENCE, AI_TRANSLATION_ONLY_PREFERENCE),
    ),
    GOOGLE_WAIT(R.string.settings_google_wait_title, MoreSettingsSection.TRANSLATION, "google_wait", setOf(GOOGLE_WAIT_PREFERENCE)),
    AI_TRANSLATION_MODEL(
        R.string.settings_ai_translation_model_title,
        MoreSettingsSection.TRANSLATION,
        "ai_translation_model",
        setOf(AI_TRANSLATION_MODEL_PREFERENCE),
    ),
    AI_TRANSLATION_THINKING(
        R.string.settings_ai_translation_thinking_title,
        MoreSettingsSection.TRANSLATION,
        "ai_translation_thinking",
        setOf(AI_TRANSLATION_THINKING_PREFERENCE),
    ),
    NATURAL_FLOW(
        R.string.settings_natural_flow_title,
        MoreSettingsSection.TRANSLATION,
        "natural_subtitles_switch",
        setOf(NATURAL_SUBTITLES_PREFERENCE),
    ),
    HIDE_SOUND_CUES(
        R.string.settings_hide_sound_cues_title,
        MoreSettingsSection.TRANSLATION,
        "hide_sound_cues_switch",
        setOf(HIDE_SOUND_CUES_PREFERENCE),
    ),
    PRELOAD_MODELS(
        R.string.settings_preload_models_title,
        MoreSettingsSection.TRANSLATION,
        "preload_models_switch",
        setOf(PRELOAD_MODELS_ENABLED_PREFERENCE),
    ),
    LANGUAGE_DOWNLOADS(R.string.language_downloads_title, MoreSettingsSection.TRANSLATION, "language_downloads", emptySet()),
    PRONOUNCE_WORDS(
        R.string.settings_pronounce_tapped_title,
        MoreSettingsSection.WORD_LEARNING,
        "auto_pronounce_switch",
        setOf("auto_pronounce"),
    ),
    PRONUNCIATION_VOLUME(
        R.string.settings_pronunciation_volume_name,
        MoreSettingsSection.WORD_LEARNING,
        "pronunciation_volume",
        setOf(PRONUNCIATION_VOLUME_PREFERENCE),
    ),
    PHONE_VOICE(
        R.string.settings_phone_voice_title,
        MoreSettingsSection.WORD_LEARNING,
        "phone_voice_switch",
        setOf(PHONE_VOICE_PREFERENCE),
    ),
    MEANING_IN_CONTEXT(
        R.string.settings_meaning_in_context_title,
        MoreSettingsSection.WORD_LEARNING,
        "meaning_in_context_switch",
        setOf(MEANING_IN_CONTEXT_PREFERENCE),
    ),
    WORD_LEARNING_MODE(
        R.string.settings_word_learning_mode_title,
        MoreSettingsSection.WORD_LEARNING,
        "word_learning_mode_switch",
        setOf(WORD_LEARNING_ENABLED_PREFERENCE),
    ),
    COLORED_LINES(
        R.string.settings_colored_lines_title,
        MoreSettingsSection.WORD_LEARNING,
        "colored_lines",
        setOf(WORD_LEARNING_TARGET_PREFERENCE),
    ),
    TAP_FOR_DEFINITION(
        R.string.settings_tap_definition_title,
        MoreSettingsSection.WORD_LEARNING,
        "tap_to_learn_switch",
        setOf(TAP_TO_LEARN_PREFERENCE),
    ),
    ACTIVE_SENTENCE_ONLY(
        R.string.settings_active_sentence_only_title,
        MoreSettingsSection.WORD_LEARNING,
        "word_learning_active_only_switch",
        setOf(WORD_LEARNING_ACTIVE_ONLY_PREFERENCE),
    ),
    PLAY_MODE(
        R.string.study_play_mode_title,
        MoreSettingsSection.STUDY,
        "study_play_mode",
        setOf(STUDY_PLAY_MODE_PREFERENCE, STUDY_REPEAT_COUNT_PREFERENCE),
        StudyPlayMode.entries.map { it.titleRes },
    ),
    LOOP_BUTTON(R.string.study_loop_button_title, MoreSettingsSection.STUDY, "loop_button_switch", setOf(LOOP_BUTTON_PREFERENCE)),
    SKIP_SILENCE(R.string.study_skip_silence_title, MoreSettingsSection.STUDY, "skip_silence", setOf(SKIP_SILENCE_PREFERENCE)),
    KNOWN_WORDS(
        R.string.study_known_words_title,
        MoreSettingsSection.WORD_LEARNING,
        "known_words",
        setOf(KNOWN_WORDS_PREFERENCE, KNOWN_WORDS_AFTER_PREFERENCE),
    ),
    MARK_NEW_WORDS(
        R.string.study_mark_new_words_title,
        MoreSettingsSection.WORD_LEARNING,
        "mark_new_words_switch",
        setOf(MARK_NEW_WORDS_PREFERENCE),
    ),
    SEND_TO(
        R.string.study_anki_title,
        MoreSettingsSection.FLASHCARDS,
        "ankidroid_switch",
        setOf(ANKIDROID_PREFERENCE, SEND_TO_APP_PREFERENCE, ANKICONNECT_DECK_PREFERENCE),
    ),
    CARD_AUDIO(R.string.study_card_audio_title, MoreSettingsSection.FLASHCARDS, "card_audio_switch", setOf(CARD_AUDIO_PREFERENCE)),
    CARD_PICTURE(R.string.study_card_picture_title, MoreSettingsSection.FLASHCARDS, "card_picture_switch", setOf(CARD_PICTURE_PREFERENCE)),
    CARD_FRONT(
        R.string.study_card_front_title,
        MoreSettingsSection.FLASHCARDS,
        "card_front",
        setOf(CARD_FRONT_PREFERENCE),
        CardFront.entries.map { it.labelRes },
    ),
    BLUR_BOX(R.string.study_blur_box_title, MoreSettingsSection.STUDY, "blur_box_switch", setOf(BLUR_BOX_PREFERENCE)),
    CHARACTER_DETAILS(
        R.string.study_kanji_details_title,
        MoreSettingsSection.WORD_LEARNING,
        "kanji_details_switch",
        setOf(KANJI_DETAILS_PREFERENCE),
    ),
    PAGE_TOKEN(R.string.study_page_token_title, MoreSettingsSection.STUDY, "page_token", setOf(PAGE_TOKEN_CAPTIONS_PREFERENCE)),
    OVERLAY_FULLSCREEN(
        R.string.settings_overlay_fullscreen_title,
        MoreSettingsSection.OVERLAY,
        "auto_overlay_fullscreen_switch",
        setOf(AUTO_OVERLAY_FULLSCREEN_PREFERENCE),
    ),
    OVERLAY_LANDSCAPE(
        R.string.settings_overlay_landscape_title,
        MoreSettingsSection.OVERLAY,
        "auto_overlay_landscape_switch",
        setOf(AUTO_OVERLAY_LANDSCAPE_PREFERENCE),
    ),
    MOVABLE_CONTROLS(
        R.string.settings_movable_controls_title,
        MoreSettingsSection.OVERLAY,
        "movable_subtitle_box_switch",
        setOf(MOVABLE_OVERLAY_PREFERENCE),
    ),
    LOCK_OVERLAY(
        R.string.settings_lock_overlay_title,
        MoreSettingsSection.OVERLAY,
        "lock_overlay_to_video_switch",
        setOf(LOCK_OVERLAY_TO_VIDEO_PREFERENCE),
    ),
    AVOID_CONTROLS(
        R.string.settings_avoid_controls_title,
        MoreSettingsSection.OVERLAY,
        "auto_avoid_player_controls_switch",
        setOf(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE),
    ),
    REMEMBER_POSITION(
        R.string.settings_remember_position_title,
        MoreSettingsSection.OVERLAY,
        "remember_overlay_position_switch",
        setOf(REMEMBER_OVERLAY_POSITION_PREFERENCE),
    ),
    CUSTOM_COLORS(
        R.string.settings_custom_colors_title,
        MoreSettingsSection.COLORS,
        "custom_colors_switch",
        setOf(
            CUSTOM_SUBTITLE_COLORS_ENABLED_PREFERENCE,
            SUBTITLE_ORIGINAL_COLOR_PREFERENCE,
            SUBTITLE_TRANSLATED_COLOR_PREFERENCE,
            SUBTITLE_HIGHLIGHT_COLOR_PREFERENCE,
        ),
    ),
    BOX_BACKGROUND(
        R.string.settings_box_background_title,
        MoreSettingsSection.COLORS,
        "box_background",
        setOf(SUBTITLE_BOX_BACKGROUND_PREFERENCE),
    ),
    APP_COLORS(R.string.settings_app_colors_title, MoreSettingsSection.COLORS, "app_colors", setOf(APP_COLOR_THEME_PREFERENCE)),
    ACCENT_COLOR(R.string.settings_accent_color_title, MoreSettingsSection.COLORS, "theme_accent", setOf(APP_THEME_ACCENT_PREFERENCE)),
    DIFFERENT_TAB_ACCOUNTS(
        R.string.tabs_different_accounts_title,
        MoreSettingsSection.TABS,
        "different_tab_accounts_switch",
        setOf(DIFFERENT_TAB_ACCOUNTS_PREFERENCE),
    ),
    SKIP_ADS(R.string.ads_skip_ads_title, MoreSettingsSection.ADS, "skip_ads_switch", setOf(SKIP_ADS_PREFERENCE)),
    SKIP_SPONSORS(R.string.ads_skip_sponsors_title, MoreSettingsSection.ADS, "skip_sponsors_switch", setOf(SKIP_SPONSORS_PREFERENCE)),
    ALWAYS_SKIP_SPONSORS(
        R.string.ads_always_skip_sponsors_title,
        MoreSettingsSection.ADS,
        "always_skip_sponsors_switch",
        setOf(ALWAYS_SKIP_SPONSORS_PREFERENCE),
    ),
    UPDATE_REMINDERS(
        R.string.updates_reminders_title,
        MoreSettingsSection.UPDATES,
        "update_reminders_switch",
        setOf(UPDATE_REMINDERS_PREFERENCE),
    ),
    AUTO_UPDATE(R.string.updates_auto_title, MoreSettingsSection.UPDATES, "auto_update_switch", setOf(APP_AUTO_UPDATE_PREFERENCE)),
    TV_LAYOUT(R.string.tv_layout_title, MoreSettingsSection.TV, "tv_layout_switch", setOf(TV_LAYOUT_PREFERENCE)),
    ;

    companion object {
        /** The setting a change of preference [key] belongs to, if any. */
        fun forKey(key: String?): SearchableSetting? = key?.let { changed -> entries.firstOrNull { changed in it.keys } }

        fun named(name: String): SearchableSetting? = entries.firstOrNull { it.name == name }
    }
}

/** One search result: a setting, or a whole More settings section when its name matches. */
internal data class SettingsSearchEntry(
    val title: String,
    val sectionTitle: String,
    val setting: SearchableSetting? = null,
    val section: MoreSettingsSection? = setting?.section,
    /** The names of the setting's choices ([SearchableSetting.alsoFinds]), matched like its name. */
    val choices: List<String> = emptyList(),
)

/** Lower case without accents, so "tieng viet" finds "Tiếng Việt" and "cafe" finds "café". */
internal fun normalizedForSearch(text: String): String =
    Normalizer
        .normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .replace('đ', 'd')

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val SEARCH_WORD_SPLIT = Regex("\\s+")

/**
 * The entries whose name, or name and section together, hold every word of [query]: names that
 * start with it first, then other name matches, then the ones only their section name matches.
 */
internal fun searchSettings(
    entries: List<SettingsSearchEntry>,
    query: String,
): List<SettingsSearchEntry> {
    val normalizedQuery = normalizedForSearch(query).trim()
    val words = normalizedQuery.split(SEARCH_WORD_SPLIT).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries
        .mapNotNull { entry ->
            val title = normalizedForSearch(entry.title)
            val withSection = "$title ${normalizedForSearch(entry.sectionTitle)}"
            val choices = entry.choices.map(::normalizedForSearch)
            when {
                title.startsWith(normalizedQuery) -> 0
                words.all { it in title } || choices.any { choice -> words.all { it in choice } } -> 1
                words.all { it in withSection } -> 2
                else -> null
            }?.let { rank -> rank to entry }
        }.sortedBy { it.first }
        .map { it.second }
}

/** One change of a setting on the settings page, at [atMs] (wall clock). */
internal data class SettingChange(
    val setting: String,
    val atMs: Long,
)

/** Often changed looks at the last 7 days. */
internal const val SETTING_HISTORY_MS = 7L * 24 * 60 * 60 * 1000

/** Changes of one setting each this close to the one before (a slider drag) count once. */
internal const val SETTING_CHANGE_MERGE_MS = 3_000L

/** Often changed lists settings changed at least this often, at most [OFTEN_CHANGED_MAX] of them. */
internal const val OFTEN_CHANGED_MIN_CHANGES = 2
internal const val OFTEN_CHANGED_MAX = 3

private const val SETTING_HISTORY_MAX_CHANGES = 200

private fun SettingChange.isRecent(nowMs: Long): Boolean = nowMs - atMs in 0..SETTING_HISTORY_MS

/** [history] with a change of [setting] now, keeping the last 7 days only. */
internal fun recordSettingChange(
    history: List<SettingChange>,
    setting: String,
    nowMs: Long,
): List<SettingChange> {
    val recent = history.filter { it.isRecent(nowMs) }
    val last = recent.indexOfLast { it.setting == setting }
    if (last >= 0 && nowMs - recent[last].atMs < SETTING_CHANGE_MERGE_MS) {
        // Still the same drag: it counts once, and the next step is measured from this one.
        return recent.toMutableList().apply { this[last] = SettingChange(setting, nowMs) }
    }
    return (recent + SettingChange(setting, nowMs)).takeLast(SETTING_HISTORY_MAX_CHANGES)
}

/** Up to 3 settings changed at least twice in the last 7 days: the most changed first, then the latest. */
internal fun oftenChangedSettings(
    history: List<SettingChange>,
    nowMs: Long,
): List<String> =
    history
        .filter { it.isRecent(nowMs) }
        .groupBy { it.setting }
        .filterValues { it.size >= OFTEN_CHANGED_MIN_CHANGES }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<SettingChange>>> {
                it.value.size
            }.thenByDescending { it.value.maxOf(SettingChange::atMs) },
        ).take(OFTEN_CHANGED_MAX)
        .map { it.key }

internal fun encodeSettingHistory(history: List<SettingChange>): String = history.joinToString(";") { "${it.setting}@${it.atMs}" }

internal fun decodeSettingHistory(text: String?): List<SettingChange> =
    text
        .orEmpty()
        .split(';')
        .mapNotNull { part ->
            val at = part.lastIndexOf('@')
            if (at <= 0) return@mapNotNull null
            part.substring(at + 1).toLongOrNull()?.let { SettingChange(part.substring(0, at), it) }
        }

private const val SETTINGS_USAGE_PREFERENCES = "settings_usage"
private const val SETTING_HISTORY_KEY = "changes"

/** The change history behind Often changed, in its own preferences so Reset all settings can clear it on purpose. */
internal class SettingsUsage(
    context: Context,
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(SETTINGS_USAGE_PREFERENCES, Context.MODE_PRIVATE)

    /** False once Reset all settings was asked for, so the reset's own changes are not counted. */
    var recording = true

    private fun history(): List<SettingChange> = decodeSettingHistory(preferences.getString(SETTING_HISTORY_KEY, null))

    fun record(
        setting: SearchableSetting,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!recording) return
        preferences.edit().putString(SETTING_HISTORY_KEY, encodeSettingHistory(recordSettingChange(history(), setting.name, nowMs))).apply()
    }

    fun oftenChanged(nowMs: Long = System.currentTimeMillis()): List<SearchableSetting> =
        oftenChangedSettings(history(), nowMs).mapNotNull(SearchableSetting::named)

    fun clear() {
        recording = false
        preferences.edit().clear().apply()
    }
}

/** Counts each change of a [SearchableSetting] while the settings page is open. */
@Composable
internal fun RecordSettingChanges(
    preferences: SharedPreferences,
    usage: SettingsUsage,
) {
    DisposableEffect(preferences, usage) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                SearchableSetting.forKey(key)?.let { usage.record(it) }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/**
 * Where each setting is on the settings page (y in the root, reported by [settingAnchor]), and the
 * one a jump just landed on, which glows for a moment.
 */
internal class SettingsAnchors {
    val tops = HashMap<String, Float>()
    var flashing by mutableStateOf<String?>(null)
}

internal val LocalSettingsAnchors = staticCompositionLocalOf<SettingsAnchors?> { null }

/** How long a setting glows after a jump to it. */
internal const val SETTINGS_FLASH_MILLIS = 1_600L

/**
 * Marks the place of the setting [tag] for Search settings and Often changed: a jump scrolls here,
 * and the place glows for a moment. Outside the settings page it does nothing.
 */
internal fun Modifier.settingAnchor(tag: String): Modifier =
    composed {
        val anchors = LocalSettingsAnchors.current ?: return@composed Modifier
        DisposableEffect(anchors, tag) { onDispose { anchors.tops.remove(tag) } }
        val glow by animateFloatAsState(if (anchors.flashing == tag) 1f else 0f, tween(SETTINGS_GLOW_FADE_MILLIS), label = "setting_glow")
        val color = MaterialTheme.colorScheme.primary
        Modifier
            .onGloballyPositioned { anchors.tops[tag] = it.positionInRoot().y }
            .drawBehind {
                if (glow > 0f) {
                    val inset = SETTINGS_GLOW_OUTSET.toPx()
                    drawRoundRect(
                        color = color.copy(alpha = SETTINGS_GLOW_ALPHA * glow),
                        topLeft = Offset(-inset, 0f),
                        size = Size(size.width + 2 * inset, size.height),
                        cornerRadius = CornerRadius(SETTINGS_GLOW_CORNER.toPx()),
                    )
                }
            }
    }

private const val SETTINGS_GLOW_FADE_MILLIS = 300
private const val SETTINGS_GLOW_ALPHA = 0.18f
private val SETTINGS_GLOW_OUTSET = 8.dp
private val SETTINGS_GLOW_CORNER = 12.dp

/** The search field at the very top of the settings page. */
@Composable
internal fun SettingsSearchField(
    query: String,
    onFocusChange: (Boolean) -> Unit = {},
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) }.testTag("settings_search"),
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("settings_search_clear")) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

/** The matching settings, or a line saying there are none. */
@Composable
internal fun SettingsSearchResults(
    results: List<SettingsSearchEntry>,
    onPick: (SettingsSearchEntry) -> Unit,
) {
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_no_results),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).testTag("settings_search_no_results"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    SettingsLinkCard {
        results.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsLinkRow(entry, "settings_search_result_${entry.setting?.name ?: entry.section?.key}") { onPick(entry) }
        }
    }
}

/**
 * Often changed, under Search settings while it is tapped: up to 3 settings, each a link to its
 * place on the page, or a line saying what will show here.
 */
@Composable
internal fun OftenChangedSettings(
    entries: List<SettingsSearchEntry>,
    onPick: (SettingsSearchEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.settings_often_changed_empty),
            modifier = Modifier.padding(horizontal = 4.dp).testTag("often_changed_empty"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    SettingsLinkCard(Modifier.testTag("often_changed_settings")) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.settings_often_changed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        entries.forEach { entry ->
            SettingsLinkRow(entry, "often_changed_${entry.setting?.name}") { onPick(entry) }
        }
    }
}

@Composable
private fun SettingsLinkCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsLinkRow(
    entry: SettingsSearchEntry,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tag)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                entry.sectionTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
