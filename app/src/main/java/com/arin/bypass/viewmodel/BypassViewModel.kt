package com.arin.bypass.viewmodel

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.arin.bypass.data.AppContainer
import com.arin.bypass.engine.Diff
import com.arin.bypass.engine.InstalledReader
import com.arin.bypass.engine.RepairLoop
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
import kotlinx.coroutines.withContext
import java.io.File

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
            val settings = container.settings.read()
            val list = withContext(Dispatchers.IO) { container.projects.list() }
            _ui.value = _ui.value.copy(settings = settings, projects = list, toolsReady = container.toolchain.ready())
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
        withContext(Dispatchers.IO) { container.projects.save(withLogs) }
        _ui.value = _ui.value.copy(current = withLogs)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { container.projects.list() }
            _ui.value = _ui.value.copy(projects = list)
        }
    }

    fun loadInstalledApps() {
        if (_ui.value.loadingApps) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingApps = true)
            val list = withContext(Dispatchers.IO) { InstalledReader.list(container.context) }
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
            val project = withContext(Dispatchers.IO) { container.projects.load(id) }
            _ui.value = _ui.value.copy(
                current = project,
                logs = project?.logs ?: emptyList(),
                diff = "",
                editorFile = null,
                editorText = "",
                javaText = ""
            )
            _ui.value = _ui.value.copy(toolsReady = container.toolchain.ready())
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.projects.delete(id) }
            if (_ui.value.current?.id == id) _ui.value = _ui.value.copy(current = null)
            refresh()
        }
    }

    fun setDryRun(value: Boolean) {
        viewModelScope.launch {
            container.settings.setDryRun(value)
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(dryRun = value))
            val project = _ui.value.current
            if (project != null) persist(project.copy(dryRun = value))
        }
    }

    fun setRenameClones(value: Boolean) {
        viewModelScope.launch {
            container.settings.setRenameClones(value)
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(renameClones = value))
        }
    }

    fun setAutoScan(value: Boolean) {
        viewModelScope.launch {
            container.settings.setAutoScan(value)
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(autoScan = value))
        }
    }

    fun setRoundLimit(value: Int) {
        viewModelScope.launch {
            container.settings.setRoundLimit(value)
            _ui.value = _ui.value.copy(settings = _ui.value.settings.copy(roundLimit = value.coerceIn(1, 6)))
        }
    }

    fun openInstallPermission() {
        viewModelScope.launch {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${container.context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { container.context.startActivity(intent) }.isSuccess
            if (!opened) {
                val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS)
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
                container.settings.setToolchainReady(true)
                _ui.value = _ui.value.copy(toolsReady = true, message = "Tools are ready.")
            }
        }
    }

    fun prepare() {
        val project = _ui.value.current ?: return
        startWork("Unpacking and scanning") {
            val result = container.pipeline.prepare(project) { line ->
                addLog("tool", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 2).coerceAtMost(95))
            }
            val updated = result.getOrElse {
                addLog("error", Trouble.plain(it))
                _ui.value = _ui.value.copy(message = Trouble.plain(it), toolsReady = container.toolchain.ready())
                return@startWork
            }
            _ui.value = _ui.value.copy(toolsReady = true, progress = 100)
            persist(updated.copy(dryRun = _ui.value.settings.dryRun))
            _ui.value = _ui.value.copy(message = "Scan finished: ${updated.findings.size} hits.")
        }
    }

    fun runBypass() {
        val project = _ui.value.current ?: return
        startWork("Running the bypass") {
            val before = project.patches.size
            val result = container.pipeline.bypass(project) { line ->
                addLog("patch", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 4).coerceAtMost(90))
            }
            val updated = result.getOrElse {
                addLog("error", Trouble.plain(it))
                _ui.value = _ui.value.copy(message = Trouble.plain(it))
                return@startWork
            }
            persist(updated)
            val made = updated.patches.size - before
            _ui.value = _ui.value.copy(
                progress = 100,
                message = if (made == 0) "Nothing could be patched automatically." else "$made changes applied, see the diff."
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
            val diff = withContext(Dispatchers.IO) { container.actions.preview(project, finding) }
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
            val result = container.pipeline.rebuild(project) { line ->
                addLog("build", line)
                _ui.value = _ui.value.copy(progress = (_ui.value.progress + 5).coerceAtMost(92))
            }
            val pair = result.getOrElse {
                addLog("error", Trouble.plain(it))
                _ui.value = _ui.value.copy(message = Trouble.plain(it))
                return@startWork
            }
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
            var current = project.copy(crashLooping = false, stillBroken = emptyList())
            val limit = _ui.value.settings.roundLimit.coerceIn(1, 6)
            var deaths = 0
            var round = 0
            while (round < limit) {
                round++
                current = current.copy(rounds = round)
                addLog("build", "Round $round of $limit")
                val built = container.pipeline.rebuild(current) { line ->
                    addLog("build", line)
                    _ui.value = _ui.value.copy(progress = ((round - 1) * 100 / limit) + 5)
                }
                val pair = built.getOrElse {
                    addLog("error", Trouble.plain(it))
                    current = current.copy(stillBroken = current.stillBroken + "Round $round: ${Trouble.plain(it)}")
                    persist(current)
                    _ui.value = _ui.value.copy(message = Trouble.plain(it))
                    return@startWork
                }
                current = pair.first
                persist(current)

                val installerResult = container.installer.install(pair.second)
                if (installerResult.isFailure) {
                    _ui.value = _ui.value.copy(
                        message = Trouble.plain(
                            installerResult.exceptionOrNull() ?: Exception("The installer did not open.")
                        )
                    )
                    return@startWork
                }
                val target = current.renameTo ?: current.packageName
                val verdict = container.loop.launchAndWatch(target) { addLog("loop", it) }
                when (verdict) {
                    RepairLoop.Verdict.ALIVE -> {
                        addLog("loop", "The clone started and stayed up")
                        persist(current.copy(stage = Stage.DONE, crashLooping = false, rounds = round))
                        _ui.value = _ui.value.copy(progress = 100, message = "The clone runs. Export is unlocked.")
                        return@startWork
                    }
                    RepairLoop.Verdict.DIED -> {
                        deaths++
                        current = current.copy(stillBroken = current.stillBroken + "Round $round: the clone died after launch.")
                        if (deaths >= 3) {
                            persist(current.copy(crashLooping = true))
                            _ui.value = _ui.value.copy(message = "Still crashing after $deaths tries. Export stays blocked.")
                            return@startWork
                        }
                    }
                    RepairLoop.Verdict.PAYWALL -> {
                        addLog("loop", "Paywall still shows, patching entitlement again")
                        current = current.copy(stillBroken = current.stillBroken + "Round $round: paywall still shown.")
                        val again = container.pipeline.bypass(current) { addLog("patch", it) }
                        current = again.getOrDefault(current)
                        persist(current)
                    }
                    RepairLoop.Verdict.SPINNER -> {
                        addLog("loop", "Stuck spinner, neutralising the token checks again")
                        current = current.copy(stillBroken = current.stillBroken + "Round $round: stuck on a spinner.")
                        val again = container.pipeline.bypass(current) { addLog("patch", it) }
                        current = again.getOrDefault(current)
                        persist(current)
                    }
                    RepairLoop.Verdict.NO_LAUNCHER -> {
                        persist(current.copy(stage = Stage.REVIEW))
                        _ui.value = _ui.value.copy(message = "The clone has no launch screen, so the loop stopped.")
                        return@startWork
                    }
                }
            }
            persist(current.copy(crashLooping = true, stage = Stage.STOPPED))
            _ui.value = _ui.value.copy(message = "Six rounds reached. The diff is kept for you.")
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
            val updated = project.copy(exported = true)
            persist(updated)
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
            val text = withContext(Dispatchers.IO) { if (file.isFile) file.readText() else "" }
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
            val before = withContext(Dispatchers.IO) { file.readText() }
            val after = _ui.value.editorText
            if (before == after) {
                _ui.value = _ui.value.copy(message = "Nothing changed.")
                return@startWork
            }
            val copy = File(container.projects.backupDir(project.id), "$relPath.orig")
            withContext(Dispatchers.IO) {
                copy.parentFile?.mkdirs()
                if (!copy.exists()) copy.writeText(before)
                file.writeText(after)
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
                javaDir.walkTopDown().firstOrNull { it.isFile && it.name == "$simple.java" }
            }
            val text = if (match != null) withContext(Dispatchers.IO) { match.readText() }.take(60_000)
            else "No readable Java for this file. It is probably obfuscated or inlined."
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
