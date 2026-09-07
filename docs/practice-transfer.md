# Practice import and export

Open Practice's collection view and choose **Export** or **Import**. Android's
system picker chooses the document; the app requests no storage permission.

Use **JSON backup** to preserve saved words, optional readings, translations,
example sentences, YouTube references, timing, and spaced-repetition due dates
and intervals. Version 1 contains `format: "DualSubReplay"`, `version: 1`, and a
`words` array. Video files and browsing sessions are excluded. Keep this file
somewhere you control; it contains your saved learning content.

Use **Anki TSV** for UTF-8 text interoperability. The export supplies tab separator,
HTML-disabled, and column headers, quotes every field, doubles embedded quotes,
and supports tabs/newlines inside quoted fields. Its ten fields are word, meaning,
reading, word language, meaning language, example, translated example, YouTube
source, start milliseconds, and end milliseconds. In Anki, select a note type
with the fields you want and map the import columns. Two-field Basic notes can
map word and meaning and ignore the rest. See [Anki's text import documentation](https://docs.ankiweb.net/importing/text-files.html).

For generic TSV, import defaults to column 1 as word and column 2 as meaning.
Map other columns in the review dialog and set fallback language tags (for
example `en`, `vi`, or `ja`) when the file lacks them. The optional source must
be a YouTube URL or video ID. TSV does not transfer Anki scheduling, media, or
card templates. Replacing an existing word from TSV preserves its current
DualSub review schedule; a JSON replacement restores the backup's schedule.
Anki's plain-text export can flatten embedded line breaks into spaces.

The preview lists valid, invalid, and duplicate rows before any database write.
Valid includes duplicates. Keep-existing is the default; replacement is explicit.
JSON matches IDs; TSV matches normalized word and language pair plus source
context (video, timing, example). Repeating an unchanged import adds no copies.
Within one file, keep-existing uses the first duplicate; replacement uses the
last. Valid records commit in one transaction, while invalid rows are skipped.
Cancellation before commit or a database failure rolls the transaction back.
Files are limited to 8 MiB and 20,000 records; unsupported JSON versions fail
without modifying Practice.
