package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.data.CaptionLanguage
import com.kienhoang.dualsubreplay.translation.TranslationLanguages

/** Collapsible groups that replace the old single "More settings" list. */
internal enum class MoreSettingsSection(
    val key: String,
    val title: String,
    val summary: String,
) {
    LAYOUT("layout", "Layout & format", "Panel position, caption format, split view"),
    COLORS("colors", "Colors & theme", "Subtitle colors, overlay background, app accent"),
    WORD_LEARNING("word_learning", "Word learning", "Word colors, tap for meaning, pronunciation"),
    OVERLAY("overlay", "Overlay & fullscreen", "Automatic overlay, dragging, saved positions"),
    TRANSLATION("translation", "Captions & translation", "Caption flow and model preloading"),
}

/** Opening a section closes the one that was open, so the page stays short. */
internal fun toggleMoreSettingsSection(
    open: MoreSettingsSection?,
    tapped: MoreSettingsSection,
): MoreSettingsSection? = if (open == tapped) null else tapped

private enum class LanguagePickerMode { SOURCE, TARGET }

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
    var pickerMode by remember { mutableStateOf<LanguagePickerMode?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var openSection by remember { mutableStateOf<MoreSettingsSection?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val sourceChoices =
        listOf(LanguageChoice("auto", "Auto (recommended)")) +
            availableSourceLanguages.map { LanguageChoice(it.code, it.name) }
    val targetChoices = TranslationLanguages.all.map { LanguageChoice(it.code, it.name) }

    val activePicker = pickerMode
    if (activePicker != null) {
        val choices = if (activePicker == LanguagePickerMode.SOURCE) sourceChoices else targetChoices
        LanguagePickerDialog(
            title =
                if (activePicker == LanguagePickerMode.SOURCE) {
                    "Original caption language"
                } else {
                    "Translate to"
                },
            choices = choices,
            selectedCode = if (activePicker == LanguagePickerMode.SOURCE) sourcePreference else targetLanguage,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onChoice = { choice ->
                if (activePicker == LanguagePickerMode.SOURCE) {
                    onSourceChange(choice.code)
                } else {
                    onTargetChange(choice.code)
                }
                pickerMode = null
                searchQuery = ""
            },
            onDismiss = {
                pickerMode = null
                searchQuery = ""
            },
            testTagPrefix = activePicker.name.lowercase(),
        )
        return
    }

    val sourceLabel =
        if (sourcePreference == "auto") {
            "Auto (recommended)"
        } else {
            sourceChoices
                .firstOrNull {
                    TranslationLanguages.normalize(it.code) == TranslationLanguages.normalize(sourcePreference)
                }?.label ?: TranslationLanguages.displayName(sourcePreference)
        }

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
                    SettingsGroupCard(title = "Languages", icon = Icons.Default.Language) {
                        Text("Original language")
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                pickerMode = LanguagePickerMode.SOURCE
                                searchQuery = ""
                            },
                            modifier = Modifier.fillMaxWidth().testTag("source_language_picker"),
                        ) {
                            Text(sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Translate to")
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                pickerMode = LanguagePickerMode.TARGET
                                searchQuery = ""
                            },
                            modifier = Modifier.fillMaxWidth().testTag("target_language_picker"),
                        ) {
                            Text(TranslationLanguages.displayName(targetLanguage))
                        }
                        SettingsHint("A language model downloads only when it is needed.")
                    }

                    SettingsGroupCard(title = "Reading", icon = Icons.Default.TextFields) {
                        Text("Text size: ${(fontScale * 100).toInt()}%")
                        Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = 0.8f..1.5f)
                        CaptionVisibilitySettings()
                        SettingsSwitchRow(
                            title = "Highlight spoken words",
                            description = "Tint the word being spoken so you can follow along.",
                            checked = wordHighlightEnabled,
                            onCheckedChange = onWordHighlightChange,
                            testTag = "word_highlight_switch",
                        )
                    }

                    SettingsGroupCard(title = "Default view", icon = Icons.Default.Dashboard) {
                        PlayerModeSettingsOption(
                            mode = PlayerExperienceMode.TRANSCRIPT_PANEL,
                            selectedMode = playerMode,
                            title = "Transcript panel",
                            description = "Full dual-subtitle timeline with paragraph replay.",
                            onModeChange = onPlayerModeChange,
                        )
                        HorizontalDivider()
                        PlayerModeSettingsOption(
                            mode = PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY,
                            selectedMode = playerMode,
                            title = "Scroll-friendly overlay",
                            description = "Compact captions while YouTube stays scrollable for comments and recommendations.",
                            onModeChange = onPlayerModeChange,
                        )
                    }

                    Text(
                        "More settings",
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
                            title = "Landscape split view",
                            description = "With the landscape overlay off, show the transcript beside the video. Drag to resize.",
                            checked = landscapeSplitEnabled,
                            onCheckedChange = onLandscapeSplitChange,
                            testTag = "landscape_split_switch",
                        )
                        SettingsHint(
                            "Swipe the transcript header down (or right in split view) to hide it. Captions keep tracking while hidden.",
                        )
                    }

                    Section(MoreSettingsSection.COLORS, Icons.Default.Palette) {
                        SettingsSwitchRow(
                            title = "Custom subtitle colors",
                            description = "Use the colors below. When off, the default subtitle colors are used.",
                            checked = customColorsEnabled,
                            onCheckedChange = onCustomColorsChange,
                            testTag = "custom_colors_switch",
                        )
                        if (customColorsEnabled) {
                            SubtitleColorSwatchRow(
                                title = "Original subtitle color",
                                selectedKey = originalColorKey,
                                enabled = true,
                                onColorChange = onOriginalColorChange,
                                testTagPrefix = "original_color",
                            )
                            SubtitleColorSwatchRow(
                                title = "Translated subtitle color",
                                selectedKey = translatedColorKey,
                                enabled = true,
                                onColorChange = onTranslatedColorChange,
                                testTagPrefix = "translated_color",
                            )
                            SubtitleColorSwatchRow(
                                title = "Spoken-word highlight",
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
                            title = "Natural subtitle flow & punctuation",
                            description = "Merge auto-generated captions along speech pauses and clauses, with proper capitalization.",
                            checked = naturalSubtitlesEnabled,
                            onCheckedChange = onNaturalSubtitlesChange,
                            testTag = "natural_subtitles_switch",
                        )
                        SettingsSwitchRow(
                            title = "Preload translation models in background",
                            description = "Download translation models at launch so playback starts without waiting.",
                            checked = preloadModelsEnabled,
                            onCheckedChange = onPreloadModelsChange,
                            testTag = "preload_models_switch",
                        )
                    }

                    OutlinedButton(
                        onClick = { showResetConfirmation = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("reset_all_settings"),
                    ) {
                        Text("Reset all settings to defaults", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (showResetConfirmation) {
            AlertDialog(
                onDismissRequest = { showResetConfirmation = false },
                title = { Text("Reset all settings?") },
                text = {
                    Text(
                        "Languages, text size, colors, view mode, and overlay options " +
                            "will return to their defaults. Your current video stays open.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetConfirmation = false
                            onResetSettings()
                        },
                        modifier = Modifier.testTag("confirm_reset_settings"),
                    ) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") }
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
            Icon(Icons.Default.Close, contentDescription = "Close settings")
        }
        Text(
            "Dual-subtitle settings",
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onDismiss) { Text("Done") }
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
                        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
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
                    Text(section.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        section.summary,
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
        title = "Pronounce tapped words",
        description = "Speak a word when you open its definition.",
        checked = autoPronounce,
        onCheckedChange = onAutoPronounceChange,
        testTag = "auto_pronounce_switch",
    )
    SettingsSwitchRow(
        title = "Word learning mode (POS colors)",
        description = "Color words by grammatical role (nouns, verbs, adjectives, particles) to see sentence structure.",
        checked = wordLearningEnabled,
        onCheckedChange = onWordLearningChange,
        testTag = "word_learning_mode_switch",
    )
    if (!wordLearningEnabled) return
    Spacer(Modifier.height(8.dp))
    Text("Colored subtitle lines", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("original" to "Original", "translation" to "Translation", "both" to "Both").forEach { (targetKey, targetLabel) ->
            FilterChip(
                selected = wordLearningTarget == targetKey,
                onClick = { onWordLearningTargetChange(targetKey) },
                label = { Text(targetLabel) },
            )
        }
    }
    SettingsSwitchRow(
        title = "Tap word for definition",
        description = "Tap any word to see its reading, part of speech, and translation.",
        checked = tapToLearnEnabled,
        onCheckedChange = onTapToLearnChange,
        testTag = "tap_to_learn_switch",
    )
    SettingsSwitchRow(
        title = "Highlight active sentence only",
        description = "Only color the sentence being spoken to keep the transcript clean.",
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

    SettingsSubheading("Fullscreen & landscape")
    SettingsSwitchRow(
        title = "Use overlay in fullscreen",
        description = "Show the compact dual-subtitle overlay when YouTube enters fullscreen.",
        checked = autoOverlayFullscreen,
        onCheckedChange = {
            autoOverlayFullscreen = it
            setBooleanPreference(AUTO_OVERLAY_FULLSCREEN_PREFERENCE, it)
        },
        testTag = "auto_overlay_fullscreen_switch",
    )
    SettingsSwitchRow(
        title = "Use overlay when rotated sideways",
        description = "Replace the transcript panel with the compact overlay in landscape.",
        checked = autoOverlayLandscape,
        onCheckedChange = {
            autoOverlayLandscape = it
            setBooleanPreference(AUTO_OVERLAY_LANDSCAPE_PREFERENCE, it)
        },
        testTag = "auto_overlay_landscape_switch",
    )

    SettingsSectionDivider()
    SettingsSubheading("Moving the overlay")
    SettingsSwitchRow(
        title = "Movable subtitle controls",
        description = "Drag the overlay or the collapsed CC button where you want. In fullscreen the overlay can reach the top edge.",
        checked = movableSubtitleBox,
        onCheckedChange = {
            movableSubtitleBox = it
            setBooleanPreference(MOVABLE_OVERLAY_PREFERENCE, it)
        },
        testTag = "movable_subtitle_box_switch",
    )
    SettingsSwitchRow(
        title = "Lock overlay to video player",
        description = "Keep the portrait overlay inside the video area instead of letting it move down the screen.",
        checked = lockOverlayToVideo,
        onCheckedChange = onLockOverlayToVideoChange,
        testTag = "lock_overlay_to_video_switch",
    )
    SettingsSwitchRow(
        title = "Automatically avoid video controls",
        description = "Move subtitles up while YouTube's seek bar and controls are visible.",
        checked = autoAvoidPlayerControls,
        onCheckedChange = {
            autoAvoidPlayerControls = it
            setBooleanPreference(AUTO_AVOID_PLAYER_CONTROLS_PREFERENCE, it)
        },
        testTag = "auto_avoid_player_controls_switch",
    )
    SettingsSwitchRow(
        title = "Remember dragged position",
        description = "Save where you drag the overlay and CC button and reuse those positions.",
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
    SettingsHint(
        "Drag the overlay up, down, or sideways. When the transcript is hidden, you can drag the CC button too. " +
            "In portrait, flicking the overlay down closes it.",
    )
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
        Text("Reset subtitle positions")
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
