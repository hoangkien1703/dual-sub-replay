package com.kienhoang.dualsubreplay.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kienhoang.dualsubreplay.data.SavedWord
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.VocabularyRepository

/** Deterministic offline journeys, packaged only in the isolated benchmark APK. */
class BenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                if (intent.getStringExtra("journey") == "translation") {
                    var result by remember { mutableStateOf("Evaluating translations…") }
                    LaunchedEffect(Unit) { result = evaluateTranslation(this@BenchmarkActivity) }
                    androidx.compose.material3.Text(result)
                } else if (intent.getStringExtra("journey") == "practice") {
                    val repository = remember { VocabularyRepository(this, "benchmark-fixture.db") }
                    DisposableEffect(repository) { onDispose { repository.close() } }
                    var ready by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        repository.importWords(
                            (0 until 500).map { index ->
                                SavedWord(
                                    "fixture-$index",
                                    "Word $index",
                                    null,
                                    "en",
                                    "vi",
                                    "Nghĩa $index",
                                    "Example sentence $index",
                                    null,
                                    null,
                                    0,
                                    0,
                                    false,
                                    false,
                                )
                            },
                            false,
                        )
                        ready = true
                    }
                    if (ready) SavedWordsScreen(repository, {}, {}, { finish() })
                } else {
                    val segments =
                        remember {
                            (0 until 500).map { index ->
                                SubtitleSegment(
                                    index.toLong(),
                                    index * 1000L,
                                    index * 1000L + 1000,
                                    "This is an example sentence $index.",
                                    "Đây là một câu ví dụ $index.",
                                )
                            }
                        }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(segments, key = { it.id }) { segment ->
                            CompactSubtitleCard(segment, false, 1f, {}, wordLearningEnabled = true)
                        }
                    }
                }
            }
        }
    }
}
