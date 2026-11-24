package com.arin.bypass.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arin.bypass.data.AppContainer
import com.arin.bypass.engine.Pipeline
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

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
        viewModelScope.launch {
            refresh()
            val remembered = withContext(Dispatchers.IO) {
                runCatching { container.settings.lastProject() }.getOrNull()
            }
            val fallback = withContext(Dispatchers.IO) {
                runCatching { container.projects.list().firstOrNull()?.id }.getOrNull()
            }
            val target = remembered ?: fallback
            if (target != null && _state.value.open == null) open(target)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) {
                runCatching { container.projects.list() }.getOrDefault(emptyList())
            }
            val settings = withContext(Dispatchers.IO) {
                runCatching { container.settings.read() }.getOrDefault(Settings())
            }
            val ready = withContext(Dispatchers.IO) {
                runCatching { container.toolchain.ready() }.getOrDefault(false)
            }
            val watcher = watcherEnabled()
            val port = withContext(Dispatchers.IO) {
                runCatching { container.settings.port(_state.value.port) }.getOrDefault(_state.value.port)
            }
            _state.update {
                it.copy(
                    loading = false,
                    projects = projects,
                    settings = settings,
                    toolsReady = ready,
                    watcherOn = watcher,
                    mockRunning = runCatching { container.mock.running }.getOrDefault(false),
                    port = port
                )
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun setPort(port: Int) {
        val safe = port.coerceIn(1024, 65535)
        _state.update { it.copy(port = safe) }
        viewModelScope.launch {
            runCatching { container.settings.setPort(safe) }
                .onFailure { CrashLog.write("save port", it) }
        }
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
            runCatching { container.settings.setLastProject(id) }
                .onFailure { CrashLog.write("save last project", it) }
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

    // ---- engine bridge: the pipeline steps are found by name, so a renamed
    // ---- step can never break the build of this screen again.

    private fun findStep(names: List<String>, types: Array<Class<*>>): Method? {
        val variants = ArrayList<Array<Class<*>>>()
        variants.add(types + Function1::class.java)
        variants.add(types)
        if (types.size > 1) variants.add(types.dropLast(1).toTypedArray() + Function1::class.java)
        for (name in names) {
            for (params in variants) {
                runCatching { Pipeline::class.java.getMethod(name, *params, Continuation::class.java) }
                    .getOrNull()?.let { return it }
                runCatching { Pipeline::class.java.getMethod(name, *params) }
                    .getOrNull()?.let { return it }
            }
        }
        return null
    }

    private suspend fun invokeStep(method: Method, args: Array<out Any?>): Any? {
        val parameters = method.parameterTypes
        if (parameters.isEmpty() || parameters.last() != Continuation::class.java) {
            return try {
                method.invoke(container.pipeline, *args)
            } catch (t: java.lang.reflect.InvocationTargetException) {
                throw (t.cause ?: t)
            }
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                method.invoke(container.pipeline, *args, continuation)
            } catch (t: java.lang.reflect.InvocationTargetException) {
                continuation.resumeWith(Result.failure(t.cause ?: t))
            } catch (t: Throwable) {
                continuation.resumeWith(Result.failure(t))
            }
        }
    }

    private suspend fun callStep(names: List<String>, vararg args: Any?): Any? {
        val types: Array<Class<*>> = Array(args.size) { index -> args[index]?.javaClass ?: Any::class.java }
        val method = findStep(names, types)
            ?: throw Exception("This build of the engine has no ${names.first()} step.")
        val raw = invokeStep(method, args)
        if (raw != null && raw.javaClass.name == "kotlin.Result") {
            @Suppress("UNCHECKED_CAST")
            return (raw as Result<Any?>).getOrElse { throw it }
        }
        return raw
    }

    private suspend fun stepProject(names: List<String>, vararg args: Any?): Project =
        callStep(names, *args) as? Project ?: throw Exception("The engine gave no project back.")

    // ---- helper calls are resolved by name as well, because the service listener
    // ---- and the screen reader are allowed to gain a target-package argument.

    private fun callHelper(target: Any?, name: String): Any? {
        if (target == null) return null
        val methods = runCatching { target.javaClass.methods.toList() }.getOrDefault(emptyList())
        val context = container.context
        val packageName = runCatching { _state.value.open?.packageName }.getOrNull() ?: context.packageName
        val two = methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 2 && it.parameterTypes[0] == Context::class.java
        }
        if (two != null) {
            return runCatching { two.invoke(target, context, packageName) }.getOrNull()
        }
        val one = methods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java
        }
        if (one != null) {
            return runCatching { one.invoke(target, context) }.getOrNull()
        }
        return null
    }

    private fun watcherEnabled(): Boolean {
        val listener = runCatching { container.watcher }.getOrNull() ?: return false
        return callHelper(listener, "isEnabled") as? Boolean ?: false
    }

    private fun probeReading(): String {
        val reader = runCatching { container.probe }.getOrNull()
        val text = callHelper(reader, "describe") as? String
        return text?.takeIf { it.isNotBlank() } ?: "No reading."
    }

    /**
     * Unpacking and scanning happen in one engine step, so this button and the scan
     * button both drive it; whichever one is pressed gives a full, fresh picture.
     */
    fun decode() = run("Unpacking and scanning") { project, log ->
        container.pipeline.prepare(project, log)
    }

    fun scan() = run("Scanning") { project, log ->
        container.pipeline.prepare(project, log)
    }

    fun sweep() = run("Integrity sweep") { project, log ->
        val prepared: Project = container.pipeline.prepare(project, log)
        _state.update { it.copy(notice = "The integrity checks run while unpacking and scanning.") }
        prepared
    }

    fun applyBilling() = run("Billing patch") { project, log ->
        stepProject(listOf("patchAll", "patchCategory", "applyBilling", "billing"), project, HitCategory.BILLING, log)
    }

    fun repairManifest() = run("Manifest repair") { project, log ->
        stepProject(listOf("repair", "repairManifest", "fixManifest"), project, log)
    }

    fun patchCategory(category: HitCategory) = run("Patching ${category.name.lowercase()}") { project, log ->
        stepProject(listOf("patchAll", "patchCategory", "category"), project, category, log)
    }

    fun rename(newPackage: String) = run("Renaming") { project, log ->
        if (newPackage.isBlank()) throw Exception("Type a package name first.")
        if (!newPackage.contains('.')) throw Exception("A package name needs at least one dot.")
        stepProject(listOf("rename", "renamePackage", "changePackage"), project, newPackage.trim(), log)
    }

    fun automate() = run("Automatic bypass") { project, log ->
        val result = stepProject(listOf("automate", "bypass", "runBypass", "autoBypass"), project, log)
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
        val apk = callStep(listOf("build", "rebuild", "compile"), project, log) as? File
            ?: throw Exception("The rebuild did not hand back a file.")
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
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val markdown = container.reporter.markdown(project.copy(logs = _state.value.open?.logs ?: project.logs))
                    runCatching { container.projects.writeReport(project, markdown) }
                    markdown
                }.getOrDefault("")
            }
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
            val on = withContext(Dispatchers.IO) { watcherEnabled() }
            _state.update { it.copy(watcherOn = on) }
        }
    }

    fun openWatcherSettings() {
        val listener = runCatching { container.watcher }.getOrNull()
        val opened = callHelper(listener, "open") as? Boolean ?: false
        if (!opened) {
            _state.update { it.copy(error = "The accessibility settings page could not be opened.") }
        }
    }

    fun readProbe() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { probeReading() }
            val on = withContext(Dispatchers.IO) { watcherEnabled() }
            _state.update { it.copy(probeText = text, watcherOn = on) }
        }
    }

    fun startMock() {
        viewModelScope.launch {
            val port = _state.value.port
            val result = withContext(Dispatchers.IO) { container.mock.start(port) }
            result.onSuccess {
                _state.update { it.copy(mockRunning = true, notice = "The mock server is listening on 127.0.0.1:$port") }
            }.onFailure { fail("mock", it) }
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
            _state.update { it.copy(mockLog = lines, mockRunning = runCatching { container.mock.running }.getOrDefault(false)) }
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
