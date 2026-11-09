package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiDroidNotesTest {
    private fun word(
        id: String,
        text: String = "猫",
        videoId: String? = "abcdefghijk",
    ) = SavedWord(
        id = id,
        word = text,
        reading = "ねこ",
        wordLanguage = "ja",
        meaningLanguage = "en",
        meaning = "cat <animal>",
        sentence = "猫が好き & 犬も",
        translatedSentence = "I like cats\nand dogs",
        videoId = videoId,
        startMs = 83_400,
        endMs = 85_000,
        translated = true,
    )

    @Test
    fun fieldsAreEscapedHtmlInTheNoteTypesOrder() {
        assertEquals(
            listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source", "Word audio", "Sentence audio"),
            ANKI_FIELDS,
        )
        assertEquals(
            listOf(
                "猫",
                "cat &lt;animal&gt;",
                "ねこ",
                "<b>猫</b>が好き &amp; 犬も",
                "I like cats<br>and dogs",
                "<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=83s\">YouTube 1:23</a>",
                "",
                "",
            ),
            ankiNoteFields(word("1")),
        )
        // A field never holds Anki's own separator, so a note cannot gain or lose a field.
        assertEquals(ANKI_FIELDS.size, ankiNoteFields(word("1")).joinToString(ANKI_FIELD_SEPARATOR).split(ANKI_FIELD_SEPARATOR).size)
    }

    @Test
    fun theSentenceBoldsTheFirstMatchIgnoringCase() {
        assertEquals("<b>Run</b>, run &quot;fast&quot;", ankiSentenceHtml("Run, run \"fast\"", "run"))
        assertEquals("no match here", ankiSentenceHtml("no match here", "cat"))
        assertEquals("it&#39;s", ankiSentenceHtml("it's", ""))
    }

    @Test
    fun theSourceLinksOnlyToAYouTubeVideoId() {
        assertEquals("", ankiSourceLink(null, 0))
        assertEquals("", ankiSourceLink("\"><script>", 0))
        assertEquals("<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=0s\">YouTube 0:00</a>", ankiSourceLink("abcdefghijk", -5))
        assertEquals("", ankiNoteFields(word("2", videoId = null))[5])
    }

    @Test
    fun wordsSentBeforeAreNotSentAgain() {
        val words = listOf(word("1"), word("2"), word("1"), word("3"))
        assertEquals(listOf("2"), unsentAnkiWords(words) { it == "1" || it == "3" }.map { it.id })
        assertEquals(listOf("1", "2", "3"), unsentAnkiWords(words) { false }.map { it.id })
    }

    @Test
    fun soundsGoInTheirOwnFieldsAndTheTemplatesPlayThem() {
        val fields = ankiNoteFields(word("1"), CardSounds(word = "[sound:w.mp3]", sentence = "[sound:s.m4a]"))
        assertEquals("[sound:w.mp3]", fields[ANKI_FIELDS.indexOf(ANKI_WORD_AUDIO_FIELD)])
        assertEquals("[sound:s.m4a]", fields[ANKI_FIELDS.indexOf(ANKI_SENTENCE_AUDIO_FIELD)])
        // The word's sound plays on the front, the sentence's on the back.
        assertEquals(true, "{{Word audio}}" in ANKI_QUESTION_FORMAT)
        assertEquals(false, "{{Sentence audio}}" in ANKI_QUESTION_FORMAT)
        assertEquals(true, "{{Sentence audio}}" in ANKI_ANSWER_FORMAT)
        // A new name: AnkiDroid can't add fields to the old "DualSub Replay" type.
        assertEquals("DualSub Replay audio", ANKI_NOTE_TYPE_NAME)
    }

    @Test
    fun aSoundFieldNamesOnlyAPlainMediaFile() {
        assertEquals("[sound:dualsub_word-1.mp3]", ankiSound("/dualsub_word-1.mp3"))
        assertEquals("[sound:a_123.m4a]", ankiSound(" a_123.m4a "))
        assertEquals("", ankiSound(null))
        assertEquals("", ankiSound(""))
        assertEquals("", ankiSound("x].mp3"))
        assertEquals("", ankiSound("dir/x.mp3"))
        assertEquals("", ankiSound("x\n.mp3"))
    }

    @Test
    fun aWaitingCardChangesOnlyItsSentenceAudioField() {
        val fields = ankiNoteFields(word("1"))
        val updated = ankiFieldsWith(fields, ANKI_SENTENCE_AUDIO_FIELD, "[sound:s.m4a]")!!
        assertEquals(fields.dropLast(1), updated.dropLast(1))
        assertEquals("[sound:s.m4a]", updated.last())
        // A note of another type (fields changed by the learner) is left alone.
        assertNull(ankiFieldsWith(fields.dropLast(1), ANKI_SENTENCE_AUDIO_FIELD, "[sound:s.m4a]"))
        assertNull(ankiFieldsWith(fields, "Back", "[sound:s.m4a]"))
    }
}
