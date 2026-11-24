# AI splits unpunctuated captions into sentences

## Status

Implemented. Contributor report 2026-11-21, after [prompt version 3](2026-11-21-browser-extension-ai-subtitle-translation.md): lines match
their own meaning better, but the start of a line is still sometimes translated wrongly, with no link to the line
before.

## Context / problem

Auto-generated captions without punctuation are cut every few seconds, not where sentences end. Joining pieces
([whole-sentence spec](2026-11-21-browser-extension-whole-sentence-translation.md)) stops at 120 characters or 8 seconds, so a long
spoken sentence still spans several lines. Any per-line translation then has to give something up:

- **Android's prompt (version 2):** the whole sentence reads naturally, but words move between lines.
- **Version 3:** each line keeps its own meaning, but a line that starts mid-sentence is translated without its
  beginning.

Tools such as Trancy re-split automatic captions into sentences with AI. The json3 word timing YouTube sends with
auto-generated captions gives each word its time, so new sentence lines can keep exact times.

## Goals

- With AI translation on and a key saved, a track with no punctuation and with word timing is split into sentences by
  the AI, in the same requests that translate it. Each line is then one sentence with its translation.
- Replay, the spoken-word highlight, word cards and English analysis keep working on the new lines.

## Non-goals

- Punctuated tracks, and tracks without word timing: they keep the current lines and the per-line AI translation
  (version 3).
- Google-only mode: Google cannot split sentences.

## User-visible behavior

- At first, lines and Google translations show as before. As each AI request answers, that part of the video is
  redrawn as whole sentences with the AI's translation, starting with the part being played. The status line shows
  "AI translating…", then "N sentences · AI translation".
- A sentence longer than about 25 words is split at a clause boundary by the AI, so a line stays readable.
- If the AI fails or is busy, the part it has not reached keeps the lines and Google translations shown before. Busy
  services are retried after 1 s, 3 s, then a 30 s rest, as before.

## Technical constraints / invariants

- [Network rule](../../AGENTS.md#browser-extension): only the background talks to the AI service.
- The words are the captions' own: a line's text comes from the AI only when it has the same words as the line's
  range (letters compared, ignoring case and punctuation). Otherwise the original words are joined and capitalized.
  This keeps the spoken-word highlight and stops the AI from changing what was said.
- The cache key carries the segmentation prompt's own version, the service, model, languages and the chunk's words.

## Proposed approach / plan

1. `translation/ai.js`:
   - `captionWords(pieces)`: the track's timed words.
   - `isUnpunctuated(pieces)`: true when fewer than 20% of pieces end a sentence.
   - `wordChunks(words)`: chunks of at most 300 words, each cut at the longest pause among its last 60 words.
   - `aiSegmentPrompt`, `aiSegmentRequest`: the numbered words, plus the 3 previous sentences as context.
   - `parseSegmentReply`: sentence start numbers, increasing and inside the chunk, with text and translation; the
     first always starts the chunk.
   - `segmentLines`: lines with word times and the end at the next sentence's start.
2. `background.js`: an `aiSegment` request, using the same service, retries and cache as `aiTranslate`.
3. `ui/content.js`: when the conditions hold, the AI pass segments chunks in playing-first order and replaces the
   lines in each chunk's time range. Rows are kept on their line objects, so Google's pass never writes into a
   replaced row. Replaced lines are analyzed again for English.

## Acceptance criteria

- [x] Unit tests: timed words, the unpunctuated check, chunks cut at the longest pause, a reply with fences and
      out-of-order or out-of-range starts repaired, line times and words, and a changed text falling back to the
      original words.
- [x] Headless Chrome with a fake AI: an unpunctuated 3-piece track whose sentence spans pieces becomes 2 sentence
      lines with the AI's translations and correct start times. The spoken word is still underlined.
- [ ] The contributor's real video with a real key.

## Version

0.7.0.

## Implementation result

As planned. Found while validating, and fixed: the panel's status line went back to "N lines" when the panel was
redrawn (it is now kept in state), and the spoken word was underlined only on the tick after a line was redrawn (it
is now underlined in the same tick).

## Validation result

- Unit tests: passed.
- Headless Chrome with a fake AI: passed. "so in college i was" / "a government major then" / "i wrote papers" became
  "So in college i was a government major." (0–3.6 s) and "Then i wrote papers." (3.6–6 s) with the AI's
  translations, and the status line reads "2 sentences · AI translation". Seeking to 4.5, 0.7, 2.3 and 5.0 s shows the
  right line and underlines "wrote", "college", "government" and "papers". A punctuated track still uses per-line AI
  translation.
- Real key and video: not run here.
