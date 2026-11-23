package com.arin.bypass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arin.bypass.viewmodel.BypassViewModel
import kotlinx.coroutines.launch

private val toolList = listOf(
    "apktool" to "Unpacks and repackages the app",
    "JADX" to "Writes the readable Java view",
    "apk-mitm helper" to "Relaxes certificate pinning",
    "APKEditor" to "Repairs split archives",
    "uber-apk-signer" to "Fallback signer",
    "keytool" to "Makes one signing key per project"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    vm: BypassViewModel,
    state: BypassViewModel.UiState,
    navController: NavController,
    drawer: DrawerState
) {
    val scope = rememberCoroutineScope()

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
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (state.toolsReady) "The helper programs are installed." else "The helper programs are not ready yet.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Setup runs once and is skipped next time. Everything runs in the background, so the screen stays usable.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.busy) {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(state.progressText, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
                    } else {
                        Button(onClick = { vm.installTools() }) {
                            Text(if (state.toolsReady) "Check and repair tools" else "Install tools")
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What is needed", style = MaterialTheme.typography.titleMedium)
                    toolList.forEach { (name, why) ->
                        Text("• $name — $why", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Installing the finished clone", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The phone must allow this app to install unknown apps. Tap below to open the switch.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { vm.openInstallPermission() }) { Text("Open install permission") }
                }
            }
        }
    }
}
