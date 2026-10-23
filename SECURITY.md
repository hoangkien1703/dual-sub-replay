# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue:
[open a private report](https://github.com/hoangkien1703/dual-sub-replay/security/advisories/new)
on GitHub (Security tab > Report a vulnerability).

Include the app version (Android Settings > Apps > DualSub Replay shows it), your Android version, and the steps
that show the problem. You should get an answer within a week.

## Supported versions

Only the latest release on the [Releases page](https://github.com/hoangkien1703/dual-sub-replay/releases/latest)
gets fixes. The app can update itself from its menu (Check for updates).

## What the app already does

- Updates download only over HTTPS from GitHub, must match the release's SHA-256 checksum, and
  Android refuses any APK not signed with the app's own key.
- AI service keys are encrypted with the Android Keystore and left out of backups.
- The YouTube page has no JavaScript bridge into the app, and cleartext HTTP is off, except to
  `127.0.0.1` (AnkiConnect on the same phone, used only when Send to is set to AnkiConnect).
- The AI assistant asks before changing settings after it read subtitles, web pages or files,
  unless the user turned on Full access.
