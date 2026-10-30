package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's licence, the libraries' notices, then the ML Kit translation terms. */
internal val LICENSE_ASSETS =
    listOf("licenses/MIT.txt", "licenses/libraries.txt", "licenses/hanzi.txt", "licenses/translation.txt")

internal const val PROJECT_REPOSITORY_URL = "https://github.com/hoangkien1703/dual-sub-replay"

@Composable
private fun FooterLinkText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The bottom of the drawer: Check for updates, Report a problem, then the open-source licences and the GitHub link in small text. */
@Composable
internal fun DrawerFooter(beforeOpen: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLicense by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val loadingLicense = stringResource(R.string.navigation_loading_license)
    val uriHandler = LocalUriHandler.current
    CheckForUpdatesRow(onOpen = { scope.launch { beforeOpen() } })
    TextButton(
        onClick = {
            scope.launch {
                beforeOpen()
                showReport = true
            }
        },
        modifier = Modifier.padding(start = 4.dp).testTag("report_problem"),
    ) {
        FooterLinkText(stringResource(R.string.report_problem_title))
    }
    if (showReport) ReportProblemDialog(onDismiss = { showReport = false })
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                scope.launch {
                    beforeOpen()
                    showLicense = true
                }
            },
            modifier = Modifier.testTag("open_source_licenses"),
        ) {
            FooterLinkText(stringResource(R.string.navigation_licenses))
        }
        Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Owner request 2026-10-24: a small link so people can see the code and star the project.
        TextButton(
            onClick = {
                scope.launch {
                    beforeOpen()
                    uriHandler.openUri(PROJECT_REPOSITORY_URL)
                }
            },
            modifier = Modifier.testTag("github_repository_link"),
        ) {
            FooterLinkText(stringResource(R.string.navigation_github_repository))
        }
    }
    if (showLicense) {
        val license by produceState(loadingLicense) {
            value =
                withContext(Dispatchers.IO) {
                    LICENSE_ASSETS.joinToString("\n\n") { name ->
                        context.assets
                            .open(name)
                            .bufferedReader()
                            .use { it.readText() }
                    }
                }
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.navigation_licenses)) },
            text = {
                Text(
                    stringResource(R.string.navigation_license_summary) + "\n\n" + license,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.navigation_close)) } },
        )
    }
}
