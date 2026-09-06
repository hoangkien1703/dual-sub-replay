package com.kienhoang.dualsubreplay.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VocabularyRepositoryTest {
    private fun makeWord(id: String, word: String = "word", meaning: String = "từ", online: Boolean = true): SavedWord =
        SavedWord(
            id = id, word = word, reading = null, wordLanguage = "en", meaningLanguage = "vi",
            meaning = meaning, sentence = "sentence with $word", translatedSentence = "câu với $meaning",
            videoId = "dQw4w9WgXcQ", startMs = 1000, endMs = 3000, translated = false, online = online,
            dueAt = 0, intervalMs = 0,
        )

    @Test fun duplicateSaveAndReopenPreserveReviewsAndOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-test-${UUID.randomUUID()}.db"
        var repository = VocabularyRepository(context, name)
        val word = makeWord("word-1")
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

            repository.remove(word.id)
            assertNull(repository.update(word.id) { it.copy(meaning = "ghost") })
            assertTrue(repository.words.value.isEmpty())
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun orderPreservedAcrossUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-order-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        try {
            val word1 = makeWord("word-1", word = "First")
            val word2 = makeWord("word-2", word = "Second")
            repository.save(word1)
            repository.save(word2)
            // Initially, word2 is at index 0 (rowid DESC) and word1 is at index 1
            assertEquals(listOf("word-2", "word-1"), repository.words.value.map { it.id })

            // Updating word1 should NOT bump it to the top because UPDATE preserves rowid!
            repository.update(word1.id) { it.copy(meaning = "Updated First") }
            assertEquals(listOf("word-2", "word-1"), repository.words.value.map { it.id })
            assertEquals("Updated First", repository.words.value[1].meaning)
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun malformedRowDoesNotBreakValidRowsAndEmitsWarning() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-malformed-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        try {
            val validWord = makeWord("valid-1")
            repository.save(validWord)

            // Inject corrupted payload directly into SQLite
            val db = context.openOrCreateDatabase(name, 0, null)
            val cv = ContentValues().apply {
                put("id", "corrupted-row")
                put("payload", "{ not-valid-json }")
            }
            db.insert("words", null, cv)
            db.close()

            repository.refresh()
            assertEquals(1, repository.words.value.size)
            assertEquals("valid-1", repository.words.value.single().id)
            assertNotNull(repository.warning.value)
            assertTrue(repository.warning.value!!.contains("malformed"))
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun concurrentUpdatesAreSerializedWithoutDataLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-concurrent-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        try {
            val word = makeWord("concurrent-word", meaning = "start")
            repository.save(word)

            coroutineScope {
                (1..50).map { index ->
                    async {
                        repository.update(word.id) { it.copy(intervalMs = it.intervalMs + 1) }
                    }
                }.awaitAll()
            }

            assertEquals(50L, repository.words.value.single().intervalMs)
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun largeCollectionScaling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "vocabulary-large-${UUID.randomUUID()}.db"
        val repository = VocabularyRepository(context, name)
        try {
            for (i in 1..1000) {
                repository.save(makeWord("id-$i", word = "word-$i"))
            }
            assertEquals(1000, repository.words.value.size)
            assertEquals("id-1000", repository.words.value.first().id)
            assertEquals("id-1", repository.words.value.last().id)
        } finally { repository.close(); context.deleteDatabase(name) }
    }

    @Test fun legacyDownloadJobsAreRetired() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler != null) {
            val fakeJob = JobInfo.Builder(99999, ComponentName(context, "androidx.work.impl.background.systemjob.SystemJobService"))
                .setOverrideDeadline(10000)
                .build()
            try {
                scheduler.schedule(fakeJob)
            } catch (_: Exception) {
                // If the class is not in manifest or scheduling is restricted, ignore
            }
            retireLegacyDownloadJobs(context)
            val remaining = scheduler.allPendingJobs.filter {
                it.service.className.contains("androidx.work")
            }
            assertTrue(remaining.isEmpty())
        }
    }
}
