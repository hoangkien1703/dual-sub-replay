# Browser extension preview zip

## Status

Implemented. Owner pick 2026-11-24: publish a ready-to-load zip so people can try the extension without git,
before announcing it in a Discussion.

## Context / problem

The Chrome extension (`browser-extension/`, #261) loads with Load unpacked. Without a clone, the only way to get the
folder was the whole repository ZIP (about 37 MB).

## Goals

- A stable link, `releases/download/extension-preview/DualSub-Chrome-extension.zip`, that always holds the
  extension from the latest `main` (about 2 MB, tests left out).

## Non-goals

- Chrome Web Store publishing (a later owner decision).
- A packed `.crx` (Chrome blocks installing those from outside the store).

## Technical constraints / invariants

- Runs only on `main` (push or a manual run), after `extension-tests` passes; PR runs never publish.
- `permissions: contents: write` only on the publish job; checkout without persisted credentials; no third-party action.
- The release is a **pre-release never marked latest**: the app's updater reads only `releases/latest` and the
  `preview` tag (`data/AppUpdates.kt`), `android.yml` compares APK size with `releases/latest`, and
  `tools/automatic_release.py` only counts `vX.Y.Z` tags, so none of them sees it.
- `--clobber` replaces the one asset; the release notes say which `main` commit built it.

## Acceptance criteria

- [ ] After this merges, the `Browser extension checks` run on `main` creates the `extension-preview` pre-release
      with `DualSub-Chrome-extension.zip`, and `releases/latest` is still the newest `vX.Y.Z` app release.
- [x] Locally, the zip step on the current folder gives 42 files, about 1.9 MB, with `manifest.json` at
      `browser-extension/manifest.json`; the notes text renders as intended.

## Release intent

`release:skip`: workflow and docs only.
