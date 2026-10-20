package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's licence, the libraries' notices, then the ML Kit translation terms. */
internal val LICENSE_ASSETS = listOf("licenses/MIT.txt", "licenses/libraries.txt", "licenses/translation.txt")

/** The bottom of the drawer: Check for updates, then the open-source licences in small text. */
@Composable
internal fun DrawerFooter(beforeOpen: suspend () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLicense by remember { mutableStateOf(false) }
    val loadingLicense = stringResource(R.string.navigation_loading_license)
    CheckForUpdatesRow(onOpen = { scope.launch { beforeOpen() } })
    TextButton(
        onClick = {
            scope.launch {
                beforeOpen()
                showLicense = true
            }
        },
        modifier = Modifier.padding(start = 4.dp).testTag("open_source_licenses"),
    ) {
        Text(
            stringResource(R.string.navigation_licenses),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
