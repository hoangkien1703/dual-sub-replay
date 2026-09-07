package com.kienhoang.dualsubreplay.data

import org.junit.Assert.*
import org.junit.Test

class PracticeTransferTest {
    private val word =

        SavedWord(
            "id",
            "hello\t\"世界\"",
            null,
            "en",
            "vi",
            "xin chào\nnext",
            "a sentence",
            "một câu",
            "dQw4w9WgXcQ",
            100,
            200,
            false,
            true,
            5000,
            1000,
        )

    @Test fun writesAnkiInteroperabilityFixture() {
        val file = java.io.File("build/qa/anki-app-export.tsv")

        file.parentFile.mkdirs()

        file.writeText(exportAnkiTsv(listOf(word)))
    }

    @Test fun readsRealAnki268Export() {
        val text = requireNotNull(javaClass.getResource("/anki-26.8-export.tsv")).readText()

        val document = parseTransfer(text)

        val mapping = transferColumns.withIndex().associate { it.value to it.index }

        val result = previewImport(document, mapping, "en", "vi", emptyList())

        assertEquals(0, result.invalid)

        // Anki's plain-text exporter flattens HTML line breaks to spaces.

        assertEquals(word.word, result.words.single().word)

        assertEquals("xin chào next", result.words.single().meaning)

        assertEquals(word.videoId, result.words.single().videoId)
    }

    @Test fun quotedHashWordsAreNotTreatedAsComments() {
        val document = parseTransfer(exportAnkiTsv(listOf(word.copy(word = "#tag"))))

        assertEquals("#tag", previewImport(document, defaultTransferMapping(document), "en", "vi", emptyList()).words.single().word)
    }

    @Test fun backupPreservesEveryFieldAndReviewHistory() {
        assertEquals(listOf(word), parseTransfer(exportBackup(listOf(word))).backup)
        val emptyOptionals = word.copy(reading = "", translatedSentence = "")
        assertEquals(listOf(emptyOptionals), parseTransfer(exportBackup(listOf(emptyOptionals))).backup)
    }

    @Test fun ankiTextRoundTripPreservesUnicodeQuotesAndMultilineFields() {
        val document = parseTransfer(exportAnkiTsv(listOf(word)))

        val preview = previewImport(document, defaultTransferMapping(document), "ja", "en", emptyList())

        assertEquals(0, preview.invalid)

        val result = preview.words.single()

        assertEquals(word.copy(id = result.id, dueAt = 0, intervalMs = 0), result)

        assertEquals(1, previewImport(document, defaultTransferMapping(document), "en", "vi", listOf(result)).duplicates)
    }

    @Test fun mappingAndDuplicateIdentityDoNotDependOnMeaning() {
        val document = parseTransfer("nghĩa\tword\nupdated\tword")

        val preview = previewImport(document, mapOf("word" to 1, "meaning" to 0), "en", "vi", emptyList())

        assertEquals(2, preview.words.size)

        assertEquals(1, preview.duplicates)

        assertEquals("word", preview.words.first().word)
    }

    @Test fun invalidRowsAreCountedWithoutDroppingValidRows() {
        val doc = parseTransfer("hello\tmeaning\n\tmissing\nonly-one")

        val result = previewImport(doc, defaultTransferMapping(doc), "en", "vi", emptyList())

        assertEquals(1, result.words.size)

        assertEquals(2, result.invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedVersionRejected() {
        parseTransfer(exportBackup(listOf(word)).replace("\"version\": 1", "\"version\": 99"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedQuotesRejected() {
        parseTransfer("\"unfinished")
    }

    @Test(expected = IllegalArgumentException::class)
    fun boundedReaderRejectsOversize() {
        readTransferText(ByteArray(MAX_TRANSFER_BYTES + 1).inputStream())
    }

    @Test fun emptyBackupIsValid() {
        assertTrue(parseTransfer(exportBackup(emptyList())).backup!!.isEmpty())
    }
}
