package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VocabularyRepositoryTest {
    @Test fun duplicateSaveAndReopenPreserveReviewsAndDeletionRejectsLateWorkerUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-test-${UUID.randomUUID()}.db"
        var repository = VocabularyRepository(context, name)
        val selection = LearningWordSelection(AnalyzedToken("word", 0, 4, PartOfSpeech.NOUN), "en", "vi",
            "dQw4w9WgXcQ", SubtitleSegment(1, 1000, 3000, "a word", null), false)
        val word = savedWordFrom(selection, "từ", true, false)
        try {
            repository.save(word)
            repository.update(word.id) { reviewWord(it, ReviewRating.GOOD, 1000) }
            repository.save(word.copy(meaning = "edited", online = false))
            assertEquals(1, repository.words.value.size)
            assertEquals(1000 + 3 * DAY_MS, repository.words.value.single().dueAt)
            repository.close()
            repository = VocabularyRepository(context, name)
            repository.refresh()
            assertEquals("edited", repository.words.value.single().meaning)
            assertFalse(repository.words.value.single().online)
            assertEquals(1000 + 3 * DAY_MS, repository.words.value.single().dueAt)
            repository.update(word.id) { it.copy(offline = true, clipStatus = "downloading", clipGeneration = 1) }
            val partial = java.io.File(repository.clipDirectory, "${word.id}-1-interrupted.part").apply { mkdirs() }
            java.io.File(partial, "partial.mp4").writeText("unfinished download")
            repository.reconcileDownloads(context)
            assertEquals("failed", repository.words.value.single().clipStatus)
            assertFalse(partial.exists())
            repository.remove(word.id)
            assertNull(repository.update(word.id) { it.copy(clipStatus = "ready") })
            assertTrue(repository.words.value.isEmpty())
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
