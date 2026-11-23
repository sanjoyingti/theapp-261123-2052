package com.arin.bypass.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.arin.bypass.data.AppContainer
import com.arin.bypass.engine.Shell
import com.arin.bypass.model.Finding
import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.Ids
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.PatchRecord
import com.arin.bypass.model.Project
import com.arin.bypass.model.Settings
import com.arin.bypass.model.Stage
import com.arin.bypass.service.ForegroundSyncService
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

data class InstalledApp(val packageName: String, val label: String, val system: Boolean)

data class ToolState(val name: String, val present: Boolean)

data class UiState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val progress: Int = 0,
    val progressText: String = "",
    val message: String? = null,
    val settings: Settings = Settings(),
    val projects: List<Project> = emptyList(),
    val open: Project? = null,
    val logs: List<LogLine> = emptyList(),
    val tools: List<ToolState> = emptyList(),
    val keystores: Int = 0,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,
    val appFilter: String = "",
    val diff: String? = null,
    val diffTitle: String = "",
    val editorPath: String? = null,
    val editorText: String = "",
    val javaText: String = "",
    val javaAvailable: Boolean = false,
    val filter: HitCategory? = null,
    val reportOpen: Boolean = false
) {
    val visibleFindings: List<Finding>
        get() = open?.findings
            ?.filter { !it.dismissed && (filter == null || it.category == filter) }
            ?.sortedByDescending { it.confidence }
            .orEmpty()
}

class BypassViewModel(private val c: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var work: Job? = null

    init {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { c.settings.read() }
            _ui.update { it.copy(settings = settings, ready = true) }
        }
        refreshProjects()
        refreshTools()
    }

    fun toast(text: String) {
        _ui.update { it.copy(message = text) }
    }

    fun log(level: String, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, text)
        _ui.update { it.copy(logs = (it.logs + line).takeLast(600)) }
    }

    private fun progress(text: String, percent: Int) {
        _ui.update { it.copy(progressText = text, progress = percent.coerceIn(0, 100)) }
        log("tool", text)
    }

    private fun launchWork(title: String, block: suspend () -> Unit) {
        if (work?.isActive == true) {
            toast("Something is still running. Cancel it first.")
            return
        }
        work = viewModelScope.launch {
            _ui.update { it.copy(busy = true, progress = 0, progressText = title) }
            ForegroundSyncService.start(c.context)
            try {
                block()
            } catch (e: CancellationException) {
                log("warn", "Cancelled. The project is kept and can be started again.")
                throw e
            } catch (e: Exception) {
                val plain = Trouble.plain(e)
                log("error", plain)
                toast(plain)
            } finally {
                _ui.update { it.copy(busy = false, progressText = "", progress = 0) }
                ForegroundSyncService.stop(c.context)
            }
        }
    }

    fun cancel() {
        work?.cancel()
        val project = _ui.value.open
        if (project != null) {
            val halted = project.copy(stage = Stage.STOPPED, stopped = true, updatedAt = System.currentTimeMillis())
            viewModelScope.launch { c.projects.save(halted.copy(logs = _ui.value.logs)) }
            _ui.update { it.copy(open = halted) }
        }
        toast("Cancelled. Nothing is lost.")
    }

    fun refreshProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { c.projects.list() }
            _ui.update { it.copy(projects = list) }
        }
    }

    fun refreshTools() {
        viewModelScope.launch {
            val tools = withContext(Dispatchers.IO) {
                listOf("java", "keytool", "apktool", "jadx", "zipalign", "apksigner")
                    .map { ToolState(it, Shell.which(it) != null) }
            }
            val stores = withContext(Dispatchers.IO) {
                c.toolchain.keystoreDir().listFiles()?.count { it.name.endsWith(".jks") } ?: 0
            }
            _ui.update { it.copy(tools = tools, keystores = stores) }
        }
    }

    // ---------- intake ----------

    fun createFromUri(uri: Uri) = launchWork("Opening the picked file") {
        progress("Copying the file into a project", 12)
        val result = c.intake.fromUri(uri) { log("tool", it) }
        result.onFailure {
            val plain = it.message ?: "That file could not be read."
            log("error", plain)
            toast(plain)
        }
        result.onSuccess { project ->
            val settings = c.settings.read()
            val prepared = project.copy(dryRun = settings.dryRun).copy(logs = _ui.value.logs)
            c.projects.save(prepared)
            _ui.update { it.copy(open = prepared) }
            refreshProjects()
            toast("Project ready: ${prepared.name}")
        }
    }

    fun createFromInstalled(packageName: String) = launchWork("Opening the installed app") {
        val result = c.intake.fromInstalled(packageName) { log("tool", it) }
        result.onFailure {
            val plain = it.message ?: "That app could not be read."
            log("error", plain)
            toast(plain)
        }
        result.onSuccess { project ->
            val settings = c.settings.read()
            val prepared = project.copy(dryRun = settings.dryRun).copy(logs = _ui.value.logs)
            c.projects.save(prepared)
            _ui.update { it.copy(open = prepared) }
            refreshProjects()
            toast("Project ready: ${prepared.name}")
        }
    }

    fun loadInstalled() {
        if (_ui.value.appsLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(appsLoading = true) }
            val list = withContext(Dispatchers.IO) {
                val manager = c.context.packageManager
                manager.getInstalledPackages(0).mapNotNull { info ->
                    val app = info.applicationInfo ?: return@mapNotNull null
                    val system = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = runCatching { app.loadLabel(manager).toString() }.getOrDefault(info.packageName)
                    InstalledApp(info.packageName, label, system)
                }.sortedBy { it.label.lowercase() }
            }
            _ui.update { it.copy(apps = list, appsLoading = false) }
        }
    }

    fun setAppFilter(text: String) = _ui.update { it.copy(appFilter = text) }

    // ---------- tools ----------

    fun installTools() {
        launchWork("Installing the tools") {
            val result = c.toolchain.install { text, percent -> progress(text, percent) }
            result.onFailure {
                val plain = it.message ?: "The tools could not be installed."
                log("error", plain)
                toast(plain)
            }
            result.onSuccess {
                c.settings.setToolchainReady(true)
                refreshTools()
                toast("Tools are ready.")
            }
        }
    }

    // ---------- project ----------

    fun openProject(id: String) {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) { c.projects.load(id) }
            if (project == null) {
                toast("That project could not be found.")
                return@launch
            }
            _ui.update {
                it.copy(
                    open = project,
                    logs = project.logs.takeLast(300),
                    reportOpen = false,
                    filter = null,
                    editorPath = null
                )
            }
        }
    }

    fun closeProject() = _ui.update {
        it.copy(open = null, logs = emptyList(), diff = null, editorPath = null, reportOpen = false, filter = null)
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.projects.delete(id) }
            refreshProjects()
            toast("Project deleted.")
        }
    }

    fun setProjectDryRun(value: Boolean) {
        val project = _ui.value.open ?: return
        val updated = project.copy(dryRun = value)
        _ui.update { it.copy(open = updated) }
        viewModelScope.launch { c.projects.save(updated) }
    }

    fun setFilter(category: HitCategory?) = _ui.update { it.copy(filter = category) }

    fun toggleReport() = _ui.update { it.copy(reportOpen = !it.reportOpen) }

    // ---------- pipeline ----------

    fun runPipeline() {
        val project = _ui.value.open ?: return
        launchWork("Working on ${project.name}") {
            log("info", "Run started for ${project.name}")
            val result = c.pipeline.run(
                project,
                { updated -> _ui.update { it.copy(open = updated.copy(logs = it.logs)) } },
                { text, percent -> progress(text, percent) },
                { line -> log("tool", line) }
            )
            result.onFailure {
                val plain = it.message ?: "The run stopped."
                log("error", plain)
                toast(plain)
            }
            result.onSuccess { finished ->
                val merged = finished.copy(logs = _ui.value.logs)
                c.projects.save(merged)
                _ui.update { it.copy(open = merged) }
                refreshProjects()
                toast("Finished: ${finished.stage.name.lowercase()}")
            }
        }
    }

    private fun withOpen(block: suspend (Project) -> Unit) {
        val project = _ui.value.open ?: return
        launchWork("Working") { block(project) }
    }

    fun runIntegrity() = withOpen { project ->
        log("info", "Integrity sweep started")
        val decode = c.projects.decodeDir(project.id)
        val (updated, notes) = c.integrity.sweep(project, decode) { log("tool", it) }
        notes.forEach { log("info", it) }
        val saved = updated.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved) }
        toast("Integrity sweep done.")
    }

    fun runBilling() = withOpen { project ->
        log("info", "Entitlement patch started")
        val (updated, notes) = c.billing.apply(project) { log("tool", it) }
        notes.forEach { log("info", it) }
        val saved = updated.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved) }
        toast("Entitlement patched.")
    }

    fun repairManifest() = withOpen { project ->
        val (updated, notes) = c.repairs.repair(project) { log("tool", it) }
        notes.forEach { log("info", it) }
        val saved = updated.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved) }
        toast("Manifest repair done.")
    }

    fun rename(suggestion: String) = withOpen { project ->
        val target = suggestion.ifBlank { project.packageName }
        val (updated, note) = c.renamer.rename(project, c.projects.decodeDir(project.id), target) { log("tool", it) }
        log("info", note)
        val saved = updated.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved) }
        toast(note)
    }

    fun rebuild() = withOpen { project ->
        log("info", "Rebuild started")
        val decode = c.projects.decodeDir(project.id)
        if (!decode.isDirectory) {
            toast("Unpack the app first.")
            return@withOpen
        }
        val result = c.builder.build(project, decode, c.projects.outDir(project.id)) { log("tool", it) }
        when (result) {
            is com.arin.bypass.engine.BuildResult.Failure -> {
                log("error", result.message)
                toast(result.message)
            }
            is com.arin.bypass.engine.BuildResult.Success -> {
                val saved = project.copy(
                    outputApk = result.apk.absolutePath,
                    stage = Stage.REVIEW,
                    logs = _ui.value.logs
                )
                c.projects.save(saved)
                _ui.update { it.copy(open = saved) }
                toast("Built and signed.")
            }
        }
    }

    fun patch(findingId: String) = withOpen { project ->
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return@withOpen
        val result = c.actions.patch(project, finding)
        val saved = result.project.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update {
            it.copy(
                open = saved,
                diff = result.diff.ifBlank { null },
                diffTitle = result.message
            )
        }
        log("info", result.message)
        refreshProjects()
    }

    fun preview(findingId: String) {
        val project = _ui.value.open ?: return
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return
        viewModelScope.launch {
            val diff = withContext(Dispatchers.IO) { c.actions.preview(project, finding) }
            _ui.update {
                it.copy(
                    diffTitle = finding.title,
                    diff = diff.ifBlank { "No change is possible for this hit." }
                )
            }
        }
    }

    fun replaceEndpoint(findingId: String, newUrl: String) = withOpen { project ->
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return@withOpen
        val result = c.actions.replaceEndpoint(project, finding, newUrl)
        val saved = result.project.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved, diff = result.diff.ifBlank { null }, diffTitle = result.message) }
        log("info", result.message)
    }

    fun dismiss(findingId: String) {
        val project = _ui.value.open ?: return
        val updated = project.copy(
            findings = project.findings.map { if (it.id == findingId) it.copy(dismissed = !it.dismissed) else it }
        )
        _ui.update { it.copy(open = updated) }
        viewModelScope.launch { c.projects.save(updated) }
    }

    fun revert(patchId: String) = withOpen { project ->
        val updated = c.patcher.revert(project, patchId)
        val saved = updated.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved) }
        toast("Patch reverted.")
    }

    fun revertAll() = withOpen { project ->
        val updated = c.patcher.revertAll(project)
        val saved = updated.copy(logs = _ui.value.logs)
        c.projects.save(saved)
        _ui.update { it.copy(open = saved) }
        toast("Every patch reverted.")
    }

    fun clearDiff() = _ui.update { it.copy(diff = null) }

    // ---------- editor ----------

    fun openFile(path: String) {
        val project = _ui.value.open ?: return
        viewModelScope.launch {
            val decode = c.projects.decodeDir(project.id)
            val text = withContext(Dispatchers.IO) { runCatching { File(decode, path).readText() }.getOrNull() }
            if (text == null) {
                toast("That file could not be opened.")
                return@launch
            }
            val relative = path.substringAfter('/', "")
            val javaPath = "java/" + relative.removeSuffix(".smali") + ".java"
            val java = withContext(Dispatchers.IO) {
                runCatching { File(c.projects.javaDir(project.id), javaPath).readText() }.getOrNull()
            }
            _ui.update {
                it.copy(
                    editorPath = path,
                    editorText = text,
                    javaText = java.orEmpty(),
                    javaAvailable = java != null
                )
            }
        }
    }

    fun closeEditor() = _ui.update { it.copy(editorPath = null, editorText = "", javaText = "") }

    fun saveEditor(text: String) {
        val project = _ui.value.open ?: return
        val path = _ui.value.editorPath ?: return
        viewModelScope.launch {
            val file = File(c.projects.decodeDir(project.id), path)
            withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
            val record = PatchRecord(
                id = Ids.newId(),
                title = "Manual edit",
                file = path,
                kind = PatchKind.NOTE,
                diff = "Edited by hand in the editor",
                appliedAt = System.currentTimeMillis()
            )
            val updated = project.copy(patches = project.patches + record, logs = _ui.value.logs)
            c.projects.save(updated)
            _ui.update { it.copy(open = updated, editorPath = null, editorText = "", javaText = "") }
            toast("Saved $path")
        }
    }

    // ---------- output ----------

    fun installOutput() {
        val project = _ui.value.open ?: return
        val path = project.outputApk
        if (path == null) {
            toast("Build the project first.")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { c.installer.install(File(path)) }
            result.onFailure { toast(it.message ?: "The installer could not be opened.") }
            result.onSuccess { toast("Installer opened.") }
        }
    }

    fun export() {
        val project = _ui.value.open ?: return
        if (project.crashLooping) {
            toast("The clone still crash-loops, so export is refused.")
            return
        }
        val path = project.outputApk
        if (path == null) {
            toast("Build the project first.")
            return
        }
        launchWork("Exporting") {
            val file = File(path)
            if (!file.isFile) {
                toast("The built file is gone, rebuild it first.")
                return@launchWork
            }
            val copy = c.projects.exportCopy(project, file)
            val updated = project.copy(exported = true, logs = _ui.value.logs)
            c.projects.save(updated)
            _ui.update { it.copy(open = updated) }
            log("info", "Exported to ${copy.absolutePath}")
            toast("Saved to ${copy.name}")
        }
    }

    fun writeReport() {
        val project = _ui.value.open ?: return
        launchWork("Writing the report") {
            val verdict = when {
                project.crashLooping -> "still broken"
                project.exported -> "exported"
                else -> "not installed"
            }
            val text = c.reporter.markdown(project.copy(logs = _ui.value.logs), verdict)
            c.projects.writeReport(project, text)
            val updated = project.copy(report = text, logs = _ui.value.logs)
            c.projects.save(updated)
            _ui.update { it.copy(open = updated, reportOpen = true) }
            toast("Report written into the project folder.")
        }
    }

    // ---------- settings ----------

    fun setDryRun(value: Boolean) {
        _ui.update { it.copy(settings = it.settings.copy(dryRun = value)) }
        viewModelScope.launch { c.settings.setDryRun(value) }
    }

    fun setRenameClones(value: Boolean) {
        _ui.update { it.copy(settings = it.settings.copy(renameClones = value)) }
        viewModelScope.launch { c.settings.setRenameClones(value) }
    }

    fun setAutoScan(value: Boolean) {
        _ui.update { it.copy(settings = it.settings.copy(autoScan = value)) }
        viewModelScope.launch { c.settings.setAutoScan(value) }
    }

    fun setRoundLimit(value: Int) {
        _ui.update { it.copy(settings = it.settings.copy(roundLimit = value)) }
        viewModelScope.launch { c.settings.setRoundLimit(value) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BypassViewModel(container) as T
            }
    }
}
