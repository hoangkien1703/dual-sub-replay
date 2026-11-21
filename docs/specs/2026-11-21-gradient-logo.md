# Gradient logo and accent touches (2026-11-21)

## Status

Approved for implementation (owner picks 2026-11-21 in the "Publish the app on Google Play" thread:
logo "G", theme "option 1, accents only", drawer header kept as it was). Merge and release stay the
owner's call.

## Context / problem

Before the app goes to Google Play, the owner wants a fresher logo. Samples compared a "D" mark and
other shapes; the owner kept the current logo (dark player plate with a cyan play triangle) and only
changed its background: the current cyan with sapphire blue (#0F52BA) fading in from the bottom-left
corner, an idea taken from the gradient style of well-known app icons. Cyan stays the main colour;
the blue is a touch, and everything must stay easy to read.

## Goals

- Launcher icon, in-app logo square, subtitle bubble, site favicon and README logo show logo "G".
- The playing subtitle line's outline and play button, and the CC badge in the subtitle panel,
  use the same gradient.

## Non-goals

- No change to the drawer header layout or colours (owner: "I want the old way, change the logo only").
- No gradient behind text, screen backgrounds, cards, subtitles or word colours.
- No new setting, string or permission.

## User-visible behavior

With the default cyan accent, the places above show cyan with a sapphire corner at the bottom left.
With any other accent the person picked in Settings, they stay that accent's flat colour, as before.
The launcher icon is the same for everyone.

## Technical constraints / invariants

- UI conventions: colours come from the theme. The sapphire is defined once, in
  `ui/theme/BrandGradient.kt`, and `DualSubTheme` provides it (`LocalBrandCorner`) only for the
  default accent; everything else reads `brandColors()`.
- Adaptive icon: a launcher shows the middle 72 of 108, so the gradient's centre sits at that
  area's bottom-left corner (18, 90), radius 63.

## Proposed approach / plan

1. `BrandGradient.kt`: `BrandColors`, a self-sizing radial `BrandBrush`, `brandBackground` and
   `brandTint` modifiers.
2. `Theme.kt`: provide the sapphire for the cyan accent.
3. Use it in `AppNavigation.kt` (logo square), `SubtitleCard.kt` (playing line outline and play
   button), `DualSubApp.kt` (CC badge, both panel headers), `MovableSubtitleFab.kt` (bubble).
4. `res/drawable/ic_launcher_background.xml` for the adaptive icon; `site/favicon.svg` (README uses it).

## Acceptance criteria

- [ ] Default accent, dark and light themes: the drawer logo, playing line outline and play button,
      CC badge and subtitle bubble show the cyan-to-sapphire gradient; text on screens is unchanged.
- [ ] Another accent (e.g. Amber): those places are flat amber, as before.
- [ ] The launcher icon shows the sapphire corner inside the visible area.
- [ ] Existing UI tests pass.

## Validation plan

| Category | Command/scenario and expected result | Environment / applicability |
| --- | --- | --- |
| Android lint/build | `formatCheck complexityCheck testDebugUnitTest lintDebug assembleDebug` pass | PR CI `verify-build` |
| Managed-device/emulator | UI tests pass; saved UI evidence screenshots checked by eye | PR CI `managed-device-tests` |
| Physical-device/manual | Owner installs the preview, checks launcher icon, drawer, playing line, bubble | Owner's phone |

## Risks / edge cases

- Launchers that crop icons differently show slightly more or less of the sapphire corner.
- Dark text never sits on the gradient, so contrast does not change.
