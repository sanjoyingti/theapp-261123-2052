package com.arin.bypass.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arin.bypass.data.AppContainer
import com.arin.bypass.model.Finding
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.Project
import com.arin.bypass.model.Settings
import com.arin.bypass.model.Stage
import com.arin.bypass.util.Format
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BypassViewModel(private val container: AppContainer) : ViewModel() {

    data class InstalledApp(val packageName: String, val label: String)

    data class UiState(
        val loading: Boolean = true,
        val projects: List<Project> = emptyList(),
        val selectedId: String? = null,
        val selected: Project? = null,
        val settings: Settings = Settings(),
        val busy: Boolean = false,
        val progress: Int = 0,
        val progressLabel: String = "",
        val liveLog: List<LogLine> = emptyList(),
        val diffTitle: String = "",
        val diff: String = "",
        val message: String? = null,
        val installed: List<InstalledApp> = emptyList(),
        val installedLoaded: Boolean = false,
        val codePath: String = "",
        val codeText: String = "",
        val javaText: String = "",
        val codeTab: Int = 0,
        val reportText: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        bootstrap()
    }

    private fun now(): Long = System.currentTimeMillis()

    fun bootstrap() {
        viewModelScope.launch {
            val settings = runCatching { container.settings.read() }.getOrDefault(Settings())
            val projects = withContext(Dispatchers.IO) { container.projects.list() }
            _state.update { it.copy(loading = false, settings = settings, projects = projects) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun clearDiff() = _state.update { it.copy(diff = "", diffTitle = "") }

    private fun log(level: String, message: String) {
        val entry = LogLine(System.currentTimeMillis(), level, message)
        _state.update { it.copy(liveLog = (it.liveLog + entry).takeLast(800)) }
    }

    private fun progress(label: String, percent: Int) {
        _state.update { it.copy(progressLabel = label, progress = percent.coerceIn(0, 100)) }
    }

    private suspend fun save(project: Project) = container.projects.save(project)

    private suspend fun reload() {
        val projects = withContext(Dispatchers.IO) { container.projects.list() }
        val id = _state.value.selectedId
        val selected = id?.let { current -> projects.firstOrNull { it.id == current } }
        _state.update { it.copy(projects = projects, selected = selected) }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update {
                it.copy(busy = true, progress = 0, progressLabel = label, diff = "", diffTitle = "")
            }
            try {
                block()
            } catch (e: CancellationException) {
                log("warn", "Cancelled. The project is kept and can be resumed.")
                _state.update { it.copy(message = "Cancelled. Everything done so far is kept.") }
            } catch (e: Exception) {
                val plain = Trouble.plain(e)
                log("error", plain)
                _state.update { it.copy(message = plain) }
            } finally {
                _state.update { it.copy(busy = false, progress = 0, progressLabel = "") }
                reload()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        val project = _state.value.selected ?: return
        viewModelScope.launch {
            val stopped = project.copy(stage = Stage.STOPPED, stopped = true, updatedAt = now())
            save(stopped)
            _state.update { it.copy(selected = stopped) }
        }
    }

    fun open(id: String) {
        val project = _state.value.projects.firstOrNull { it.id == id } ?: container.projects.load(id) ?: return
        _state.update { it.copy(selectedId = id, selected = project, reportText = "") }
    }

    fun addFromUri(uri: Uri) = run("Reading the picked file") {
        val project = container.intake.fromUri(uri) { log("tool", it) }.getOrThrow()
        open(project.id)
        if (container.settings.read().autoScan) prepareAndPatch(project)
    }

    fun addInstalled(packageName: String) = run("Reading the installed app") {
        val project = container.intake.fromInstalled(packageName) { log("tool", it) }.getOrThrow()
        open(project.id)
        if (container.settings.read().autoScan) prepareAndPatch(project)
    }

    fun start(id: String) {
        val project = _state.value.selected ?: return
        if (project.id != id) return
        run("Preparing the project") { prepareAndPatch(project) }
    }

    private suspend fun prepareAndPatch(initial: Project) {
        var project = initial
        val result = container.pipeline.prepare(
            project,
            log = { message -> log("tool", message) },
            progress = { label, percent -> progress(label, percent) }
        )
        val error = result.exceptionOrNull()
        if (error != null) {
            val latest = container.projects.load(project.id) ?: project
            val stopped = latest.copy(stage = Stage.STOPPED, stopped = true, updatedAt = now())
            save(stopped)
            _state.update { it.copy(selected = stopped) }
            throw error
        }
        project = result.getOrThrow()
        _state.update { it.copy(selected = project, selectedId = project.id) }
        save(project)
        log("info", "Stage ${project.stage.name}: ${project.appliedPatches} patches, ${project.openFindings} open hits")
    }

    fun rebuild() {
        val project = _state.value.selected ?: return
        run("Rebuilding the clone") {
            val result = container.pipeline.rebuild(
                project,
                log = { message -> log("tool", message) },
                progress = { label, percent -> progress(label, percent) }
            )
            val updated = result.getOrThrow()
            _state.update { it.copy(selected = updated) }
        }
    }

    fun install() {
        val project = _state.value.selected ?: return
        val apk = project.outputApk?.let { File(it) }
        if (apk == null || !apk.exists()) {
            _state.update { it.copy(message = "Rebuild first, there is no signed app yet.") }
            return
        }
        run("Opening the installer") {
            log("info", "Installing ${apk.name}")
            container.installer.install(apk).getOrThrow()
            _state.update { it.copy(message = "Confirm the install in the phone's installer, then tap Watch.") }
        }
    }

    fun watch() {
        val project = _state.value.selected ?: return
        run("Watching the clone") {
            val result = container.pipeline.verify(
                project,
                log = { message -> log("tool", message) },
                progress = { label, percent -> progress(label, percent) }
            )
            val updated = result.getOrThrow()
            _state.update { it.copy(selected = updated) }
        }
    }

    fun export() {
        val project = _state.value.selected ?: return
        run("Exporting the clone") {
            val file = container.pipeline.export(project).getOrThrow()
            log("info", "Exported to ${file.absolutePath}")
            _state.update { it.copy(message = "Exported to ${file.name}") }
        }
    }

    fun previewFinding(findingId: String) {
        val project = _state.value.selected ?: return
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { container.actions.preview(project, finding) }
            if (preview.isBlank()) {
                _state.update { it.copy(message = "There is nothing to preview for this hit.") }
            } else {
                _state.update { it.copy(diff = preview, diffTitle = finding.title) }
            }
        }
    }

    fun patchFinding(findingId: String) {
        val project = _state.value.selected ?: return
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return
        run("Patching ${finding.title}") {
            val result = container.actions.patch(project, finding)
            save(result.project)
            log("patch", result.message)
            _state.update {
                it.copy(selected = result.project, diff = result.diff, diffTitle = result.message)
            }
        }
    }

    fun replaceEndpoint(findingId: String, newUrl: String) {
        val project = _state.value.selected ?: return
        val finding: Finding = project.findings.firstOrNull { it.id == findingId } ?: return
        if (newUrl.isBlank()) return
        run("Replacing the base URL") {
            val result = container.actions.replaceEndpoint(project, finding, newUrl.trim())
            save(result.project)
            log("patch", result.message)
            _state.update {
                it.copy(selected = result.project, diff = result.diff, diffTitle = result.message)
            }
        }
    }

    fun dismissFinding(findingId: String) {
        val project = _state.value.selected ?: return
        run("Dismissing the hit") {
            val updated = project.copy(
                findings = project.findings.map { if (it.id == findingId) it.copy(dismissed = true) else it },
                updatedAt = now()
            )
            save(updated)
            _state.update { it.copy(selected = updated) }
        }
    }

    fun revert(patchId: String) {
        val project = _state.value.selected ?: return
        run("Reverting one change") {
            val updated = container.patcher.revert(project, patchId)
            save(updated)
            _state.update { it.copy(selected = updated) }
            log("info", "Reverted one change")
        }
    }

    fun revertAll() {
        val project = _state.value.selected ?: return
        run("Reverting everything") {
            val updated = container.patcher.revertAll(project)
            save(updated)
            _state.update { it.copy(selected = updated) }
            log("info", "All changes reverted")
        }
    }

    fun openFile(relPath: String) = run("Opening ${relPath.substringAfterLast('/')}") {
        val project = _state.value.selected ?: return@run
        val file = File(container.projects.decodeDir(project.id), relPath)
        val text = withContext(Dispatchers.IO) { if (file.isFile) file.readText() else "" }
        val java = withContext(Dispatchers.IO) { findJava(project, relPath) }
        _state.update { it.copy(codePath = relPath, codeText = text, javaText = java, codeTab = 0) }
    }

    private fun findJava(project: Project, relPath: String): String {
        var name = relPath.substringAfterLast('/').removeSuffix(".smali")
        val outer = name.substringBefore('$')
        name = if (outer.isEmpty()) name else outer
        val javaDir = container.projects.javaDir(project.id)
        val target = javaDir.walkTopDown()
            .filter { it.isFile && it.name == "$name.java" }
            .take(1)
            .firstOrNull() ?: return "No readable Java version of this file was produced."
        return target.readText()
    }

    fun saveCode(newText: String) {
        val project = _state.value.selected ?: return
        val path = _state.value.codePath
        if (path.isBlank()) return
        run("Saving ${path.substringAfterLast('/')}") {
            val record = container.patcher.patchText(
                project,
                path,
                "Manual edit of ${path.substringAfterLast('/')}",
                PatchKind.NOTE,
                null
            ) { newText }
            if (record == null) {
                _state.update { it.copy(message = "Nothing changed in that file.") }
            } else {
                val updated = project.copy(patches = project.patches + record, updatedAt = now())
                save(updated)
                log("patch", "Saved by hand: $path")
                _state.update {
                    it.copy(selected = updated, diff = record.diff, diffTitle = record.title)
                }
            }
        }
    }

    fun makeReport() {
        val project = _state.value.selected ?: return
        viewModelScope.launch {
            val markdown = container.reporter.markdown(project)
            val file = withContext(Dispatchers.IO) { container.projects.writeReport(project, markdown) }
            _state.update { it.copy(reportText = markdown, message = "Report saved as ${file.name}") }
        }
    }

    fun exportLog() {
        val project = _state.value.selected ?: return
        viewModelScope.launch {
            val text = buildString {
                project.logs.forEach { appendLine("${Format.day(it.time)} ${it.level} ${it.message}") }
                _state.value.liveLog.forEach { appendLine("${Format.day(it.time)} ${it.level} ${it.message}") }
            }
            val file = withContext(Dispatchers.IO) {
                File(container.projects.exports(), "log-${project.id}-${System.currentTimeMillis()}.txt")
                    .apply { writeText(text) }
            }
            _state.update { it.copy(message = "Log saved as ${file.name}") }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.projects.delete(id) }
            _state.update {
                it.copy(
                    projects = it.projects.filterNot { item -> item.id == id },
                    selected = if (it.selectedId == id) null else it.selected,
                    selectedId = if (it.selectedId == id) null else it.selectedId
                )
            }
            log("info", "Project deleted")
        }
    }

    fun loadInstalled() {
        if (_state.value.installedLoaded) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val manager = container.context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                manager.queryIntentActivities(intent, 0).mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == container.context.packageName) return@mapNotNull null
                    InstalledApp(pkg, runCatching { info.loadLabel(manager).toString() }.getOrDefault(pkg))
                }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }
            _state.update { it.copy(installed = apps, installedLoaded = true) }
        }
    }

    fun setDryRun(value: Boolean) {
        viewModelScope.launch {
            container.settings.setDryRun(value)
            _state.update { it.copy(settings = it.settings.copy(dryRun = value)) }
        }
    }

    fun setRenameClones(value: Boolean) {
        viewModelScope.launch {
            container.settings.setRenameClones(value)
            _state.update { it.copy(settings = it.settings.copy(renameClones = value)) }
        }
    }

    fun setAutoScan(value: Boolean) {
        viewModelScope.launch {
            container.settings.setAutoScan(value)
            _state.update { it.copy(settings = it.settings.copy(autoScan = value)) }
        }
    }

    fun setRoundLimit(value: Int) {
        viewModelScope.launch {
            container.settings.setRoundLimit(value)
            _state.update { it.copy(settings = it.settings.copy(roundLimit = value.coerceIn(1, 6))) }
        }
    }
}

class BypassViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BypassViewModel(container) as T
    }
}
