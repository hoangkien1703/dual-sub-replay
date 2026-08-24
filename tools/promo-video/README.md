# DualSub Replay promotional video

This Remotion project reproduces the repository's promotional media with pinned dependencies and tool-local FFmpeg binaries.

## Build

From this directory:

```powershell
npm ci
npm run preprocess -- "C:\Users\hoang\Downloads\video_2026-08-24_12-22-09.mp4"
npm run render
npm run validate
npm run validate:links
```

`preprocess` creates the silent, normalized source segments retained in `public/source`. `render` creates the 30-second MP4, README GIF, poster, social card, and an ignored QA contact sheet. `validate` checks duration, codec, frame rate, pixel format, dimensions, audio removal, fast-start metadata, and asset-size limits. `validate:links` checks every local and remote link or image referenced by the README.

Generated caches, `node_modules`, and QA output stay outside version control.
