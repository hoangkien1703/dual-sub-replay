package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.translation.TranslationLanguages

private enum class SetupPickerMode { NATIVE, LEARNING }

@Composable
fun LanguageSetupScreen(
    onComplete: (nativeLanguage: String, learningLanguage: String) -> Unit,
    onSkip: () -> Unit,
) {
    var nativeLanguage by remember { mutableStateOf<String?>(null) }
    var learningLanguage by remember { mutableStateOf<String?>(null) }
    var pickerMode by remember { mutableStateOf<SetupPickerMode?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val choices = TranslationLanguages.all.map { LanguageChoice(it.code, it.name) }

    val activeMode = pickerMode
    if (activeMode != null) {
        val selected = if (activeMode == SetupPickerMode.NATIVE) nativeLanguage else learningLanguage
        LanguagePickerDialog(
            title = if (activeMode == SetupPickerMode.NATIVE) {
                "Your native language"
            } else {
                "Language you want to learn"
            },
            choices = choices,
            selectedCode = selected.orEmpty(),
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onChoice = { choice ->
                if (activeMode == SetupPickerMode.NATIVE) {
                    nativeLanguage = TranslationLanguages.normalize(choice.code)
                } else {
                    learningLanguage = TranslationLanguages.normalize(choice.code)
                }
                pickerMode = null
                searchQuery = ""
            },
            onDismiss = {
                pickerMode = null
                searchQuery = ""
            },
            testTagPrefix = activeMode.name.lowercase(),
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize().testTag("language_setup_screen"),
        color = Color(0xFF061719),
        contentColor = Color(0xFFF3FAFA),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.ClosedCaption,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Welcome to DualSub Replay",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFF3FAFA),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Choose your languages once. You can change them later in subtitle settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB7CED1),
            )
            Spacer(Modifier.height(28.dp))

            SetupLanguageField(
                label = "Your native language",
                helper = "Shown as the small translated subtitle.",
                selection = nativeLanguage?.let(TranslationLanguages::displayName),
                onClick = {
                    pickerMode = SetupPickerMode.NATIVE
                    searchQuery = ""
                },
                testTag = "native_language_picker",
            )
            Spacer(Modifier.height(18.dp))
            SetupLanguageField(
                label = "Language you want to learn",
                helper = "Preferred caption track; the big subtitle follows the video.",
                selection = learningLanguage?.let(TranslationLanguages::displayName),
                onClick = {
                    pickerMode = SetupPickerMode.LEARNING
                    searchQuery = ""
                },
                testTag = "learning_language_picker",
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val native = nativeLanguage ?: return@Button
                    val learning = learningLanguage ?: return@Button
                    onComplete(native, learning)
                },
                enabled = nativeLanguage != null && learningLanguage != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_continue"),
            ) {
                Text("Continue")
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_skip"),
            ) {
                Text(
                    "Skip for now",
                    color = Color(0xFFB7CED1),
                )
            }
        }
    }
}

@Composable
private fun SetupLanguageField(
    label: String,
    helper: String,
    selection: String?,
    onClick: () -> Unit,
    testTag: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color(0xFFF3FAFA))
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        ) {
            Text(
                text = selection ?: "Select language",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selection == null) Color(0xFF9EDCE4) else Color.Unspecified,
            )
        }
        Text(helper, style = MaterialTheme.typography.bodySmall, color = Color(0xFF607477))
    }
}
