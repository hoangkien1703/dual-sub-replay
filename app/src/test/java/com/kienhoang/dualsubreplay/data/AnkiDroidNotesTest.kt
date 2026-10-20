package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
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
        assertEquals(listOf("Word", "Meaning", "Reading", "Sentence", "Translation", "Source"), ANKI_FIELDS)
        assertEquals(
            listOf(
                "猫",
                "cat &lt;animal&gt;",
                "ねこ",
                "<b>猫</b>が好き &amp; 犬も",
                "I like cats<br>and dogs",
                "<a href=\"https://www.youtube.com/watch?v=abcdefghijk&amp;t=83s\">YouTube 1:23</a>",
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
        assertEquals("", ankiNoteFields(word("2", videoId = null)).last())
    }

    @Test
    fun wordsSentBeforeAreNotSentAgain() {
        val words = listOf(word("1"), word("2"), word("1"), word("3"))
        assertEquals(listOf("2"), unsentAnkiWords(words) { it == "1" || it == "3" }.map { it.id })
        assertEquals(listOf("1", "2", "3"), unsentAnkiWords(words) { false }.map { it.id })
    }
}
