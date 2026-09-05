package com.kienhoang.dualsubreplay.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val REPOSITORY_URL = "https://github.com/hoangkien1703/dual-sub-replay"

@Composable
internal fun SettingsRepositoryLink(onOpen: (() -> Unit)? = null) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    val text = buildAnnotatedString {
        append("Check the latest version of the app on ")
        withLink(LinkAnnotation.Url(REPOSITORY_URL,
            TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = {
                try { if (onOpen != null) onOpen() else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))) }
                catch (_: ActivityNotFoundException) { failed = true }
            })) { append("GitHub") }
        append(".")
    }
    Text(text, modifier = Modifier.testTag("settings_github_link"), style = MaterialTheme.typography.bodySmall)
    if (failed) Text("No browser is available to open GitHub.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { showLicense = true }) { Text("Open-source licenses") }
    if (showLicense) {
        val license by produceState("Loading license…") {
            value = withContext(Dispatchers.IO) {
                context.assets.open("licenses/MIT.txt").bufferedReader().use { it.readText() } + "\n\n" +
                    context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("Open-source licenses") },
            text = { Text("DualSub Replay © 2026 Hoang Trung Kien. This combined application is distributed under GPL-3.0 without warranty. You may redistribute and modify it under these terms. Original MIT notices and dependency source/build links are in THIRD_PARTY_NOTICES.md in the GitHub repository.\n\n" + license,
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("Close") } })
    }
}
