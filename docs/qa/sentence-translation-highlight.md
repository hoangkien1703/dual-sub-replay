# Whole-sentence translation and highlight QA

Spec: [2026-09-24-sentence-translation-and-highlight-sync](../specs/2026-09-24-sentence-translation-and-highlight-sync.md).

Offline unit tests cover segmentation, slicing, parsing and resolver rules. They cannot
establish translation quality or audio sync on a phone. Record results here.

## Pending phone checks

1. On the TED-Ed video from the 4you screenshot, with English → Vietnamese and Short paired
   phrases:
   - The lines "The French Revolution temporarily" / "stalled relocation efforts." show
     consecutive parts of one translation.
   - Compare the result with 4you.
2. Repeat step 1 with Whole sentence. A row never contains the end of one sentence plus the
   start of the next ("…price. Even at, look at").
3. With English → Vietnamese and Japanese → English auto captions, the underline follows the
   spoken word:
   - when YouTube reveals several words at once
   - in portrait, landscape split and fullscreen
   - at 1x and 1.5x
4. After a repeated common word ("the", "you"), a wrong underline corrects itself within about
   1 s, without seeking.
5. Regression checks, which should behave as before:
   - seek backward and forward
   - pause and resume
   - switching the caption format
   - Word Learning on and off
   - a manually authored caption track

## Results

- **2026-09-24, owner's phone, build `de92e85`:**
  - The underline sometimes jumped back to the previous sentence.
  - One row was too long ("Traditional LMS … this model can That is actually 40 to 200x faster.").
  - Both are addressed in the follow-up commit; this needs a re-check on the phone.
- **2026-09-24, owner's phone, build `46b3b9f`:**
  - Better, but the highlight sometimes skipped spoken words: "All of this sets a" was missing
    between "every day." and "Pretty brutal stage…".
  - Cause: overlapping caption pieces were absorbed without their text.
  - Fixed in the next commit; this needs a re-check on the phone.
