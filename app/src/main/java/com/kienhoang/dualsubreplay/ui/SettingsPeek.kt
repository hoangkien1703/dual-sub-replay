package com.kienhoang.dualsubreplay.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.kienhoang.dualsubreplay.R

/**
 * The settings page's see-through state. Holding the eye button hides the page until it is let
 * go; a quick tap keeps it hidden until [SettingsPeekBackOverlay] or Back is tapped.
 */
internal class SettingsPeekState {
    var locked by mutableStateOf(false)
    var buttonHeld by mutableStateOf(false)
    var sliderDragged by mutableStateOf(false)
    val buttonInteraction = MutableInteractionSource()

    /** The sliders that change how the subtitles look: dragging one fades the page. */
    val fontScaleSlider = MutableInteractionSource()
    val panelPositionSlider = MutableInteractionSource()
    private var pressedAtMillis: Long? = null

    val pageAlpha: Float get() = settingsPageAlpha(peeking = locked || buttonHeld, sliderDragged = sliderDragged)

    fun onButtonPress(nowMillis: Long) {
        pressedAtMillis = nowMillis
    }

    fun onButtonClick() {
        locked = settingsPeekLockedAfterClick(locked, pressedAtMillis, SystemClock.uptimeMillis())
        pressedAtMillis = null
    }
}

@Composable
internal fun rememberSettingsPeekState(): SettingsPeekState {
    val state = remember { SettingsPeekState() }
    LaunchedEffect(state) {
        state.buttonInteraction.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    state.onButtonPress(SystemClock.uptimeMillis())
                    state.buttonHeld = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> state.buttonHeld = false
            }
        }
    }
    val sliderHeld = state.fontScaleSlider.isHeld() || state.panelPositionSlider.isHeld()
    LaunchedEffect(sliderHeld) { state.sliderDragged = sliderHeld }
    return state
}

@Composable
private fun MutableInteractionSource.isHeld(): Boolean {
    val dragged by collectIsDraggedAsState()
    val pressed by collectIsPressedAsState()
    return dragged || pressed
}

/** Lets the video show through the settings dialog: dialogs dim what is behind them by default. */
@Composable
internal fun SeeThroughDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(window) { window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
}

/** While the page stays see-through, any tap (or the button, for TV remotes) brings it back. */
@Composable
internal fun SettingsPeekBackOverlay(onBack: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(onBack) { detectTapGestures { onBack() } }
                .safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier.padding(bottom = 24.dp).focusRequester(focusRequester).testTag("settings_peek_back"),
        ) {
            Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_peek_back))
        }
    }
}

/**
 * The gear on a subtitle panel: a tap opens the small languages popup, a long press opens the full
 * settings page at the section that panel's settings live in (owner request 2026-10-31).
 */
@Composable
internal fun PanelSettingsButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.settings_section_layout_title),
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = stringResource(R.string.player_subtitle_settings),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(iconSize),
        )
    }
}
