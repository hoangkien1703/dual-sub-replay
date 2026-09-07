package com.kienhoang.dualsubreplay.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PracticeImportTest {
    @Test fun cancelledImportRollsBackEarlierRows() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "cancel-import-${UUID.randomUUID()}.db"
            val repository = VocabularyRepository(context, name)
            try {
                lateinit var importing: Job
                val rows =
                    object : AbstractList<SavedWord>() {
                        override val size = 2

                        override fun get(index: Int): SavedWord {
                            if (index == 1) importing.cancel()
                            return word("row-$index")
                        }
                    }
                importing = launch(start = CoroutineStart.LAZY) { repository.importWords(rows, false) }
                importing.start()
                importing.join()
                repository.refresh()
                assertTrue(repository.words.value.isEmpty())
            } finally {
                repository.close()
                context.deleteDatabase(name)
            }
        }

    private fun word(id: String) =
        SavedWord(
            id,
            "word",
            null,
            "en",
            "vi",
            "meaning",
            "sentence",
            null,
            null,
            0,
            0,
            false,
            false,
            1234,
            5678,
        )

    @Test fun transactionalImportRollsBackAndSurvivesReopen() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "import-${UUID.randomUUID()}.db"
            var repository = VocabularyRepository(context, name)
            try {
                repository.importWords(listOf(word("existing")), false)
                try {
                    repository.importWords(listOf(word("new"), word("invalid").copy(word = "")), false)
                    fail("Expected invalid input to abort transaction")
                } catch (_: IllegalArgumentException) {
                }
                repository.refresh()
                assertEquals(listOf(word("existing")), repository.words.value)
                assertEquals(0, repository.importWords(listOf(word("existing").copy(meaning = "changed")), false))
                assertEquals(1, repository.importWords(listOf(word("existing").copy(meaning = "changed")), true))
                repository.importWords(
                    listOf(word("existing").copy(meaning = "changed", dueAt = 0, intervalMs = 0)),
                    true,
                    preserveReviewHistory = true,
                )
                repository.close()
                repository = VocabularyRepository(context, name)
                repository.refresh()
                assertEquals(word("existing").copy(meaning = "changed"), repository.words.value.single())
            } finally {
                repository.close()
                context.deleteDatabase(name)
            }
        }
}
