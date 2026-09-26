package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AppNavigation(
    onPractice: () -> Unit,
    onSettings: () -> Unit,
    onVisibilityChange: (Boolean) -> Unit = {},
    content: @Composable (menuButton: @Composable () -> Unit) -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(drawer.currentValue, drawer.targetValue) {
        onVisibilityChange(drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open)
    }
    DisposableEffect(Unit) { onDispose { onVisibilityChange(false) } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                BoxWithConstraints(Modifier.fillMaxHeight()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            DrawerHeader(onClose = { scope.launch { drawer.close() } })
                            Spacer(Modifier.height(8.dp))
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.School, contentDescription = null) },
                                label = { Text("Practice") }, selected = false,
                                modifier = Modifier.testTag("open_saved_words"),
                                onClick = { scope.launch { drawer.close(); onPractice() } },
                            )
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }, selected = false,
                                onClick = { scope.launch { drawer.close(); onSettings() } },
                            )
                        }
                        Column(Modifier.padding(horizontal = 4.dp)) {
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            SettingsRepositoryLink(beforeOpen = { drawer.close() })
                        }
                    }
                }
            }
        },
    ) {
        content {
            Box(
                modifier = Modifier.size(width = 72.dp, height = 48.dp)
                    .testTag("navigation_menu_button")
                    .clickable(role = Role.Button) { scope.launch { drawer.open() } },
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Open navigation menu",
                    modifier = Modifier.padding(start = 12.dp).size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("DualSub Replay", style = MaterialTheme.typography.titleLarge)
            Text(
                "Learn languages with YouTube",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("close_navigation_menu")) {
            Icon(Icons.Default.Close, contentDescription = "Close navigation menu")
        }
    }
}
