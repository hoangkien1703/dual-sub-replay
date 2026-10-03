package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import java.util.Locale

/** Collapsible groups that replace the old single "More settings" list. */
internal enum class MoreSettingsSection(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    LAYOUT("layout", R.string.settings_section_layout_title, R.string.settings_section_layout_summary),
    COLORS("colors", R.string.settings_section_colors_title, R.string.settings_section_colors_summary),
    WORD_LEARNING(
        "word_learning",
        R.string.settings_section_word_learning_title,
        R.string.settings_section_word_learning_summary,
    ),
    OVERLAY("overlay", R.string.settings_section_overlay_title, R.string.settings_section_overlay_summary),
    TRANSLATION(
        "translation",
        R.string.settings_section_translation_title,
        R.string.settings_section_translation_summary,
    ),
}

/** Opening a section closes the one that was open, so the page stays short. */
internal fun toggleMoreSettingsSection(
    open: MoreSettingsSection?,
    tapped: MoreSettingsSection,
): MoreSettingsSection? = if (open == tapped) null else tapped

internal enum class LanguagePickerMode { SOURCE, TARGET }

/** Which language list is open, shared by the full settings page and the quick languages popup. */
internal class LanguagePickerState {
    var mode by mutableStateOf<LanguagePickerMode?>(null)
        private set
    var query by mutableStateOf("")

    fun open(mode: LanguagePickerMode) {
        this.mode = mode
        query = ""
    }

    fun close() {
        mode = null
        query = ""
    }
}

/** [autoLabel] names the "auto" choice; caption track names come from YouTube as they are. */
internal fun sourceLanguageChoices(
    availableSourceLanguages: List<CaptionLanguage>,
    autoLabel: String,
): List<LanguageChoice> =
    listOf(LanguageChoice("auto", autoLabel)) +
        availableSourceLanguages.map { LanguageChoice(it.code, it.name) }

internal fun sourceLanguageLabel(
    sourcePreference: String,
    sourceChoices: List<LanguageChoice>,
    autoLabel: String,
    interfaceLocale: Locale,
): String =
    if (sourcePreference == "auto") {
        autoLabel
    } else {
        sourceChoices
            .firstOrNull {
                TranslationLanguages.normalize(it.code) == TranslationLanguages.normalize(sourcePreference)
            }?.let { choice ->
                // A track named exactly like the catalog language is shown in the interface language.
                if (choice.label == TranslationLanguages.find(choice.code)?.name) {
                    languageDisplayName(choice.code, interfaceLocale)
                } else {
                    choice.label
                }
            } ?: languageDisplayName(sourcePreference, interfaceLocale)
    }

/**
 * Shows the language list while one is open and returns true, so the caller can hide its own
 * dialog until a language is picked.
 */
@Composable
private fun showLanguagePickerIfOpen(
    picker: LanguagePickerState,
    sourcePreference: String,
    targetLanguage: String,
    sourceChoices: List<LanguageChoice>,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
): Boolean {
    val mode = picker.mode ?: return false
    val source = mode == LanguagePickerMode.SOURCE
    val interfaceLocale = currentInterfaceLocale()
    LanguagePickerDialog(
        title =
            stringResource(
                if (source) R.string.settings_original_caption_language else R.string.settings_translate_to,
            ),
        choices =
            if (source) {
                sourceChoices
            } else {
                TranslationLanguages.all.map { LanguageChoice(it.code, languageDisplayName(it.code, interfaceLocale)) }
            },
        selectedCode = if (source) sourcePreference else targetLanguage,
        searchQuery = picker.query,
        onSearchQueryChange = { picker.query = it },
        onChoice = { choice ->
            if (source) onSourceChange(choice.code) else onTargetChange(choice.code)
            picker.close()
        },
        onDismiss = picker::close,
        testTagPrefix = mode.name.lowercase(),
    )
    return true
}

@Composable
private fun LanguagePickerButtons(
    sourceLabel: String,
    targetLanguage: String,
    onPick: (LanguagePickerMode) -> Unit,
) {
    Text(stringResource(R.string.settings_original_language))
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = { onPick(LanguagePickerMode.SOURCE) },
        modifier = Modifier.fillMaxWidth().testTag("source_language_picker"),
    ) {
        Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.settings_translate_to))
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = { onPick(LanguagePickerMode.TARGET) },
        modifier = Modifier.fillMaxWidth().testTag("target_language_picker"),
    ) {
        Text(languageDisplayName(targetLanguage, currentInterfaceLocale()))
    }
    SettingsHint(stringResource(R.string.settings_model_download_hint))
}

/**
 * The subtitle panel's gear opens this small popup with only the languages, over the video.
 * "Dual-subtitle settings" leads to the full settings page.
 */
@Composable
internal fun QuickLanguageSettingsDialog(
    sourcePreference: String,
    targetLanguage: String,
    availableSourceLanguages: List<CaptionLanguage>,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onOpenAllSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val languagePicker = remember { LanguagePickerState() }
    val autoLabel = stringResource(R.string.settings_source_language_auto)
    val interfaceLocale = currentInterfaceLocale()
    val sourceChoices = sourceLanguageChoices(availableSourceLanguages, autoLabel)
    val pickerOpen =
        showLanguagePickerIfOpen(
            picker = languagePicker,
            sourcePreference = sourcePreference,
            targetLanguage = targetLanguage,
            sourceChoices = sourceChoices,
            onSourceChange = onSourceChange,
            onTargetChange = onTargetChange,
        )
    if (pickerOpen) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Language, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_subtitle_languages)) },
        text = {
            Column(Modifier.testTag("quick_language_settings")) {
                LanguagePickerButtons(
                    sourceLabel = sourceLanguageLabel(sourcePreference, sourceChoices, autoLabel, interfaceLocale),
                    targetLanguage = targetLanguage,
                    onPick = languagePicker::open,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClick = onOpenAllSettings)
                            .testTag("open_all_settings")
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_dual_subtitle_settings), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_dual_subtitle_settings_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) } },
    )
}

@Composable
@Suppress("LongMethod")
internal fun SubtitleSettingsDialog(
    sourcePreference: String,
    targetLanguage: String,
    availableSourceLanguages: List<CaptionLanguage>,
    fontScale: Float,
    portraitPanelOffsetFraction: Float = DEFAULT_PORTRAIT_PANEL_OFFSET_FRACTION,
    onPortraitPanelOffsetFractionChange: (Float) -> Unit = {},
    onResetPortraitPanelPosition: () -> Unit = {},
    landscapeSplitEnabled: Boolean,
    playerMode: PlayerExperienceMode = PlayerExperienceMode.TRANSCRIPT_PANEL,
    originalColorKey: String = DEFAULT_ORIGINAL_COLOR_KEY,
    translatedColorKey: String = DEFAULT_TRANSLATED_COLOR_KEY,
    highlightColorKey: String = DEFAULT_HIGHLIGHT_COLOR_KEY,
    wordHighlightEnabled: Boolean = true,
    customColorsEnabled: Boolean = true,
    captionFormat: CaptionFormat = CaptionFormat.SHORT_PHRASES,
    lockOverlayToVideo: Boolean = false,
    onLockOverlayToVideoChange: (Boolean) -> Unit = {},
    preloadModelsEnabled: Boolean = true,
    onPreloadModelsChange: (Boolean) -> Unit = {},
    naturalSubtitlesEnabled: Boolean = true,
    onNaturalSubtitlesChange: (Boolean) -> Unit = {},
    wordLearningEnabled: Boolean = true,
    onWordLearningChange: (Boolean) -> Unit = {},
    wordLearningTarget: String = "both",
    onWordLearningTargetChange: (String) -> Unit = {},
    wordLearningActiveOnly: Boolean = true,
    onWordLearningActiveOnlyChange: (Boolean) -> Unit = {},
    tapToLearnEnabled: Boolean = true,
    onTapToLearnChange: (Boolean) -> Unit = {},
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onLandscapeSplitChange: (Boolean) -> Unit,
    onPlayerModeChange: (PlayerExperienceMode) -> Unit = {},
    onOriginalColorChange: (String) -> Unit = {},
    onTranslatedColorChange: (String) -> Unit = {},
    onHighlightColorChange: (String) -> Unit = {},
    onWordHighlightChange: (Boolean) -> Unit = {},
    onCustomColorsChange: (Boolean) -> Unit = {},
    onCaptionFormatChange: (CaptionFormat) -> Unit = {},
    onResetSettings: () -> Unit = {},
    autoPronounce: Boolean = true,
    onAutoPronounceChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val languagePicker = remember { LanguagePickerState() }
    var openSection by remember { mutableStateOf<MoreSettingsSection?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val autoLabel = stringResource(R.string.settings_source_language_auto)
    val sourceChoices = sourceLanguageChoices(availableSourceLanguages, autoLabel)
    val pickerOpen =
        showLanguagePickerIfOpen(
            picker = languagePicker,
            sourcePreference = sourcePreference,
            targetLanguage = targetLanguage,
            sourceChoices = sourceChoices,
            onSourceChange = onSourceChange,
            onTargetChange = onTargetChange,
        )
    if (pickerOpen) return
    val sourceLabel =
        sourceLanguageLabel(sourcePreference, sourceChoices, autoLabel, currentInterfaceLocale())

    @Composable
    fun Section(
        section: MoreSettingsSection,
        icon: ImageVector,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        ExpandableSettingsSection(
            section = section,
            icon = icon,
            expanded = openSection == section,
            onToggle = { openSection = toggleMoreSettingsSection(openSection, section) },
            content = content,
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                SettingsTopBar(onDismiss)
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsGroupCard(title = stringResource(R.string.settings_group_languages), icon = Icons.Default.Language) {
                        LanguagePickerButtons(sourceLabel, targetLanguage, languagePicker::open)
                    }

                    SettingsGroupCard(title = stringResource(R.string.settings_group_reading), icon = Icons.Default.TextFields) {
                        Text(stringResource(R.string.settings_text_size, (fontScale * 100).toInt()))
                        Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE)
                        CaptionVisibilitySettings()
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_highlight_spoken_words_title),
                            description = stringResource(R.string.settings_highlight_spoken_words_description),
                            checked = wordHighlightEnabled,
                            onCheckedChange = onWordHighlightChange,
                            testTag = "word_highlight_switch",
                        )
                    }

                    SettingsGroupCard(title = stringResource(R.string.settings_group_default_view), icon = Icons.Default.Dashboard) {
                        PlayerModeSettingsOption(
                            mode = PlayerExperienceMode.TRANSCRIPT_PANEL,
                            selectedMode = playerMode,
                            title = stringResource(R.string.settings_transcript_panel_title),
                            description = stringResource(R.string.settings_transcript_panel_description),
                            onModeChange = onPlayerModeChange,
                        )
                        HorizontalDivider()
                        PlayerModeSettingsOption(
                            mode = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
                            selectedMode = playerMode,
                            title = stringResource(R.string.settings_scroll_overlay_title),
                            description = stringResource(R.string.settings_scroll_overlay_description),
                            onModeChange = onPlayerModeChange,
                        )
                    }

                    Text(
                        stringResource(R.string.settings_more_settings),
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Section(MoreSettingsSection.LAYOUT, Icons.Default.ViewDay) {
                        PortraitPanelPositionSettings(
                            offsetFraction = portraitPanelOffsetFraction,
                            onOffsetFractionChange = onPortraitPanelOffsetFractionChange,
                            onReset = onResetPortraitPanelPosition,
                        )
                        SettingsSectionDivider()
                        CaptionFormatSettings(captionFormat, onCaptionFormatChange)
                        SettingsSectionDivider()
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_landscape_split_title),
                            description = stringResource(R.string.settings_landscape_split_description),
                            checked = landscapeSplitEnabled,
                            onCheckedChange = onLandscapeSplitChange,
                            testTag = "landscape_split_switch",
                        )
                        SettingsHint(
                            stringResource(R.string.settings_hide_transcript_hint),
                        )
                    }

                    Section(MoreSettingsSection.COLORS, Icons.Default.Palette) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_custom_colors_title),
                            description = stringResource(R.string.settings_custom_colors_description),
                            checked = customColorsEnabled,
                            onCheckedChange = onCustomColorsChange,
                            testTag = "custom_colors_switch",
                        )
                        if (customColorsEnabled) {
                            SubtitleColorSwatchRow(
                                title = stringResource(R.string.settings_original_color_title),
                                selectedKey = originalColorKey,
                                enabled = true,
                                onColorChange = onOriginalColorChange,
                                testTagPrefix = "original_color",
                            )
                            SubtitleColorSwatchRow(
                                title = stringResource(R.string.settings_translated_color_title),
                                selectedKey = translatedColorKey,
                                enabled = true,
                                onColorChange = onTranslatedColorChange,
                                testTagPrefix = "translated_color",
                            )
                            SubtitleColorSwatchRow(
                                title = stringResource(R.string.settings_highlight_color_title),
                                selectedKey = highlightColorKey,
                                enabled = wordHighlightEnabled,
                                onColorChange = onHighlightColorChange,
                                testTagPrefix = "highlight_color",
                            )
                        }
                        SettingsSectionDivider()
                        AdvancedAppearanceSettings()
                    }

                    Section(MoreSettingsSection.WORD_LEARNING, Icons.Default.School) {
                        WordLearningSettings(
                            autoPronounce = autoPronounce,
                            onAutoPronounceChange = onAutoPronounceChange,
                            wordLearningEnabled = wordLearningEnabled,
                            onWordLearningChange = onWordLearningChange,
                            wordLearningTarget = wordLearningTarget,
                            onWordLearningTargetChange = onWordLearningTargetChange,
                            tapToLearnEnabled = tapToLearnEnabled,
                            onTapToLearnChange = onTapToLearnChange,
                            wordLearningActiveOnly = wordLearningActiveOnly,
                            onWordLearningActiveOnlyChange = onWordLearningActiveOnlyChange,
                        )
                    }

                    Section(MoreSettingsSection.OVERLAY, Icons.Default.Fullscreen) {
                        OverlaySettings(lockOverlayToVideo, onLockOverlayToVideoChange)
                    }

                    Section(MoreSettingsSection.TRANSLATION, Icons.Default.Translate) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_natural_flow_title),
                            description = stringResource(R.string.settings_natural_flow_description),
                            checked = naturalSubtitlesEnabled,
                            onCheckedChange = onNaturalSubtitlesChange,
                            testTag = "natural_subtitles_switch",
                        )
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_preload_models_title),
                            description = stringResource(R.string.settings_preload_models_description),
                            checked = preloadModelsEnabled,
                            onCheckedChange = onPreloadModelsChange,
                            testTag = "preload_models_switch",
                        )
                    }

                    OutlinedButton(
                        onClick = { showResetConfirmation = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("reset_all_settings"),
                    ) {
                        Text(stringResource(R.string.settings_reset_all_button), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (showResetConfirmation) {
            AlertDialog(
                onDismissRequest = { showResetConfirmation = false },
                title = { Text(stringResource(R.string.settings_reset_all_title)) },
                text = {
                    Text(stringResource(R.string.settings_reset_all_message))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetConfirmation = false
                            onResetSettings()
                        },
                        modifier = Modifier.testTag("confirm_reset_settings"),
                    ) { Text(stringResource(R.string.settings_reset)) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmation = false }) { Text(stringResource(R.string.settings_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun SettingsTopBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_settings")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_close_settings))
        }
        Text(
            stringResource(R.string.settings_dual_subtitle_settings),
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ExpandableSettingsSection(
    section: MoreSettingsSection,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expansionState =
        stringResource(if (expanded) R.string.settings_section_expanded else R.string.settings_section_collapsed)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onToggle)
                        .semantics { stateDescription = expansionState }
                        .testTag("settings_section_${section.key}")
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(section.summaryRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun WordLearningSettings(
    autoPronounce: Boolean,
    onAutoPronounceChange: (Boolean) -> Unit,
    wordLearningEnabled: Boolean,
    onWordLearningChange: (Boolean) -> Unit,
    wordLearningTarget: String,
    onWordLearningTargetChange: (String) -> Unit,
    tapToLearnEnabled: Boolean,
    onTapToLearnChange: (Boolean) -> Unit,
    wordLearningActiveOnly: Boolean,
    onWordLearningActiveOnlyChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = stringResource(R.string.settings_pronounce_tapped_title),
        description = stringResource(R.string.settings_pronounce_tapped_description),
        checked = autoPronounce,
        onCheckedChange = onAutoPronounceChange,
        testTag = "auto_pronounce_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_word_learning_mode_title),
        description = stringResource(R.string.settings_word_learning_mode_description),
        checked = wordLearningEnabled,
        onCheckedChange = onWordLearningChange,
        testTag = "word_learning_mode_switch",
    )
    if (!wordLearningEnabled) return
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.settings_colored_lines_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "original" to R.string.settings_colored_lines_original,
            "translation" to R.string.settings_colored_lines_translation,
            "both" to R.string.settings_colored_lines_both,
        ).forEach { (targetKey, targetLabelRes) ->
            FilterChip(
                selected = wordLearningTarget == targetKey,
                onClick = { onWordLearningTargetChange(targetKey) },
                label = { Text(stringResource(targetLabelRes)) },
            )
        }
    }
    SettingsSwitchRow(
        title = stringResource(R.string.settings_tap_definition_title),
        description = stringResource(R.string.settings_tap_definition_description),
        checked = tapToLearnEnabled,
        onCheckedChange = onTapToLearnChange,
        testTag = "tap_to_learn_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_active_sentence_only_title),
        description = stringResource(R.string.settings_active_sentence_only_description),
        checked = wordLearningActiveOnly,
        onCheckedChange = onWordLearningActiveOnlyChange,
        testTag = "word_learning_active_only_switch",
    )
}

@Composable
@Suppress("LongMethod")
private fun OverlaySettings(
    lockOverlayToVideo: Boolean,
    onLockOverlayToVideoChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("dual_sub_preferences", 0) }
    var autoOverlayFullscreen by remember {
        mutableStateOf(preferences.getBoolean(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, true))
    }
    var autoOverlayLandscape by remember {
        mutableStateOf(preferences.getBoolean(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, true))
    }
    var autoAvoidPlayerControls by remember {
        mutableStateOf(preferences.getBoolean(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE, true))
    }
    var rememberOverlayPosition by remember {
        mutableStateOf(preferences.getBoolean(REMEMBER_OVERLAY_POSITION_PREFERENCE, true))
    }
    var movableSubtitleBox by remember {
        mutableStateOf(preferences.getBoolean(MOVABLE_OVERLAY_PREFERENCE, true))
    }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                when (key) {
                    AUTO_OVERLAY_FULLSCREEN_PREFERENCE -> autoOverlayFullscreen = sharedPreferences.getBoolean(key, true)
                    AUTO_OVERLAY_LANDSCAPE_PREFERENCE -> autoOverlayLandscape = sharedPreferences.getBoolean(key, true)
                    AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE -> autoAvoidPlayerControls = sharedPreferences.getBoolean(key, true)
                    REMEMBER_OVERLAY_POSITION_PREFERENCE -> rememberOverlayPosition = sharedPreferences.getBoolean(key, true)
                    MOVABLE_OVERLAY_PREFERENCE -> movableSubtitleBox = sharedPreferences.getBoolean(key, true)
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun setBooleanPreference(
        key: String,
        value: Boolean,
    ) {
        preferences.edit().putBoolean(key, value).apply()
    }

    SettingsSubheading(stringResource(R.string.settings_overlay_fullscreen_heading))
    SettingsSwitchRow(
        title = stringResource(R.string.settings_overlay_fullscreen_title),
        description = stringResource(R.string.settings_overlay_fullscreen_description),
        checked = autoOverlayFullscreen,
        onCheckedChange = {
            autoOverlayFullscreen = it
            setBooleanPreference(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, it)
        },
        testTag = "auto_overlay_fullscreen_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_overlay_landscape_title),
        description = stringResource(R.string.settings_overlay_landscape_description),
        checked = autoOverlayLandscape,
        onCheckedChange = {
            autoOverlayLandscape = it
            setBooleanPreference(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, it)
        },
        testTag = "auto_overlay_landscape_switch",
    )

    SettingsSectionDivider()
    SettingsSubheading(stringResource(R.string.settings_overlay_moving_heading))
    SettingsSwitchRow(
        title = stringResource(R.string.settings_movable_controls_title),
        description = stringResource(R.string.settings_movable_controls_description),
        checked = movableSubtitleBox,
        onCheckedChange = {
            movableSubtitleBox = it
            setBooleanPreference(MOVABLE_OVERLAY_PREFERENCE, it)
        },
        testTag = "movable_subtitle_box_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_lock_overlay_title),
        description = stringResource(R.string.settings_lock_overlay_description),
        checked = lockOverlayToVideo,
        onCheckedChange = onLockOverlayToVideoChange,
        testTag = "lock_overlay_to_video_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_avoid_controls_title),
        description = stringResource(R.string.settings_avoid_controls_description),
        checked = autoAvoidPlayerControls,
        onCheckedChange = {
            autoAvoidPlayerControls = it
            setBooleanPreference(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE, it)
        },
        testTag = "auto_avoid_player_controls_switch",
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_remember_position_title),
        description = stringResource(R.string.settings_remember_position_description),
        checked = rememberOverlayPosition,
        onCheckedChange = { enabled ->
            rememberOverlayPosition = enabled
            val editor =
                preferences
                    .edit()
                    .putBoolean(REMEMBER_OVERLAY_POSITION_PREFERENCE, enabled)
            if (!enabled) {
                editor
                    .remove(OVERLAY_VERTICAL_POSITION_PREFERENCE)
                    .remove(OVERLAY_HORIZONTAL_POSITION_PREFERENCE)
                    .remove(COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE)
                    .remove(COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE)
            }
            editor.apply()
        },
        testTag = "remember_overlay_position_switch",
    )
    SettingsHint(stringResource(R.string.settings_drag_overlay_hint))
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            preferences
                .edit()
                .putFloat(OVERLAY_VERTICAL_POSITION_PREFERENCE, DEFAULT_OVERLAY_VERTICAL_POSITION)
                .putFloat(OVERLAY_HORIZONTAL_POSITION_PREFERENCE, DEFAULT_OVERLAY_HORIZONTAL_POSITION)
                .putFloat(COLLAPSED_CC_HORIZONTAL_POSITION_PREFERENCE, DEFAULT_COLLAPSED_CC_HORIZONTAL_POSITION)
                .putFloat(COLLAPSED_CC_VERTICAL_POSITION_PREFERENCE, DEFAULT_COLLAPSED_CC_VERTICAL_POSITION)
                .apply()
        },
        modifier = Modifier.fillMaxWidth().testTag("reset_overlay_position"),
    ) {
        Text(stringResource(R.string.settings_reset_subtitle_positions))
    }
}

@Composable
private fun SettingsSubheading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SubtitleColorSwatchRow(
    title: String,
    selectedKey: String,
    enabled: Boolean,
    onColorChange: (String) -> Unit,
    testTagPrefix: String,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title)
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SubtitleColorOption.entries.forEach { option ->
                val selected = option.key == selectedKey
                Box(
                    modifier =
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(option.argb))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ).clip(CircleShape)
                            .clickable(enabled = enabled) { onColorChange(option.key) }
                            .testTag("color_option_${testTagPrefix}_${option.key}"),
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun PlayerModeSettingsOption(
    mode: PlayerExperienceMode,
    selectedMode: PlayerExperienceMode,
    title: String,
    description: String,
    onModeChange: (PlayerExperienceMode) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onModeChange(mode) }
                .padding(vertical = 8.dp)
                .testTag("player_mode_${mode.storageValue}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selectedMode == mode,
            onClick = { onModeChange(mode) },
        )
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
