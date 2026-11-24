package com.arin.bypass.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arin.bypass.data.AppContainer
import com.arin.bypass.model.Finding
import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.Project
import com.arin.bypass.model.Settings
import com.arin.bypass.model.Stage
import com.arin.bypass.util.CrashLog
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

data class UiState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val settings: Settings = Settings(),
    val open: Project? = null,
    val busy: Boolean = false,
    val progressLabel: String = "",
    val error: String? = null,
    val notice: String? = null,
    val preview: String = "",
    val toolsReady: Boolean = false,
    val toolProgress: Int = 0,
    val toolLabel: String = "",
    val watcherOn: Boolean = false,
    val probeText: String = "",
    val mockRunning: Boolean = false,
    val mockLog: List<String> = emptyList(),
    val port: Int = 8899
)

class BypassViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loopJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            runCatching { container.bypass.read().lastProject }
                .getOrNull()
                ?.let { id -> if (container.projects.load(id) != null) open(id) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) { container.projects.list() }
            val settings = runCatching { container.settings.read() }.getOrDefault(Settings())
            val session = runCatching { container.bypass.read() }.getOrNull()
            val ready = withContext(Dispatchers.IO) { runCatching { container.toolchain.ready() }.getOrDefault(false) }
            _state.update {
                it.copy(
                    loading = false,
                    projects = projects,
                    settings = settings,
                    toolsReady = ready,
                    port = session?.mockPort ?: 8899,
                    watcherOn = runCatching { container.watcher.isEnabled(container.context) }.getOrDefault(false)
                )
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun setPort(port: Int) {
        val safe = port.coerceIn(1024, 65535)
        _state.update { it.copy(port = safe) }
        container.bypass.setPort(safe)
    }

    // ---- projects ----

    fun importApk(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progressLabel = "Reading the picked file", error = null) }
            val result = container.intake.fromUri(uri) { line -> pushLog(line) }
            _state.update { it.copy(busy = false, progressLabel = "") }
            result.onSuccess { project ->
                saveProject(project)
                _state.update { it.copy(notice = "Project ${project.name} created", projects = listOf(project) + it.projects) }
                open(project.id)
            }.onFailure { fail("import", it) }
        }
    }

    fun importInstalled(packageName: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progressLabel = "Copying the installed app", error = null) }
            val result = container.intake.fromInstalled(packageName) { line -> pushLog(line) }
            _state.update { it.copy(busy = false, progressLabel = "") }
            result.onSuccess { project ->
                saveProject(project)
                _state.update { it.copy(notice = "Project ${project.name} created", projects = listOf(project) + it.projects) }
                open(project.id)
            }.onFailure { fail("import installed", it) }
        }
    }

    fun open(id: String) {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) { container.projects.load(id) }
            if (project == null) {
                _state.update { it.copy(error = "That project could not be read.") }
                return@launch
            }
            container.bypass.setLastProject(id)
            _state.update { it.copy(open = project, preview = "") }
        }
    }

    fun close() {
        loopJob?.cancel()
        _state.update { it.copy(open = null, preview = "") }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.projects.delete(id) }
            if (_state.value.open?.id == id) _state.update { it.copy(open = null) }
            refresh()
        }
    }

    // ---- pipeline ----

    private fun pushLog(line: String, level: String = "info") {
        _state.update { current ->
            val project = current.open ?: return@update current
            val entry = LogLine(System.currentTimeMillis(), level, line)
            current.copy(open = project.copy(logs = (project.logs + entry).takeLast(400)))
        }
    }

    private fun logSink(): (String) -> Unit = { line -> pushLog(line) }

    private suspend fun saveProject(project: Project) {
        withContext(Dispatchers.IO) { runCatching { container.projects.save(project) } }
    }

    private suspend fun persist(project: Project) {
        val merged = withContext(Dispatchers.IO) {
            val fresh = runCatching { container.projects.load(project.id) }.getOrNull()
            val logs = listOfNotNull(fresh?.logs).maxByOrNull { it.size } ?: project.logs
            project.copy(logs = if (logs.size > project.logs.size) logs else project.logs)
        }
        _state.update { current ->
            current.copy(
                open = if (current.open?.id == project.id) merged else current.open,
                projects = current.projects.map { if (it.id == merged.id) merged else it }
            )
        }
        saveProject(merged)
    }

    private fun run(label: String, block: suspend (Project, (String) -> Unit) -> Project?) {
        val project = _state.value.open ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progressLabel = label, error = null) }
            try {
                val result = block(project, logSink())
                if (result != null) persist(result)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(label, t)
            } finally {
                _state.update { it.copy(busy = false, progressLabel = "") }
            }
        }
    }

    private fun fail(tag: String, t: Throwable) {
        CrashLog.write(tag, t)
        _state.update { it.copy(error = Trouble.plain(t), busy = false, progressLabel = "") }
    }

    fun decode() = run("Unpacking") { project, log ->
        container.pipeline.decode(project, log).getOrElse { throw it }
    }

    fun scan() = run("Scanning") { project, log ->
        container.pipeline.scan(project, log).getOrElse { throw it }
    }

    fun sweep() = run("Integrity sweep") { project, log ->
        container.pipeline.sweep(project, log).getOrElse { throw it }
    }

    fun applyBilling() = run("Billing patch") { project, log ->
        container.pipeline.applyBilling(project, log).getOrElse { throw it }
    }

    fun repairManifest() = run("Manifest repair") { project, log ->
        container.pipeline.repair(project, log).getOrElse { throw it }
    }

    fun patchCategory(category: HitCategory) = run("Patching ${category.name.lowercase()}") { project, log ->
        container.pipeline.patchAll(project, category, log).getOrElse { throw it }
    }

    fun rename(newPackage: String) = run("Renaming") { project, log ->
        if (newPackage.isBlank()) throw Exception("Type a package name first.")
        if (!newPackage.contains('.')) throw Exception("A package name needs at least one dot.")
        container.pipeline.rename(project, newPackage.trim(), log).getOrElse { throw it }
    }

    fun automate() = run("Automatic bypass") { project, log ->
        val result = container.pipeline.automate(project, log).getOrElse { throw it }
        _state.update { it.copy(notice = "The automatic run finished. Review the patch list.") }
        result
    }

    fun patchFinding(finding: Finding) = run("Patching ${finding.title}") { project, _ ->
        val result = container.actions.patch(project, finding)
        _state.update { it.copy(notice = result.message, preview = result.diff.ifBlank { it.preview }) }
        result.project
    }

    fun previewFinding(finding: Finding) {
        val project = _state.value.open ?: return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { container.actions.preview(project, finding) }.getOrDefault("") }
            _state.update {
                it.copy(
                    preview = text.ifBlank { "Nothing to preview for this hit." },
                    notice = "Preview for ${finding.title}"
                )
            }
        }
    }

    fun dismissFinding(finding: Finding) = run("Dismissing") { project, _ ->
        project.copy(findings = project.findings.map { if (it.id == finding.id) it.copy(dismissed = true) else it })
    }

    fun replaceEndpoint(finding: Finding, url: String) = run("Changing the base URL") { project, _ ->
        if (url.isBlank()) throw Exception("Type the new URL first.")
        val result = container.actions.replaceEndpoint(project, finding, url.trim())
        _state.update { it.copy(notice = result.message) }
        result.project
    }

    fun revertPatch(patchId: String) = run("Undoing") { project, _ ->
        container.patcher.revert(project, patchId)
    }

    fun revertAll() = run("Undoing everything") { project, _ ->
        container.patcher.revertAll(project)
    }

    fun build() = run("Rebuilding") { project, log ->
        val apk = container.pipeline.build(project, log).getOrElse { throw it }
        _state.update { it.copy(notice = "Built ${apk.name}") }
        project.copy(stage = Stage.REBUILD, outputApk = apk.absolutePath)
    }

    fun install() {
        val project = _state.value.open ?: return
        viewModelScope.launch {
            val path = project.outputApk
            if (path == null) {
                _state.update { it.copy(error = "Build the project first, then install.") }
                return@launch
            }
            if (!container.installer.canInstall() && !container.installer.openPermission()) {
                _state.update { it.copy(error = "Allow installing unknown apps for Arin, then press install again.") }
                return@launch
            }
            val result = container.installer.install(File(path))
            result.onFailure { fail("install", it) }
                .onSuccess { _state.update { it.copy(notice = "The installer should be open now.") } }
        }
    }

    fun export() {
        val project = _state.value.open ?: return
        viewModelScope.launch {
            val path = project.outputApk
            if (path == null) {
                _state.update { it.copy(error = "Build the project first, then export.") }
                return@launch
            }
            val target = withContext(Dispatchers.IO) { runCatching { container.projects.exportCopy(project, File(path)) }.getOrNull() }
            if (target == null) {
                _state.update { it.copy(error = "The export could not be copied.") }
            } else {
                _state.update { it.copy(notice = "Exported to ${target.absolutePath}", open = project.copy(exported = true)) }
            }
        }
    }

    fun regenerateReport() {
        val project = _state.value.open ?: return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { container.pipeline.writeReport(project) }.getOrDefault("") }
            _state.update { it.copy(open = project.copy(report = text), notice = "Report written") }
        }
    }

    fun startLoop() {
        val project = _state.value.open ?: return
        if (loopJob?.isActive == true) return
        loopJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, progressLabel = "Repair loop", error = null) }
            try {
                val ended = container.loop.run(project, _state.value.settings.roundLimit, logSink())
                persist(ended)
                _state.update { it.copy(notice = if (ended.crashLooping) "Round limit reached, it still closes." else "The loop finished.") }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail("loop", t)
            } finally {
                _state.update { it.copy(busy = false, progressLabel = "") }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.update { it.copy(busy = false, progressLabel = "") }
    }

    // ---- tools ----

    fun installTools() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, toolProgress = 0) }
            val result = container.toolchain.install { label, percent ->
                _state.update { it.copy(toolLabel = label, toolProgress = percent) }
            }
            val ready = withContext(Dispatchers.IO) { runCatching { container.toolchain.ready() }.getOrDefault(false) }
            runCatching { container.settings.setToolchainReady(ready) }
            _state.update { it.copy(busy = false, toolsReady = ready, toolLabel = "") }
            result.onSuccess { _state.update { it.copy(notice = "The tools are installed.") } }
                .onFailure { fail("tools", it) }
        }
    }

    fun refreshWatcher() {
        viewModelScope.launch {
            val on = withContext(Dispatchers.IO) { runCatching { container.watcher.isEnabled(container.context) }.getOrDefault(false) }
            _state.update { it.copy(watcherOn = on) }
        }
    }

    fun openWatcherSettings() {
        if (!container.watcher.open(container.context)) {
            _state.update { it.copy(error = "The accessibility settings page could not be opened.") }
        }
    }

    fun readProbe() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { container.probe.describe(container.context) }.getOrDefault("No reading.") }
            _state.update { it.copy(probeText = text, watcherOn = runCatching { container.watcher.isEnabled(container.context) }.getOrDefault(false)) }
        }
    }

    fun startMock() {
        viewModelScope.launch {
            val port = _state.value.port
            val result = withContext(Dispatchers.IO) { container.mock.start(port) }
            result.onSuccess { _state.update { it.copy(mockRunning = true, notice = "The mock server is listening on 127.0.0.1:$port") } }
                .onFailure { fail("mock", it) }
            readMockLog()
        }
    }

    fun stopMock() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.mock.stop() }
            _state.update { it.copy(mockRunning = false) }
            readMockLog()
        }
    }

    fun readMockLog() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) { runCatching { container.mock.log() }.getOrDefault(emptyList()) }
            _state.update { it.copy(mockLog = lines, mockRunning = container.mock.running) }
        }
    }

    // ---- settings ----

    fun setDryRun(value: Boolean) = viewModelScope.launch {
        runCatching { container.settings.setDryRun(value) }
        _state.update { it.copy(settings = it.settings.copy(dryRun = value)) }
    }

    fun setRenameClones(value: Boolean) = viewModelScope.launch {
        runCatching { container.settings.setRenameClones(value) }
        _state.update { it.copy(settings = it.settings.copy(renameClones = value)) }
    }

    fun setAutoScan(value: Boolean) = viewModelScope.launch {
        runCatching { container.settings.setAutoScan(value) }
        _state.update { it.copy(settings = it.settings.copy(autoScan = value)) }
    }

    fun setRoundLimit(value: Int) = viewModelScope.launch {
        val safe = value.coerceIn(1, 6)
        runCatching { container.settings.setRoundLimit(safe) }
        _state.update { it.copy(settings = it.settings.copy(roundLimit = safe)) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BypassViewModel(container) }
        }
    }
}
