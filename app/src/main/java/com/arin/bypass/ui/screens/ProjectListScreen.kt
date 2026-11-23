package com.arin.bypass.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arin.bypass.model.Project
import com.arin.bypass.ui.components.EmptyState
import com.arin.bypass.ui.components.WorkProgress
import com.arin.bypass.util.Format
import com.arin.bypass.viewmodel.BypassViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    vm: BypassViewModel,
    state: BypassViewModel.UiState,
    navController: NavController,
    drawer: DrawerState
) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.createFromUri(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawer.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Pick an APK, APKS or XAPK file") },
                        onClick = {
                            menuOpen = false
                            picker.launch(arrayOf(
                                "application/vnd.android.package-archive",
                                "application/zip",
                                "application/octet-stream"
                            ))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Pick an installed app") },
                        onClick = {
                            menuOpen = false
                            navController.navigate("installed")
                        }
                    )
                }
                FloatingActionButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New project")
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) WorkProgress(state.progressText, state.progress)

            if (state.projects.isEmpty()) {
                EmptyState(
                    "No projects yet",
                    "Tap + to pick an APK file or an installed app. The original is kept untouched beside the working copy."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        ProjectCard(project, onOpen = { navController.navigate("project/${project.id}") }, onDelete = { vm.deleteProject(project.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                Text(project.packageName, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${project.stage.name.lowercase()} · ${project.openFindings} open hits · ${project.appliedPatches} patches",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    Format.day(project.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (project.renameTo != null) {
                    Text(
                        "clone of ${project.packageName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete project")
            }
            Icon(Icons.Filled.Apps, contentDescription = null)
        }
    }
}
