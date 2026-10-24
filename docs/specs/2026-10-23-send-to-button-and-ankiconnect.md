# One Send to button, with AnkiConnect

## Status

Implemented (owner request and design 2026-10-23 in the "AnkiConnect and Send to button" project
thread); waiting for the owner's phone test.

## Context / problem

A Reddit user (developer of a flashcard tool) asked for AnkiConnect support, so saved words can
reach tools that speak the AnkiConnect protocol (Yomitan, asbplayer and others). On Android that
means the local AnkiConnect server at `http://127.0.0.1:8765`, served by the "AnkiConnect
Android" app. The app already had Send to AnkiDroid (Settings → Study tools, [study tools
spec](2026-10-20-study-tools-from-migaku-and-jidoujisho.md)).

The owner does not want a separate button per app: "a button "send to" with a list of choices,
with AnkiDroid the default and other options like AnkiConnect. If users choose another option
than the default, the app remembers it and shows it on that button, with a list to choose again."

## Goals

- One split **Send to** button on the word card: the main part names the app in use and sends
  there; ▾ opens a list of apps (AnkiDroid, AnkiConnect) with a check by the one in use.
- Picking an app from the list remembers it (the button then names it) and sends the word there
  at once. AnkiDroid is the default.
- Practice → Export's "Send all to …" uses the same choice and the same ▾ list.
- Settings → Study tools keeps one switch (now "Send to flashcard apps") and adds "Send words to"
  (the same remembered choice) and, for AnkiConnect, a deck choice (Automatic by default).
- No setup needed beyond opening AnkiConnect Android once.

## Non-goals

- An address or port setting, or sending to AnkiConnect on another computer (that would need
  cleartext to any address).
- Field mapping for the user's own note types, audio or pictures on cards.
- Any AnkiConnect code (AnkiConnect Android is GPL-3.0); only the public JSON protocol is used.

## User-visible behavior

- Word card: `[🗂 Send to AnkiDroid][▾]`. After sending: "Sent to AnkiDroid" (disabled); ▾ still
  works, so the word can go to the other app too. Picking AnkiConnect in the list sends and the
  button reads "Send to AnkiConnect" from then on, on every card.
- AnkiConnect not answering: "Nothing answered at AnkiConnect's address. Open AnkiConnect Android
  (or your AnkiConnect tool), check that its server is on, then try again." with **Open
  AnkiConnect Android** when that app is installed. A refusal shows AnkiConnect's own first line.
- Send to shows when the switch is on and AnkiDroid is installed, or when AnkiConnect is the
  chosen app (so phones without AnkiDroid can use it after picking AnkiConnect in Settings).
- Cards: desktop-like AnkiConnect servers get the DualSub Replay deck and note type (made with
  `createDeck` / `createModel`); AnkiConnect Android cannot make either, so cards go to the chosen
  deck, else DualSub Replay if the user made one, else Default, using Anki's Basic note type with
  the word and sentence on the front and reading, meaning, translation and source on the back.
- The AI assistant can read and change `send_to_app` (ankidroid / ankiconnect).

## Technical constraints / invariants

- Only `http://127.0.0.1:8765` is ever asked, with no proxy. `res/xml/network_security_config.xml`
  keeps cleartext off everywhere (base config) and allows it only for `127.0.0.1`.
- No new permission. `<queries>` gains `com.kamwithk.ankiconnectandroid` (to offer opening it).
- Each app keeps its own sent list (`ankidroid_sent`, `ankiconnect_sent` preferences), so a word is
  not sent twice to the same app; AnkiConnect's duplicate refusal also counts as sent before.
- New preferences `send_to_app` and `ankiconnect_deck` are Study tools settings (Reset all
  settings clears them).
- All text in `strings_study.xml` in 8 languages; app names are untranslatable strings.

## Proposed approach / plan

1. `data/AnkiConnectNotes.kt`: request/reply helpers, deck and note-type choice as small
   internal functions, `send(words, chosenDeck)` returning the existing `AnkiSendResult` (new
   `NOT_RUNNING` problem and `detail` text). Transport injected for unit tests.
2. `ui/StudySettings.kt`: `FlashcardApp` enum, the two preferences.
3. `ui/StudyTools.kt`: `StudyTools` sends by app; `SendToButton` (split chip + `SendToMenu`),
   `AnkiSendAllButton` with the same menu; problem text per app.
4. `ui/StudyToolsSettings.kt`: "Send words to" chips, per-app hint, AnkiConnect deck dialog.
5. `AiSetting.SEND_TO_APP`, manifest, network security config, strings, docs.

## Acceptance criteria

- One Send to button with ▾ on the word card; picking an app remembers it and the button names it.
- Default is AnkiDroid; AnkiDroid behavior (permission, deck, Open AnkiDroid) is unchanged.
- With AnkiConnect Android running, a word arrives as a card; with nothing running, the card says
  so and offers to open AnkiConnect Android.
- Cleartext is allowed only to `127.0.0.1`.

## Validation plan

- Unit tests: `AnkiConnectNotesTest` (protocol, deck/note-type fallbacks, desktop vs Android
  servers, duplicates, not running, refusal text), AI setting tests.
- Instrumented: `StudyToolsUiTest` for the split button, its list and the settings rows, with
  screenshots.
- Owner phone test with AnkiDroid and AnkiConnect Android.

## Risks / edge cases

- AnkiConnect Android must be opened once (its server is a foreground service).
- A user who made their own "DualSub Replay" note type with other fields gets the front/back layout.
- Deck names are listed only while AnkiConnect answers; otherwise the dialog says nothing answered.

## Release intent

Patch (`release:patch`), as the owner's thread brief asked.

## Implementation result

Implemented as planned.

## Validation result

To be filled after CI and the owner's phone test. Not verified in the cloud: a real AnkiConnect
Android server on a phone.
