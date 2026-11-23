package com.arin.bypass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arin.bypass.engine.Shell
import com.arin.bypass.ui.components.WorkProgress
import com.arin.bypass.viewmodel.BypassViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    vm: BypassViewModel,
    state: BypassViewModel.UiState,
    navController: NavController,
    drawer: DrawerState
) {
    val scope = rememberCoroutineScope()
    val tools = listOf("java", "keytool", "apktool", "jadx", "zipalign", "apksigner", "uber-apk-signer")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawer.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text(
                "Everything here is installed once and is not downloaded again. Nothing runs on the main thread.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.installTools() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.toolsReady) "Check and repair the tools" else "Install all tools")
            }
            if (state.busy) {
                Spacer(Modifier.height(12.dp))
                WorkProgress(state.progressText, state.progress)
            }
            Spacer(Modifier.height(16.dp))
            for (tool in tools) {
                val path = Shell.which(tool)
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(tool, style = MaterialTheme.typography.titleMedium)
                            Text(
                                path ?: "not present",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (path == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "A signing key is made per project on first build, so two clones never share a key.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Folder: ${state.settings.let { "" }}${Shell.binDir?.absolutePath ?: "not ready"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
