package com.arin.bypass.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.arin.bypass.model.Finding
import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.Project
import com.arin.bypass.ui.components.EmptyState
import com.arin.bypass.ui.theme.Mono
import com.arin.bypass.util.Format
import com.arin.bypass.viewmodel.BypassViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    vm: BypassViewModel,
    state: BypassViewModel.UiState,
    projectId: String,
    navController: NavController,
    drawer: DrawerState
) {
    val scope = rememberCoroutineScope()
    val project = state.current?.takeIf { it.id == projectId }

    LaunchedEffect(projectId) { vm.openProject(projectId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "Project", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { drawer.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        if (project == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState("Opening", "Reading this project from the phone storage.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { StatusCard(project) }
            item { ActionCard(vm, state, project) }
            if (state.diff.isNotBlank()) {
                item { DiffCard(state.diff) { vm.clearDiff() } }
            }
            if (state.editorFile != null) {
                item { EditorCard(state.editorText, vm) }
            }
            if (state.javaText.isNotBlank()) {
                item { JavaCard(state.javaText) { vm.clearJava() } }
            }
            item {
                Text("Detected issues (${project.openFindings})", style = MaterialTheme.typography.titleMedium)
            }
            val visible = project.findings.filter { !it.dismissed }
            if (visible.isEmpty()) {
                item { EmptyState("Nothing listed", "Run the unpack and scan step to fill this list.") }
            } else {
                items(visible, key = { it.id }) { finding ->
                    FindingCard(finding, vm)
                }
            }
            item { PatchLedger(project, vm) }
            item { LogPanel(state) }
        }
    }
}

@Composable
private fun StatusCard(project: Project) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(project.packageName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Stage: ${project.stage.name.lowercase().replace('_', ' ')} · rounds ${project.rounds}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (project.dryRun) "Dry run is on: nothing installs by itself." else "Dry run is off: builds install.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (project.missingSplits.isNotEmpty()) {
                Text(
                    "Missing split parts: ${project.missingSplits.joinToString(", ")}. The clone may miss native code.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (project.crashLooping) {
                Text(
                    "The clone still crash-loops, so export is blocked.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                "Original ${Format.sha(project.originalSha256)} · working ${Format.sha(project.workingSha256)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionCard(vm: BypassViewModel, state: BypassViewModel.UiState, project: Project) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(state.progressText, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.cancel() }) { Text("Cancel") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.prepare() }, enabled = !state.busy) { Text("Unpack & scan") }
                OutlinedButton(onClick = { vm.runBypass() }, enabled = !state.busy && project.findings.isNotEmpty()) {
                    Text("Run bypass")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.rebuild() }, enabled = !state.busy) { Text("Rebuild") }
                OutlinedButton(onClick = { vm.installOutput() }, enabled = !state.busy) { Text("Install") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.runRepairLoop() }, enabled = !state.busy) { Text("Repair loop") }
                OutlinedButton(onClick = { vm.exportApk() }, enabled = !state.busy) { Text("Export") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.writeReport() }, enabled = !state.busy) { Text("Report") }
                OutlinedButton(onClick = { vm.revertAll() }, enabled = !state.busy) { Text("Revert all") }
            }
        }
    }
}

@Composable
private fun DiffCard(diff: String, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text(
                diff,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun EditorCard(text: String, vm: BypassViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Editor", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = vm::editText,
                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveEditor() }) { Text("Save with backup") }
                OutlinedButton(onClick = { vm.closeEditor() }) { Text("Close") }
            }
        }
    }
}

@Composable
private fun JavaCard(text: String, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Java view", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text(
                text.take(4000),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun FindingCard(finding: Finding, vm: BypassViewModel) {
    var url by remember(finding.id) { mutableStateOf(finding.snippet) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = { },
                    label = { Text("${finding.confidence}%") }
                )
                AssistChip(
                    onClick = { },
                    label = { Text(finding.category.name.lowercase()) }
                )
                if (finding.patched) {
                    AssistChip(onClick = { }, label = { Text("patched") })
                }
            }
            Text(finding.title, style = MaterialTheme.typography.titleMedium)
            Text(finding.detail, style = MaterialTheme.typography.bodySmall)
            Text(
                "${finding.file}:${finding.line}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (finding.snippet.isNotBlank()) {
                Text(
                    finding.snippet,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(6.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.previewFinding(finding) }) { Text("Preview") }
                Button(onClick = { vm.patchFinding(finding) }, enabled = !finding.patched) { Text("Patch") }
                TextButton(onClick = { vm.dismissFinding(finding) }) { Text("Dismiss") }
                TextButton(onClick = { vm.openEditor(finding.file) }) { Text("Open") }
                TextButton(onClick = { vm.loadJava(finding.file) }) { Text("Java") }
            }
            if (finding.category == HitCategory.ENDPOINT) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("New URL") },
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { vm.replaceEndpoint(finding, url) }) { Text("Replace URL") }
            }
        }
    }
}

@Composable
private fun PatchLedger(project: Project, vm: BypassViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Change ledger (${project.patches.count { !it.reverted }})", style = MaterialTheme.typography.titleMedium)
        if (project.patches.isEmpty()) {
            Text("No change applied yet.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        project.patches.reversed().forEach { patch ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        (if (patch.reverted) "reverted · " else "applied · ") + patch.title,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(patch.file, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.revertPatch(patch.id) }, enabled = !patch.reverted) { Text("Revert this") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogPanel(state: BypassViewModel.UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            if (state.logs.isEmpty()) {
                Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
            }
            state.logs.takeLast(60).forEach { line ->
                Text(
                    "${Format.time(line.time)}  ${line.message}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = Mono,
                    color = if (line.level == "error") Color(0xFFFFB4AB) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
