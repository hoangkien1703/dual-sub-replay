package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                            Text("DualSub Replay", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                            NavigationDrawerItem(
                                label = { Text("Practice") }, selected = false,
                                modifier = Modifier.testTag("open_saved_words"),
                                onClick = { scope.launch { drawer.close(); onPractice() } },
                            )
                            NavigationDrawerItem(
                                label = { Text("Settings") }, selected = false,
                                onClick = { scope.launch { drawer.close(); onSettings() } },
                            )
                        }
                        Column {
                            HorizontalDivider(Modifier.padding(vertical = 16.dp))
                            SettingsRepositoryLink(beforeOpen = { drawer.close() })
                        }
                    }
                }
            }
        },
    ) {
        content {
            IconButton(onClick = { scope.launch { drawer.open() } }) {
                Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
            }
        }
    }
}
