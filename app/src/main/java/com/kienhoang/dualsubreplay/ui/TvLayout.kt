package com.kienhoang.dualsubreplay.ui

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

// Settings → Android TV (beta) (owner request 2026-10-29/30, spec 2026-10-30-android-tv-layout.md):
// on a TV the TV layout is on unless turned off; on phones and tablets it can never turn on.

internal const val TV_LAYOUT_PREFERENCE = "tv_layout"

/** Reset all settings brings the TV layout back to its default (on, on a TV). */
internal val TV_LAYOUT_SETTING_KEYS = listOf(TV_LAYOUT_PREFERENCE)

/** Panel text on a TV is read from across the room. */
internal const val TV_TEXT_SCALE = 1.25f

/** Fire TV says so with its own feature instead of Android TV's. */
private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

/** A TV by any of the ways Android reports one: television UI mode, Android TV's leanback, or Fire TV. */
internal fun isTelevision(
    televisionUiMode: Boolean,
    leanback: Boolean,
    fireTv: Boolean,
): Boolean = televisionUiMode || leanback || fireTv

internal fun isTelevisionDevice(context: Context): Boolean {
    val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
    val packages = context.packageManager
    return isTelevision(
        televisionUiMode = uiMode == Configuration.UI_MODE_TYPE_TELEVISION,
        leanback = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
        fireTv = packages.hasSystemFeature(FIRE_TV_FEATURE),
    )
}

/** The TV layout is on only on a TV, and there unless the user turned it off. */
internal fun tvLayoutOn(
    television: Boolean,
    switchedOn: Boolean,
): Boolean = television && switchedOn

internal fun readTvLayout(
    preferences: SharedPreferences,
    television: Boolean,
): Boolean = tvLayoutOn(television, preferences.getBoolean(TV_LAYOUT_PREFERENCE, true))

/** Whether the TV layout is on, following every change (from the settings page or the assistant). */
@Composable
internal fun rememberTvLayout(): State<Boolean> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val television = remember(context) { isTelevisionDevice(context) }
    val on = remember(preferences) { mutableStateOf(readTvLayout(preferences, television)) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == TV_LAYOUT_PREFERENCE) on.value = readTvLayout(preferences, television)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        on.value = readTvLayout(preferences, television)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return on
}

/** True while the app shows its TV layout; false everywhere else, including isolated UI tests. */
internal val LocalTvLayout = compositionLocalOf { false }

/** More settings → Android TV (beta). On anything but a TV the switch stays off and greyed out. */
@Composable
internal fun TvLayoutSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val television = remember(context) { isTelevisionDevice(context) }
    val on by rememberTvLayout()
    SettingsSwitchRow(
        title = stringResource(R.string.tv_layout_title),
        description = stringResource(R.string.tv_layout_description),
        checked = on,
        onCheckedChange = { preferences.edit().putBoolean(TV_LAYOUT_PREFERENCE, it).apply() },
        testTag = "tv_layout_switch",
        enabled = television,
    )
    SettingsHint(stringResource(if (television) R.string.tv_layout_note else R.string.tv_layout_needs_tv))
}

/**
 * A clear ring around the focused item, so a remote's focus can be followed from the sofa. Put it
 * before the item's `clickable` or `focusable`; it draws nothing outside the TV layout.
 */
internal fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier =
    composed {
        if (!LocalTvLayout.current) return@composed Modifier
        var focused by remember { mutableStateOf(false) }
        val color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
        Modifier.onFocusChanged { focused = it.hasFocus }.border(3.dp, color, shape)
    }
