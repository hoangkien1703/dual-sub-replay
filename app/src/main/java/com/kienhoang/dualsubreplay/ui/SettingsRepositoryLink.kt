package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

internal const val REPOSITORY_URL = "https://github.com/hoangkien1703/dual-sub-replay"

@Composable
internal fun SettingsRepositoryLink() {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val text = buildAnnotatedString {
        append("Check the latest version of the app on ")
        pushStringAnnotation("url", REPOSITORY_URL)
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) { append("GitHub") }
        pop()
        append(".")
    }
    ClickableText(text, modifier = Modifier.testTag("settings_github_link"),
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        onClick = { offset ->
            if (text.getStringAnnotations("url", offset, offset).isNotEmpty()) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))) }
                catch (_: ActivityNotFoundException) { failed = true }
            }
        })
    if (failed) Text("No browser is available to open GitHub.", style = MaterialTheme.typography.bodySmall)
}
