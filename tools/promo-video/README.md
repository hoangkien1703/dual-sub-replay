# DualSub Replay promotional video

This Remotion project renders the repository's promotional media with pinned dependencies, bundled fonts and tool-local FFmpeg binaries.

## What it renders

| Output | Composition | Use |
| --- | --- | --- |
| `docs/media/dualsub-replay-promo.mp4` | `PromoVideo`, 1080x1920, 51 s, silent | Demo linked from the README and launch posts |
| `docs/images/dualsub-replay-demo.gif` | `ReadmeLoop`, 14 s at 12 fps, 400 px wide | Inline README preview |
| `docs/images/dualsub-replay-promo-poster.jpg` | `PromoPoster` | Video poster / vertical thumbnail |
| `docs/images/dualsub-replay-social-preview.png` | `SocialCard`, 1280x640 | GitHub and website social preview |

The video runs: intro, dual subtitles, tap-to-replay, word lookup with grammar and Save to vocabulary, Practice, landscape fullscreen, Progress, and an end card.

- **Recorded scenes** (dual subtitles, replay, fullscreen) use real phone footage in `public/source`, cut from the owner's 2026-08-24 screen recording by `preprocess`.
- **Recreated scenes** (word lookup, Practice, Progress) postdate that recording. `src/AppScreens.tsx` rebuilds those screens in HTML with the app's own strings (`app/src/main/res/values/strings_*.xml`) and colors (`ui/theme/Theme.kt`), on top of a real app screenshot cropped by `prepare:stills`. When the UI changes, update the matching scene, or replace it with fresh footage.

## Build

From this directory:

```powershell
npm ci
npm run preprocess -- "C:\Users\hoang\Downloads\video_2026-08-24_12-22-09.mp4"   # only when replacing footage
npm run render
npm run validate
npm run validate:links
```

`render` typechecks, crops the stills, renders every composition, then re-encodes the MP4 with fast-start metadata, builds the GIF, and writes an ignored QA contact sheet to `out/`. `validate` checks duration, codec, frame rate, pixel format, dimensions, audio removal, fast-start metadata, and asset-size limits. `validate:links` checks every local and remote link or image referenced by the README.

Remotion downloads its own headless Chrome on first use. To use an installed one instead (for example on a container without internet access to Google's CDN), append `-- --browser-executable=/path/to/headless_shell` to each `render:*` script.

Generated caches, `node_modules`, and QA output stay outside version control.
