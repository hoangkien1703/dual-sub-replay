package com.kienhoang.dualsubreplay.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R

/*
 * TEMPORARY, test build only (owner request 2026-11-05): Bluetooth earphones sometimes distort, drop
 * out or repeat the sound in this app, but not in YouTube in Chrome. Each stage turns off more of the
 * app's own work on the page, so the owner can hear which stage makes the glitches stop. Remove this
 * file, its strings and its uses once the cause is found.
 */

internal const val SOUND_TEST_PREFERENCE = "sound_test_stage"

internal enum class SoundTestStage(
    val storageValue: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    NORMAL("normal", R.string.sound_test_normal, R.string.sound_test_normal_description),

    /** Full screen shows WebView's own view directly again, as before the screen-off fix (PR #190, v1.8.15). */
    OLD_FULL_SCREEN("old_full_screen", R.string.sound_test_old_full_screen, R.string.sound_test_old_full_screen_description),

    /** No media notification or media session: nothing tells the earphones what plays. */
    NO_NOTIFICATION("no_notification", R.string.sound_test_no_notification, R.string.sound_test_no_notification_description),

    /** The page is read once a second instead of 10 times a second while a video plays. */
    SLOW_READING("slow_reading", R.string.sound_test_slow_reading, R.string.sound_test_slow_reading_description),

    /** None of the app's repeating page scripts run, and the subtitles stop moving. */
    NO_SCRIPTS("no_scripts", R.string.sound_test_no_scripts, R.string.sound_test_no_scripts_description),

    /** As [NO_SCRIPTS], and the page is loaded again without the scripts the app adds before YouTube's own. */
    PLAIN_PAGE("plain_page", R.string.sound_test_plain_page, R.string.sound_test_plain_page_description),
    ;

    val oldFullScreen: Boolean get() = this >= OLD_FULL_SCREEN
    val noNotification: Boolean get() = this >= NO_NOTIFICATION
    val slowReading: Boolean get() = this >= SLOW_READING
    val noScripts: Boolean get() = this >= NO_SCRIPTS
    val plainPage: Boolean get() = this == PLAIN_PAGE
}

internal fun storedSoundTestStage(value: String?): SoundTestStage =
    SoundTestStage.entries.firstOrNull { it.storageValue == value } ?: SoundTestStage.NORMAL

/** How often a playing video's page is read in [SoundTestStage.SLOW_READING] and later stages. */
internal const val SOUND_TEST_SLOW_POLL_MS = 1_000L

internal fun soundTestPollIntervalMs(
    intervalMs: Long,
    stage: SoundTestStage,
): Long = if (stage.slowReading) maxOf(intervalMs, SOUND_TEST_SLOW_POLL_MS) else intervalMs

@Composable
internal fun rememberSoundTestStage(): State<SoundTestStage> {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val stage = remember(preferences) { mutableStateOf(storedSoundTestStage(preferences.getString(SOUND_TEST_PREFERENCE, null))) }
    DisposableEffect(preferences) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == SOUND_TEST_PREFERENCE) {
                    stage.value = storedSoundTestStage(preferences.getString(SOUND_TEST_PREFERENCE, null))
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        stage.value = storedSoundTestStage(preferences.getString(SOUND_TEST_PREFERENCE, null))
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return stage
}

/** More settings → App updates → Sound test (temporary). */
@Composable
internal fun SoundTestSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { studyPreferences(context) }
    val stage by rememberSoundTestStage()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.sound_test_title))
        Text(
            stringResource(R.string.sound_test_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SoundTestStage.entries.forEach { option ->
            FilterChip(
                selected = stage == option,
                onClick = {
                    SoundTestLog.app("sound test stage ${option.storageValue}")
                    preferences.edit().putString(SOUND_TEST_PREFERENCE, option.storageValue).apply()
                },
                label = { Text(stringResource(option.labelRes)) },
                modifier = Modifier.testTag("sound_test_${option.storageValue}"),
            )
        }
        Text(
            stringResource(stage.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SoundTestLogList()
    }
}
