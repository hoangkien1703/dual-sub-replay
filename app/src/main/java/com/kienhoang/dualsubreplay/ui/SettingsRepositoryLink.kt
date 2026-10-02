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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal const val REPOSITORY_URL = "https://github.com/hoangkien1703/dual-sub-replay"

@Composable
internal fun SettingsRepositoryLink(onOpen: (() -> Unit)? = null, beforeOpen: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    // The sentence keeps a %1$s slot so each language can put the GitHub link where it reads naturally.
    val (beforeLink, afterLink) = splitAroundLink(stringResource(R.string.navigation_latest_version))
    val loadingLicense = stringResource(R.string.navigation_loading_license)
    val text = buildAnnotatedString {
        append(beforeLink)
        withLink(LinkAnnotation.Url(REPOSITORY_URL,
            TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = {
                scope.launch {
                beforeOpen()
                try { if (onOpen != null) onOpen() else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))) }
                catch (_: ActivityNotFoundException) { failed = true }
                }
            })) { append("GitHub") }
        append(afterLink)
    }
    Text(text, modifier = Modifier.testTag("settings_github_link"), style = MaterialTheme.typography.bodySmall)
    if (failed) Text(stringResource(R.string.navigation_no_browser), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { scope.launch { beforeOpen(); showLicense = true } }) { Text(stringResource(R.string.navigation_licenses)) }
    if (showLicense) {
        val license by produceState(loadingLicense) {
            value = withContext(Dispatchers.IO) {
                context.assets.open("licenses/MIT.txt").bufferedReader().use { it.readText() } + "\n\n" +
                    context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text(stringResource(R.string.navigation_licenses)) },
            text = { Text(stringResource(R.string.navigation_license_summary) + "\n\n" + license,
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.navigation_close)) } })
    }
}

/** The text before and after the %1$s link slot; a translation without the slot keeps the link at the end. */
internal fun splitAroundLink(sentence: String): Pair<String, String> {
    val slot = sentence.indexOf("%1\$s")
    return if (slot < 0) "$sentence " to "" else sentence.substring(0, slot) to sentence.substring(slot + 4)
}
