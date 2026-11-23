package com.arin.bypass.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arin.bypass.viewmodel.BypassViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: BypassViewModel,
    state: BypassViewModel.UiState,
    navController: NavController,
    drawer: DrawerState
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dry run", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Patch and show the diff, install nothing until you say so.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = state.settings.dryRun, onCheckedChange = { vm.setDryRun(it) })
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Rename clones", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Change the package so the clone installs beside the original.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = state.settings.renameClones, onCheckedChange = { vm.setRenameClones(it) })
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Scan after unpack", style = MaterialTheme.typography.titleMedium)
                        Text("Look for checks straight away.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.settings.autoScan, onCheckedChange = { vm.setAutoScan(it) })
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Repair rounds: ${state.settings.roundLimit}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Six is the maximum, then the loop stops and shows the diff.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = state.settings.roundLimit.toFloat(),
                        onValueChange = { vm.setRoundLimit(it.toInt()) },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                }
            }
        }
    }
}
