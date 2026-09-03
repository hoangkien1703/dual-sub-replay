package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kienhoang.dualsubreplay.data.AnalyzedToken

/**
 * Tap-to-learn inspection popup for Word Learning Mode (Issue #42 V2).
 * Shows the word surface form, POS badge, reading, and on-demand definition.
 */
@Composable
internal fun WordLearningDialog(
    token: AnalyzedToken,
    sourceLanguage: String,
    targetLanguage: String,
    onTranslateWord: suspend (word: String) -> String,
    onDismiss: () -> Unit,
) {
    var definition by remember(token) { mutableStateOf<String?>(null) }
    var isLoading by remember(token) { mutableStateOf(true) }

    LaunchedEffect(token) {
        isLoading = true
        definition = try {
            onTranslateWord(token.text)
        } catch (_: Exception) {
            "Translation unavailable"
        } finally {
            isLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF132226),
            contentColor = Color(0xFFF3FAFA),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .testTag("word_learning_dialog"),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Word surface form
                    Text(
                        text = token.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )

                    // POS Category Badge
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(token.partOfSpeech.colorHex).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = token.partOfSpeech.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(token.partOfSpeech.colorHex),
                        )
                    }
                }

                if (!token.reading.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = token.reading,
                        fontSize = 14.sp,
                        color = Color(0xFF9EA3A5),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Meaning / Definition in user's target language
                Text(
                    text = "Meaning",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF75E7C1),
                )
                Spacer(Modifier.height(4.dp))

                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF75E7C1),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Loading definition...",
                            fontSize = 14.sp,
                            color = Color(0xFFC0C7C9),
                        )
                    }
                } else {
                    Text(
                        text = definition ?: token.text,
                        fontSize = 16.sp,
                        color = Color(0xFFF3FAFA),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Close",
                            color = Color(0xFF75E7C1),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
