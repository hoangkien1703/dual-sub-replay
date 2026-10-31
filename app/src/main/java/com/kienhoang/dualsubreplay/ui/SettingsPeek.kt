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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.DialogWindowProvider
import com.kienhoang.dualsubreplay.R
import kotlin.math.roundToInt

/**
 * The settings page's see-through state. Holding the eye button hides the page until it is let
 * go; a quick tap keeps it hidden until [SettingsPeekBackOverlay] or Back is tapped.
 */
internal class SettingsPeekState {
    var locked by mutableStateOf(false)
    var buttonHeld by mutableStateOf(false)
    val buttonInteraction = MutableInteractionSource()

    /** The sliders that change how the subtitles look: holding one hides the page but that slider. */
    val fontScaleSlider = PeekSlider(this)
    val panelPositionSlider = PeekSlider(this)

    /** The slider being held, shown alone on [HeldSliderCard] while the page is hidden. */
    var heldSlider by mutableStateOf<HeldSlider?>(null)
    private var pressedAtMillis: Long? = null

    val pageAlpha: Float get() = settingsPageAlpha(peeking = locked || buttonHeld, sliderHeld = heldSlider != null)

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
    return state
}

/** A settings slider that hides the page while it is held or dragged. */
internal class PeekSlider(
    private val peek: SettingsPeekState,
) {
    val interaction = MutableInteractionSource()

    /** Where the slider sits on the page, unclipped, so [HeldSliderCard] can cover the same spot. */
    var boundsInRoot by mutableStateOf(Rect.Zero)

    fun report(held: HeldSlider?) {
        if (held != null) {
            peek.heldSlider = held
        } else if (peek.heldSlider?.slider === this) {
            peek.heldSlider = null
        }
    }
}

/** What [HeldSliderCard] draws in place of the hidden page: the held slider, its label and value. */
internal data class HeldSlider(
    val slider: PeekSlider,
    val label: String,
    val value: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val steps: Int,
)

/**
 * Put on a settings [androidx.compose.material3.Slider] that also takes [PeekSlider.interaction]:
 * while it is held, the page hides and [HeldSliderCard] shows [label] and the slider in its place.
 */
@Composable
internal fun Modifier.peekWhileHeld(
    slider: PeekSlider?,
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Modifier {
    if (slider == null) return this
    val held = slider.interaction.isHeld()
    SideEffect { slider.report(if (held) HeldSlider(slider, label, value, valueRange, steps) else null) }
    return onGloballyPositioned { slider.boundsInRoot = Rect(it.positionInRoot(), it.size.toSize()) }
}

/**
 * The held slider alone on a solid card over the video, where the slider sits on the hidden page,
 * like Android's brightness slider. It only shows the value: the hidden slider keeps the touch.
 */
@Composable
internal fun HeldSliderCard(held: HeldSlider) {
    val density = LocalDensity.current
    val padding = 12.dp
    val labelHeight = 24.dp
    val bounds = held.slider.boundsInRoot
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            (bounds.left - padding.toPx()).roundToInt(),
                            (bounds.top - padding.toPx() - labelHeight.toPx()).roundToInt(),
                        )
                    }.width(with(density) { bounds.width.toDp() } + padding * 2)
                    .testTag("held_slider_card"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = padding, end = padding, top = padding)) {
                Text(held.label, modifier = Modifier.height(labelHeight), fontWeight = FontWeight.Medium, maxLines = 1)
                Slider(
                    value = held.value,
                    onValueChange = {},
                    valueRange = held.valueRange,
                    steps = held.steps,
                    modifier = Modifier.height(with(density) { bounds.height.toDp() }),
                )
            }
        }
    }
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
