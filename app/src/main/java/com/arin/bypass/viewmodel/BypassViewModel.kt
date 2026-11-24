package com.arin.bypass.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arin.bypass.data.AppContainer
import com.arin.bypass.engine.Diff
import com.arin.bypass.engine.InstalledReader
import com.arin.bypass.engine.Pipeline
import com.arin.bypass.model.Finding
import com.arin.bypass.model.InstalledApp
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.PatchRecord
import com.arin.bypass.model.Project
import com.arin.bypass.model.Settings
import com.arin.bypass.model.Stage
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Method
import kotlin.coroutines.Continuation

class BypassViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val projects: List<Project> = emptyList(),
        val installed: List<InstalledApp> = emptyList(),
        val settings: Settings = Settings(),
        val current: Project? = null,
        val logs: List<LogLine> = emptyList(),
        val busy: Boolean = false,
        val progress: Int = 0,
        val progressText: String = "",
        val message: String? = null,
        val diff: String = "",
        val editorFile: String? = null,
        val editorText: String = "",
        val javaText: String = "",
        val loadingApps: Boolean = false,
        val toolsReady: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private var work: Job? = null

    init {
        viewModelScope.launch {
            val settings = runCatching { container.settings.read() }.getOrDefault(Settings())
            val list = withContext(Dispatchers.IO) { runCatching { container.projects.list() }.getOrDefault(emptyList()) }
            val ready = withContext(Dispatchers.IO) { runCatching { container.toolchain.ready() }.getOrDefault(false) }
            _ui.value = _ui.value.copy(settings = settings, projects = list, toolsReady = ready)
        }
    }

    suspend fun toast(message: String) {
        _ui.value = _ui.value.copy(message = message)
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    fun clearDiff() {
        _ui.value = _ui.value.copy(diff = "")
    }

    private fun addLog(level: String, message: String) {
        val trimmed = message.trim().take(400)
        if (trimmed.isEmpty()) return
        val next = (_ui.value.logs + LogLine(System.currentTimeMillis(), level, trimmed)).takeLast(400)
        _ui.value = _ui.value.copy(logs = next)
    }

    private suspend fun persist(project: Project) {
        val withLogs = project.copy(logs = _ui.value.logs.takeLast(400), updatedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { runCatching { container.projects.save(withLogs) } }
        _ui.value = _ui.value.copy(current = withLogs)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { container.projects.list() }.getOrDefault(emptyList()) }
            _ui.value = _ui.value.copy(projects = list)
        }
    }

    fun loadInstalledApps() {
        if (_ui.value.loadingApps) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingApps = true)
            val list = withContext(Dispatchers.IO) {
                runCatching { InstalledReader.list(container.context) }.getOrDefault(emptyList())
            }
            _ui.value = _ui.value.copy(installed = list, loadingApps = false)
        }
    }

    fun createFromInstalled(packageName: String) {
        startWork("Reading the installed app") {
            val result = container.intake.fromInstalled(packageName) { addLog("info", it) }
            val project = result.getOrElse {
                _ui.value = _ui.value.copy(message = Trouble.plain(it))
                return@startWork
            }
            addLog("info", "Project created: ${project.name}")
            refresh()
            _ui.value = _ui.value.copy(current = project, logs = project.logs, message = "Project ready. Open it from the list.")
        }
    }

    fun importApk(uri: Uri) {
        startWork("Copying the picked file") {
            val result = container.intake.fromUri(uri) { addLog("info", it) }
            val project = result.getOrElse {
                _ui.value = _ui.value.copy(message = Trouble.plain(it))
                return@startWork
            }
            refresh()
            _ui.value = _ui.value.copy(current = project, logs = project.logs, message = "Project created: ${project.name}")
        }
    }

    fun openProject(id: String) {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) { runCatching { container.projects.load(id) }.getOrNull() }
            _ui.value = _ui.value.copy(
                current = project,
                logs = project?.logs ?: emptyList(),
                diff = "",
                editorFile = null,
                editorText = "",
                javaText = ""
            )
            _ui.value = _ui.value.copy(toolsReady = runCatching { container.toolchain.ready() }.getOrDefault(false))
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { container.projects.delete(id) } }
            if (_ui.value.current?.id == id) _ui.value = _ui.value.copy(current = null)
            refresh()
        }
    }

    fun setDryRun(value: Boolean) {
        viewModelScope.launch {
            runCatching { container.settings.setDryRun(value) }
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(dryRun = value))
            val project = _ui.value.current
            if (project != null) persist(project.copy(dryRun = value))
        }
    }

    fun setRenameClones(value: Boolean) {
        viewModelScope.launch {
            runCatching { container.settings.setRenameClones(value) }
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(renameClones = value))
        }
    }

    fun setAutoScan(value: Boolean) {
        viewModelScope.launch {
            runCatching { container.settings.setAutoScan(value) }
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(autoScan = value))
        }
    }

    fun setRoundLimit(value: Int) {
        viewModelScope.launch {
            val clamped = value.coerceIn(1, 6)
            runCatching { container.settings.setRoundLimit(clamped) }
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(roundLimit = clamped))
        }
    }

    fun openInstallPermission() {
        viewModelScope.launch {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${container.context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { container.context.startActivity(intent) }.isSuccess
            if (!opened) {
                val fallback = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = runCatching { container.context.startActivity(fallback) }.isSuccess
                if (!ok) {
                    _ui.value = _ui.value.copy(
                        message = "The settings screen could not be opened. Open Settings → Apps → special access → install unknown apps yourself."
                    )
                }
            }
        }
    }

    fun installTools() {
        startWork("Installing the helper programs") {
            val result = container.toolchain.install { message, percent ->
                _ui.value = _ui.value.copy(progress = percent, progressText = message)
                addLog("tool", message)
            }
            if (result.isFailure) {
                _ui.value = _ui.value.copy(
                    message = Trouble.plain(result.exceptionOrNull() ?: Exception("The tools could not be installed."))
                )
            } else {
                runCatching { container.settings.setToolchainReady(true) }
                _ui.value = _ui.value.copy(toolsReady = true, message = "Tools are ready.")
            }
        }
    }

    // ---- engine bridge -------------------------------------------------------
    // The pipeline steps are looked up by name so a renamed or re-signatured step
    // can never break the build of this screen again. The step that unpacks is a
    // plain call: it hands the updated project straight back.

    private fun findStep(names: List<String>, withLog: Boolean): Method? {
        val lists: List<Array<Class<*>>> = if (withLog) {
            listOf(
                arrayOf<Class<*>>(Project::class.java, Function1::class.java),
                arrayOf<Class<*>>(Project::class.java)
            )
        } else {
            listOf(arrayOf<Class<*>>(Project::class.java))
        }
        for (name in names) {
            for (params in lists) {
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

    private suspend fun callStep(names: List<String>, project: Project, log: ((String) -> Unit)?): Any? {
        val method = findStep(names, log != null)
            ?: throw Exception("This build of the engine has no ${names.first()} step.")
        val declared = method.parameterTypes.filter { it != Continuation::class.java }
        val args: Array<out Any?> = if (declared.size >= 2 && log != null) {
            arrayOf<Any?>(project, log)
        } else {
            arrayOf<Any?>(project)
        }
        val raw = invokeStep(method, args)
        if (raw != null && raw.javaClass.name == "kotlin.Result") {
            @Suppress("UNCHECKED_CAST")
            return (raw as Result<Any?>).getOrElse { throw it }
        }
        return raw
    }

    private suspend fun stepProject(names: List<String>, project: Project, log: (String) -> Unit): Project {
        val raw = callStep(names, project, log)
        return raw as? Project ?: throw Exception("The engine gave no project back.")
    }

    /** Runs the build step and always hands back both the project and the built file. */
    private suspend fun buildStep(project: Project, log: (String) -> Unit): Pair<Project, File> {
        val raw = callStep(listOf("build", "rebuild", "compile"), project, log)
        return when (raw) {
            is File -> project to raw
            is Pair<*, *> -> {
                val next = raw.first as? Project ?: project
                val apk = raw.second as? File
                    ?: throw Exception("The rebuild did not hand back a file.")
                next to apk
            }
            else -> throw Exception("The rebuild did not hand back a file.")
        }
    }

    fun prepare() {
        val project = _ui.value.current ?: return
        startWork("Unpacking and scanning") {
            val updated = container.pipeline.prepare(project) { line ->
                addLog("tool", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 2).coerceAtMost(95))
            } ?: project
            _ui.value = _ui.value.copy(toolsReady = true, progress = 100)
            persist(updated.copy(dryRun = _ui.value.settings.dryRun))
            _ui.value = _ui.value.copy(message = "Scan finished: ${updated.findings.size} hits.")
        }
    }

    fun runBypass() {
        val project = _ui.value.current ?: return
        startWork("Running the bypass") {
            val before = project.patches.size
            val log: (String) -> Unit = { line ->
                addLog("patch", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 4).coerceAtMost(90))
            }
            val updated = stepProject(listOf("automate", "bypass", "runBypass", "autoBypass"), project, log)
            persist(updated)
            val made = updated.patches.size - before
            _ui.value = _ui.value.copy(
                progress = 100,
                message = if (made <= 0) "Nothing could be patched automatically." else "$made changes applied, see the diff."
            )
        }
    }

    fun patchFinding(finding: Finding) {
        val project = _ui.value.current ?: return
        startWork("Patching one hit") {
            val result = container.actions.patch(project, finding)
            addLog("patch", result.message)
            persist(result.project)
            _ui.value = _ui.value.copy(diff = result.diff, message = if (result.diff.isBlank()) result.message else null)
        }
    }

    fun previewFinding(finding: Finding) {
        val project = _ui.value.current ?: return
        viewModelScope.launch {
            val diff = withContext(Dispatchers.IO) {
                runCatching { container.actions.preview(project, finding) }.getOrDefault("")
            }
            _ui.value = _ui.value.copy(
                diff = diff,
                message = if (diff.isBlank()) "Nothing to preview for this hit." else null
            )
        }
    }

    fun replaceEndpoint(finding: Finding, newUrl: String) {
        val project = _ui.value.current ?: return
        startWork("Replacing the base URL") {
            val result = container.actions.replaceEndpoint(project, finding, newUrl)
            addLog("patch", result.message)
            persist(result.project)
            _ui.value = _ui.value.copy(diff = result.diff, message = if (result.diff.isBlank()) result.message else null)
        }
    }

    fun dismissFinding(finding: Finding) {
        val project = _ui.value.current ?: return
        viewModelScope.launch {
            persist(project.copy(findings = project.findings.map { if (it.id == finding.id) it.copy(dismissed = true) else it }))
        }
    }

    fun revertPatch(patchId: String) {
        val project = _ui.value.current ?: return
        startWork("Reverting one change") {
            val updated = container.patcher.revert(project, patchId)
            addLog("patch", "Reverted one change")
            persist(updated)
        }
    }

    fun revertAll() {
        val project = _ui.value.current ?: return
        startWork("Reverting everything") {
            val updated = container.patcher.revertAll(project)
            addLog("patch", "All changes reverted")
            persist(updated)
        }
    }

    fun rebuild() {
        val project = _ui.value.current ?: return
        startWork("Rebuilding the clone") {
            val log: (String) -> Unit = { line ->
                addLog("build", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 5).coerceAtMost(92))
            }
            val pair = buildStep(project, log)
            persist(pair.first)
            _ui.value = _ui.value.copy(progress = 100, message = "Build finished: ${pair.second.name}")
        }
    }

    fun installOutput() {
        val project = _ui.value.current ?: return
        startWork("Handing the file to the installer") {
            val apk = project.outputApk?.let { File(it) }
            if (apk == null || !apk.isFile) {
                _ui.value = _ui.value.copy(message = "Build the clone first.")
                return@startWork
            }
            val result = container.installer.install(apk)
            result.onSuccess { addLog("build", "Opened the installer") }
            result.onFailure { _ui.value = _ui.value.copy(message = Trouble.plain(it)) }
        }
    }

    fun runRepairLoop() {
        val project = _ui.value.current ?: return
        startWork("Repair loop") {
            val limit = _ui.value.settings.roundLimit.coerceIn(1, 6)
            val log: (String) -> Unit = { line ->
                addLog("loop", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 5).coerceAtMost(95))
            }
            val started = project.copy(crashLooping = false, stillBroken = emptyList())
            val ended = container.loop.run(started, limit, log)
            persist(ended.copy(stage = if (ended.crashLooping) Stage.STOPPED else Stage.DONE))
            _ui.value = _ui.value.copy(
                progress = 100,
                message = if (ended.crashLooping) {
                    "The clone still closes after $limit rounds. Export stays blocked."
                } else {
                    "The clone stayed open, so export is unlocked."
                }
            )
        }
    }

    fun exportApk() {
        val project = _ui.value.current ?: return
        if (project.crashLooping) {
            _ui.value = _ui.value.copy(message = "The app still crash-loops, so export is blocked. Run the repair loop again.")
            return
        }
        startWork("Exporting") {
            val apk = project.outputApk?.let { File(it) }
            if (apk == null || !apk.isFile) {
                _ui.value = _ui.value.copy(message = "Build the clone first.")
                return@startWork
            }
            val copy = withContext(Dispatchers.IO) { container.projects.exportCopy(project, apk) }
            persist(project.copy(exported = true))
            addLog("build", "Exported to ${copy.absolutePath}")
            _ui.value = _ui.value.copy(message = "Saved: ${copy.name}")
        }
    }

    fun writeReport() {
        val project = _ui.value.current ?: return
        startWork("Writing the report") {
            val markdown = container.reporter.markdown(project.copy(logs = _ui.value.logs))
            val file = withContext(Dispatchers.IO) { container.projects.writeReport(project, markdown) }
            persist(project.copy(report = markdown))
            addLog("info", "Report written to ${file.absolutePath}")
            _ui.value = _ui.value.copy(message = "Report saved in the project folder.")
        }
    }

    fun openEditor(relPath: String) {
        val project = _ui.value.current ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { File(container.projects.decodeDir(project.id), relPath) }
            val text = withContext(Dispatchers.IO) { if (file.isFile) runCatching { file.readText() }.getOrDefault("") else "" }
            _ui.value = _ui.value.copy(editorFile = relPath, editorText = text, diff = "")
        }
    }

    fun closeEditor() {
        _ui.value = _ui.value.copy(editorFile = null, editorText = "")
    }

    fun editText(value: String) {
        _ui.value = _ui.value.copy(editorText = value)
    }

    fun saveEditor() {
        val project = _ui.value.current ?: return
        val relPath = _ui.value.editorFile ?: return
        startWork("Saving the edit") {
            val file = File(container.projects.decodeDir(project.id), relPath)
            if (!file.isFile) {
                _ui.value = _ui.value.copy(message = "That file is gone from the project.")
                return@startWork
            }
            val before = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
            val after = _ui.value.editorText
            if (before == after) {
                _ui.value = _ui.value.copy(message = "Nothing changed.")
                return@startWork
            }
            val copy = File(container.projects.backupDir(project.id), "$relPath.orig")
            withContext(Dispatchers.IO) {
                copy.parentFile?.mkdirs()
                if (!copy.exists()) runCatching { copy.writeText(before) }
                runCatching { file.writeText(after) }
            }
            val record = PatchRecord(
                id = com.arin.bypass.model.Ids.newId(),
                title = "Hand edit of ${relPath.substringAfterLast('/')}",
                file = relPath,
                kind = PatchKind.NOTE,
                diff = Diff.unified(relPath, before, after),
                appliedAt = System.currentTimeMillis()
            )
            persist(project.copy(patches = project.patches + record))
            _ui.value = _ui.value.copy(diff = record.diff, message = "Saved with a backup.")
        }
    }

    fun loadJava(smaliRelPath: String) {
        val project = _ui.value.current ?: return
        viewModelScope.launch {
            val javaDir = container.projects.javaDir(project.id)
            val simple = smaliRelPath.substringAfterLast('/').removeSuffix(".smali")
            val match = withContext(Dispatchers.IO) {
                runCatching { javaDir.walkTopDown().firstOrNull { it.isFile && it.name == "$simple.java" } }.getOrNull()
            }
            val text = if (match != null) {
                withContext(Dispatchers.IO) { runCatching { match.readText() }.getOrDefault("") }.take(60_000)
            } else {
                "No readable Java for this file. It is probably obfuscated or inlined."
            }
            _ui.value = _ui.value.copy(javaText = text)
        }
    }

    fun clearJava() {
        _ui.value = _ui.value.copy(javaText = "")
    }

    fun cancel() {
        work?.cancel()
        work = null
        val project = _ui.value.current
        _ui.value = _ui.value.copy(busy = false, progress = 0, progressText = "", message = "Stopped. The project is kept and can be resumed.")
        if (project != null) viewModelScope.launch { persist(project.copy(stopped = true, stage = Stage.STOPPED)) }
    }

    private fun startWork(label: String, block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, progress = 0, progressText = label, message = null)
            try {
                block()
            } catch (e: CancellationException) {
                addLog("info", "Cancelled")
                throw e
            } catch (e: Exception) {
                addLog("error", Trouble.plain(e))
                _ui.value = _ui.value.copy(message = Trouble.plain(e))
            } finally {
                _ui.value = _ui.value.copy(busy = false, progress = 0, progressText = "")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { BypassViewModel(container) }
        }
    }
}
