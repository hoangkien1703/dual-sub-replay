# Send the selected subtitle text to the Google Translate app

Owner request 2026-10-28. Scope: the watch-video subtitle selection popup only.

## Behavior

- The popup bar shown when a word, phrase or subtitle line is selected has an "Open in Google Translate" item after Pronounce in its ▾ list only (`phrase_menu_google_translate`); the bar itself keeps Copy, Translate and Pronounce so it stays uncluttered (owner review 2026-10-28). Nothing else in the app changes: not Practice, not saved words, not the word sheet.
- The button shares the exact selected substring with `ACTION_SEND`, MIME `text/plain`, `EXTRA_TEXT`, aimed at the package `com.google.android.apps.translate` with `NEW_TASK | CLEAR_TASK`. Blank text sends nothing. Google documents no entry point for source and target languages, so the app picks its own; none are sent.
- Before launching, `PackageManager` must show the app installed (manifest `<queries><package>` for package visibility) and at least one activity matching the intent. Failures show a localized toast: "not installed" or "could not accept the text". Success shows nothing. A web page, browser, chooser or clipboard copy is never used.
- Why `CLEAR_TASK`: with only `NEW_TASK` an already open Translate app was brought forward and did not read the new text (found on a physical device). The cost is that its previous screen is discarded.

## Acceptance criteria

1. The intent is `ACTION_SEND` / `text/plain` / exact `EXTRA_TEXT` / the Translate package.
2. Words, phrases, sentences, umlauts, ß, Persian with ZWNJ, punctuation and inner whitespace arrive unchanged.
3. Not installed, no receiver and launch failure give distinct messages; no browser opens.
4. Copy, Translate and Pronounce behave as before.

## Validation

- Unit: result classification, blank text.
- Instrumented: the real `Intent` contract; the ▾ list item sends the selected word and the bar has no Google Translate icon.
- Device (owner, physical phone): the shared text arrived with the Translate app both open and closed. Languages are not preselected.

## Release intent

Patch (default); the owner may choose otherwise before merge.
