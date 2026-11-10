# Pictures and a card front choice for Anki cards

## Status

Implemented, waiting for CI and a phone test. Owner picked option A ("Picture + Card front picker") on 2026-11-10 after discussing lion737's request in discussion #81. Same day: the picture is off by default (owner), and the word card gained the meaning in the sentence (owner pick A, "Meaning in context first", off by default even with a key). Merge and release need the owner's OK.

## Context / problem

lion737 (discussion #81, comment 19515473, 2026-11-10) asked for Anki export "like Migaku":

1. **Image support:** cards sent to Anki should include an image.
2. **Original sentence:** the source sentence on the card, with a setting to turn it on or off.
3. **Settings parity:** "all card-creation settings available in Migaku".

Their screenshots show Migaku's card (word, sentence, two sound buttons, definition, then a frame of the video) and its Card type picker: Word, Sentence, Audio Word, Audio Sentence.

What we already had (v1.8.30): the sentence is on the front of every card with the word in bold, plus word sound, sentence sound, meaning, reading, translation, a YouTube link, a deck picker (AnkiConnect) and the `DualSubReplay` tag. So "sentence on/off" is really about what the front shows, which is Migaku's card type.

What we checked before building (2026-11-10):

- A canvas can draw the YouTube page video and read it back: the video is a Media Source `blob:` video, so it is same-origin for the page. On www.youtube.com in desktop Chromium, `drawImage(video)` + `toDataURL('image/jpeg', 0.8)` gave a real frame (320 × 240 video, 21 KB) with no permission.
- AnkiDroid's provider takes any media file (`content://com.ichi2.anki.flashcards/media`) and lets an app change a note type's card template (`models/<id>/templates/<ord>`, already used when the note type is made). It can't add fields to a note type or change its CSS.
- Desktop AnkiConnect has `updateModelTemplates`; AnkiConnect Android doesn't (it answers unknown actions with its version text) and can't make note types.

## Goals

- **Picture:** Settings → Flashcards & Anki → **Add a picture to cards** (off by default, owner's pick 2026-11-10). A still the page takes from its own video while the word's line is on screen; when there is none, the video's 320 × 180 thumbnail from `i.ytimg.com`. It sits on the back, above the YouTube link.
- **Card front:** Settings → Flashcards & Anki → **Card front**, one choice instead of a sentence switch and Migaku's settings: *Word and sentence* (default, today's card), *Word* (sentence moves to the back), *Sentence* (word in bold; word and meaning on the back), *Listening* (only the line's sound; until the sound arrives the sentence shows, so Anki never makes an empty card).
- **Waiting sound notice:** when a card goes without its sentence's sound (the line was paused part-way, because tapping a word pauses the video), the word card says "The sentence's sound is added once the line has played to the end." Send all says how many cards wait.

- **Meaning in this sentence (AI)** (lion737, comments 19516234, 19516518 and 19520846): Settings → Word learning → **Meaning in this sentence (AI)**, off by default even with a key (owner). When on, a tapped word's card asks the AI assistant's service for the word's meaning as used in its sentence ("go" in "I go to school" is "gedirəm" in Azerbaijani, the dictionary says "getmək") and shows it first, with the dictionary meaning under it. No "Translation order" setting (owner pick): the meaning in the sentence is what people tap for. The saved meaning, and so Practice and Anki's Meaning field, is "gedirəm (getmək)".

## Non-goals

- Not every Migaku setting (owner agreed): no card editor before each send and no combining lines. We ask lion737 which settings they still miss after this.
- No Audio Word front (a word sound alone, with nothing to read, is a weak card).
- No picture for words saved from a line the video never showed on this phone, except the thumbnail.
- No new note type: the picture goes in the Source field (AnkiDroid can't add a field to the type people already have).

## User-visible behavior

- Flashcards & Anki (shown while Send to is on): Add audio to cards, **Add a picture to cards**, **Card front** (four chips and a line saying where things go and "Changing this also changes cards you sent before.").
- The word card's Send to: after sending, the notice above shows under the button when the card waits for its sentence's sound.
- Practice → Export → Send all: "N words sent to AnkiDroid. N cards get their sentence's sound once their lines have played."
- Changing Card front changes the template of the "DualSub Replay audio" note type the next time a card is sent, so older cards change too, like Anki's own card types. A template the learner edited in Anki is only replaced when they pick another front.
- AnkiConnect Android: cards use Basic, so the front choice decides which fields go in Front and Back; the picture is on the back.
- The AI assistant can change both settings (`anki_card_picture`, `anki_card_front`) and `meaning_in_context`.
- Word card with Meaning in this sentence on: the label reads "In this sentence (language)", the meaning in the sentence is large and bold, and "Dictionary: …" is under it. While the AI answers, "Asking AI for the meaning in this sentence…" shows under the dictionary meaning. Without a key the card says "Meaning in this sentence needs an AI key: More settings → AI assistant." When the AI fails, times out (20 s) or answers with the dictionary meaning, the card stays as before. The pencil edits the saved text, and the edit then is the one meaning.

## Technical constraints / invariants

- One WebView. `webLinePictureScript` checks `https:` + `*.youtube.com` and the page's video id, skips ads and times outside the line, draws the video into its own canvas and returns a JPEG data URL at most 640 px wide. It never changes the video or the page. A black frame counts as unreadable (thumbnail instead).
- The page loop (`LineAudioPageScripts`) also takes a still of every saved word's line while the video shows it (line start to end + 400 ms), and Send to takes one at once when the video is on the line.
- Stills and thumbnails live in `files/line-pictures/` (`<videoId>-<startMs>-<endMs>.jpg`, `<videoId>-thumbnail.jpg`), at most 1.5 MB each and JPEG only; ones no saved word needs are deleted after a day. The folder is left out of cloud backups and shared with AnkiDroid through the existing `${applicationId}.cardaudio` FileProvider (now also `line-pictures/`).
- The thumbnail comes only from `https://i.ytimg.com/vi/<id>/mqdefault.jpg` (id checked).
- The front last set is kept per note type (`ankidroid_card_front`, `ankiconnect_card_front` preferences). With nothing kept, the template counts as Word and sentence.
- Meaning in this sentence sends only the tapped word, its sentence (the whole sentence when the row is part of one; the translated sentence when the word was tapped in the translation line) and the two language names, with the AI translation model (thinking off), and keeps answers for 200 words in memory for the app run. Nothing is sent while the switch is off, for a saved word reopened, or without a key. A reply longer than 80 characters is not a meaning and is dropped.
- All new text in all 9 interface languages.

## Plan

1. Spec (this file).
2. `data/AnkiCardLayout.kt`: `CardFront`, `CardOptions`, templates per front, `ankiPicture`, front memory.
3. `data/LinePicture.kt`: store, parsing, thumbnail address.
4. Senders: `AnkiCardMedia.files(word, options)`, picture in Source, template update, `AnkiSendResult.waiting`.
5. Page: `webLinePictureScript`, `LinePictureBridge`, stills in the page loop, `CardMediaSource` with thumbnail fallback.
6. Settings, search, assistant, strings, notice.
7. Tests.

## Validation

- Unit (meaning in this sentence): the sentence picked (whole sentence, translated line, none), the request text, reply cleaning, the saved "in sentence (dictionary)" text. Compose: the card shows both meanings and saves them, editing turns them into one, the no-key note, and the switch is off by default.
- Unit: templates per front (every field on every card, front holds only its own), template update only on a new pick, picture field escaping, Source field with picture and link, Basic card sides per front, AnkiConnect template update once (desktop) and not on Android, waiting count, still parsing, store and prune, script origin and id checks, settings gating.
- Real page (2026-11-10): the still script's drawing code read a frame from www.youtube.com in desktop Chromium. **Not yet checked in the Android WebView on a phone.**
- Phone (owner): send a word with each front and check the card in AnkiDroid; check the picture shows on the back; send while paused mid-line and see the notice, then play past the line and check the sound arrives.
