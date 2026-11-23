package com.arin.bypass.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.arin.bypass.data.AppContainer
import com.arin.bypass.ui.screens.InstalledAppsScreen
import com.arin.bypass.ui.screens.ProjectListScreen
import com.arin.bypass.ui.screens.ProjectScreen
import com.arin.bypass.ui.screens.SettingsScreen
import com.arin.bypass.ui.screens.ToolsScreen
import com.arin.bypass.viewmodel.BypassViewModel
import kotlinx.coroutines.launch

private data class Entry(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppShell(container: AppContainer) {
    val vm: BypassViewModel = viewModel(factory = BypassViewModel.factory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val entries = listOf(
        Entry("projects", "Projects", Icons.Filled.Folder),
        Entry("installed", "Installed apps", Icons.Filled.Apps),
        Entry("tools", "Tools", Icons.Filled.Build),
        Entry("settings", "Settings", Icons.Filled.Settings)
    )

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp)) {
                    Text("APK Bypass", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Removes server checks from your own builds",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                for (entry in entries) {
                    NavigationDrawerItem(
                        label = { Text(entry.label) },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            navController.navigate(entry.route) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        NavHost(navController = navController, startDestination = "projects") {
            composable("projects") { ProjectListScreen(vm, state, navController, drawer) }
            composable("installed") { InstalledAppsScreen(vm, state, navController, drawer) }
            composable("tools") { ToolsScreen(vm, state, navController, drawer) }
            composable("settings") { SettingsScreen(vm, state, navController, drawer) }
            composable("project/{id}") { backStack ->
                val id = backStack.arguments?.getString("id").orEmpty()
                ProjectScreen(vm, state, id, navController, drawer)
            }
        }
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            vm.toast(message)
        }
    }
}

@Composable
fun ShellMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth().padding(16.dp)
    )
}
