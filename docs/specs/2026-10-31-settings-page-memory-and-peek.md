# Settings page remembers its place, peek button, slider fade, gear long-press

## Status

Implemented. Owner request 2026-10-31 in the project thread: remember the settings page's place
until the app restarts, reset it if Settings is not opened again within 3 minutes, and build all
three suggested extras (peek button, fade while dragging a slider, long-press shortcut).

## Context / problem

The full settings page (`SubtitleSettingsDialog`) covers the video. To see how a change looks,
users close it, watch, then reopen it, and every reopen started at the top with all More settings
sections closed, so they had to scroll and open the same section again. Opening the language
picker reset the scroll position too.

## Goals

- Reopening the settings page shows the same open section at the same scroll position.
- The place is forgotten when the app restarts or when the page stays closed for more than 3 minutes.
- Users can see the video without closing the page at all.

## Non-goals

- No saved preference: the place lives in memory only.
- Not remembering the inner state of nested cards (for example the AI assistant's own sections).
- No new setting to turn these off.

## User-visible behavior

- **Remember place.** Closing the page (Done, X, Back) and reopening it within 3 minutes opens the
  same More settings section scrolled to the same spot. After 3 minutes, or after the app was
  closed, it opens at the top as before. Rotating the phone keeps it (the activity handles
  orientation changes itself). Choosing a language no longer jumps back to the top.
- **Peek button.** An eye icon next to Done. Hold it and the page disappears so the video and
  subtitles behind it show; let go and the page is back. A quick tap keeps the page hidden and
  shows a "Back to settings" button at the bottom; tapping anywhere or Back returns to the page.
  TalkBack and TV remote clicks count as a tap.
- **Slider fade.** While the Text size or Portrait panel position slider is held or dragged, the
  page fades to 30% so the change shows on the video live.
- **Gear long-press.** On the subtitle panel (portrait and side-by-side), tapping the gear still
  opens the small languages popup; a long press opens the full page at Layout & format.

## Technical constraints / invariants

- User-visible text in `strings_settings.xml` for all 8 interface languages (`AppLanguageTest`, lint).
- Theme colors only; the page fades with `graphicsLayer` alpha, so no new colors.
- The dialog window's dim is cleared so a see-through page shows the video undimmed; the page
  itself is opaque, so nothing changes while not peeking.

## Proposed approach / plan

1. `ui/SettingsPageMemory.kt`: `SettingsPageMemory` (place + close time, 3-minute freshness),
   `placeOnOpen` (a shortcut's section wins), peek tap/hold rule and page alpha as small
   `internal` functions for unit tests.
2. `SubtitleSettingsDialog`: take `pageMemory` and `jumpToSection`; hoist the scroll state above
   the language picker's early return; save the place on dispose; record each section's top and
   scroll to the jump section.
3. `ui/SettingsPeek.kt`: peek state (eye button press/hold, slider interaction sources), the
   see-through window, the "Back to settings" overlay, and `PanelSettingsButton` (tap + long press).
4. `DualSubApp` keeps one `SettingsPageMemory` and passes `MoreSettingsSection.LAYOUT` on long press.

## Acceptance criteria

- [x] Reopening within 3 minutes restores section and scroll; after 3 minutes it starts at the top (`SettingsPageMemoryTest`).
- [x] Reopening the page shows the Layout slider without scrolling (`SettingsPageMemoryUiTest`).
- [x] Tapping the eye shows "Back to settings" and tapping it brings the page back (`SettingsPageMemoryUiTest`).
- [x] Gear tap and long press call different actions (`SettingsPageMemoryUiTest`).
- [ ] On a phone: hold the eye and drag Text size over a playing video; the subtitles show through.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Unit tests | `testDebugUnitTest` incl. `SettingsPageMemoryTest` passes | Cloud container |
| Android lint/build | `formatCheck complexityCheck lintDebug assembleDebug assembleDebugAndroidTest` pass | Cloud container |
| Managed-device/emulator | `SettingsPageMemoryUiTest` passes | PR CI (no KVM in the cloud container) |
| Physical-device/manual | Peek and slider fade show the video behind the page | Owner's phone, preview APK |

## Risks / edge cases

- Nested cards that change height (for example AI assistant subsections) may shift the restored
  offset slightly; the offset is clamped to the page height.
- While the page is see-through the video cannot be tapped (the dialog still covers it); that keeps
  Back and the overlay simple.

## Release intent

`release:patch`: changes the APK, a small user-facing improvement (owner default for APK changes).

## Implementation result

As planned. The subtitle panel's detekt `LongMethod` baseline entry was updated for its new
`onLongPressSettings` parameter (the function got shorter: 125 to 108 lines).

## Validation result

- Passed in the cloud container: `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` (`SettingsPageMemoryTest` 7/7).
- Not run locally: `SettingsPageMemoryUiTest` (no KVM); it runs in PR CI.
- Not yet checked on a phone: peek and slider fade over a playing video. See the PR for final-head CI.
