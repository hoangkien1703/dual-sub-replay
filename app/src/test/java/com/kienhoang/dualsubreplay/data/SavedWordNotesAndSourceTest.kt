package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Video titles and notes on saved words (ported from mrAlishah's Triple Sub Replay fork). */
class SavedWordNotesAndSourceTest {
    private fun word(
        id: String = "1",
        sourceTitle: String? = null,
        notes: String = "",
    ) = SavedWord(
        id,
        "Hund",
        null,
        "de",
        "en",
        "dog",
        "Der Hund schläft.",
        "The dog sleeps.",
        "dQw4w9WgXcQ",
        1_000,
        2_000,
        false,
        true,
        sourceTitle = sourceTitle,
        notes = notes,
    )

    @Test fun titlesAreKeptOnOneTidyLine() {
        assertEquals("Learn German - Episode 1", cleanSourceTitle("  Learn German -\n  Episode 1 "))
        assertNull(cleanSourceTitle("   "))
        assertNull(cleanSourceTitle(null))
        assertEquals(MAX_SOURCE_TITLE_LENGTH, cleanSourceTitle("x".repeat(300))!!.length)
    }

    @Test fun storageAndBackupKeepTitleAndNotes() {
        val saved = word(sourceTitle = "Easy German 1", notes = "der, plural Hunde\nsee also: Katze")

        assertEquals(saved, decodeWord(encodeWord(saved)))
        assertEquals(saved, parseTransfer(exportBackup(listOf(saved))).backup!!.single())
    }

    @Test fun olderWordsHaveNoTitleOrNotes() {
        val old = encodeWord(word()).apply { remove("sourceTitle") }

        assertNull(decodeWord(old).sourceTitle)
        assertEquals("", decodeWord(old).notes)
    }

    @Test fun tripleSubReplayBackupsBringTheirNotes() {
        val fork = encodeWord(word()).apply { put("personalNotes", "from the fork") }

        assertEquals("from the fork", decodeWord(fork).notes)
    }

    @Test fun savingAgainKeepsScheduleNotesAndTitle() {
        val old = word(sourceTitle = "Easy German 1", notes = "my note").copy(dueAt = 9, intervalMs = 7)
        val again = word(notes = "").copy(meaning = "hound")

        val merged = mergeResaved(old, again)

        assertEquals("hound", merged.meaning)
        assertEquals("my note", merged.notes)
        assertEquals("Easy German 1", merged.sourceTitle)
        assertEquals(9L, merged.dueAt)
        assertEquals(7L, merged.intervalMs)
        assertEquals("Easy German 2", mergeResaved(old, again.copy(sourceTitle = "Easy German 2")).sourceTitle)
    }

    @Test fun ankiTextCarriesTitleAndNotes() {
        val saved = word(sourceTitle = "Easy German 1", notes = "line one\nline two")

        val document = parseTransfer(exportAnkiTsv(listOf(saved)))
        val imported = previewImport(document, defaultTransferMapping(document), "de", "en", emptyList()).words.single()

        assertEquals("Easy German 1", imported.sourceTitle)
        assertEquals("line one\nline two", imported.notes)
    }

    @Test fun importRejectsHugeNotesAndUntidyTitles() {
        assertThrows(IllegalArgumentException::class.java) { validateTransferWord(word(notes = "x".repeat(MAX_WORD_NOTES_LENGTH + 1))) }
        assertThrows(IllegalArgumentException::class.java) { validateTransferWord(word(sourceTitle = " spaced ")) }
    }
}
