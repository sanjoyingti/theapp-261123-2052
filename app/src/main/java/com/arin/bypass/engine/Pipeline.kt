package com.arin.bypass.engine

import com.arin.bypass.data.ProjectStore
import com.arin.bypass.data.SettingsStore
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import com.arin.bypass.util.Trouble
import java.io.File

/**
 * Ordered stages of one project. Every stage is guarded so a failure stops at a
 * readable stage instead of leaving a half written project behind.
 */
class Pipeline(
    private val store: ProjectStore,
    private val settings: SettingsStore,
    private val decoder: Decoder,
    private val scanner: Scanner,
    private val integrity: Integrity,
    private val billing: Billing,
    private val actions: Actions,
    private val repairs: ManifestRepair,
    private val renamer: Renamer,
    private val builder: Rebuilder,
    private val reporter: Report
) {

    private fun line(project: Project, message: String): Project =
        project.copy(
            logs = (project.logs + LogLine(System.currentTimeMillis(), "i", message)).takeLast(400)
        )

    private suspend fun save(project: Project): Project {
        val trimmed = project.copy(updatedAt = System.currentTimeMillis())
        store.save(trimmed)
        return trimmed
    }

    suspend fun prepare(project: Project, onLog: (String) -> Unit): Project {
        var current = project
        try {
            val decodeDir: File = store.decodeDir(current.id)
            val manifest = File(decodeDir, "AndroidManifest.xml")
            if (!manifest.exists()) {
                current = save(current.copy(stage = Stage.DECODE))
                onLog("Unpacking the app")
                val apk = store.workingFile(current)
                if (!apk.isFile) throw Exception("The working copy of the app is missing.")
                val decoded = decoder.decode(apk, decodeDir, store.javaDir(current.id)) { line ->
                    onLog(line)
                }
                if (decoded.isFailure) {
                    throw Exception(decoded.exceptionOrNull()?.message ?: "Unpacking failed.")
                }
                current = save(line(current, "Unpacked"))
            }

            if (current.findings.isEmpty()) {
                current = save(current.copy(stage = Stage.SCAN))
                onLog("Scanning for server side checks")
                val found = scanner.scan(decodeDir, store.javaDir(current.id)) { onLog(it) }
                current = save(current.copy(findings = found, stage = Stage.INTEGRITY))
                onLog("Found ${found.size} hits")
            }

            val config = settings.read()
            current = save(current.copy(stage = Stage.INTEGRITY))
            val (afterIntegrity, integrityNotes) = integrity.sweep(current, decodeDir) { onLog(it) }
            current = save(afterIntegrity)
            integrityNotes.forEach { onLog(it) }

            current = save(current.copy(stage = Stage.BILLING))
            val (afterBilling, billingNotes) = billing.apply(current) { onLog(it) }
            current = save(afterBilling)
            billingNotes.forEach { onLog(it) }

            current = save(current.copy(stage = Stage.REVIEW))
            val (afterRepair, repairNotes) = repairs.repair(current) { onLog(it) }
            current = save(afterRepair)
            repairNotes.forEach { onLog(it) }

            if (config.renameClones) {
                val target = current.renameTo ?: renamer.suggest(current.packageName)
                if (target != current.packageName) {
                    val (afterRename, note) = renamer.rename(current, decodeDir, target, onLog)
                    current = save(afterRename)
                    onLog(note)
                }
            }

            val report = reporter.markdown(current)
            store.writeReport(current, report)
            current = save(current.copy(report = report, stage = Stage.REVIEW))
            onLog("Preparation finished")
        } catch (t: Throwable) {
            onLog("Stopped: ${Trouble.plain(t)}")
            current = save(
                line(current.copy(stage = Stage.STOPPED), "Stopped: ${Trouble.plain(t)}")
            )
        }
        return current
    }

    suspend fun build(project: Project, onLog: (String) -> Unit): Pair<Project, BuildResult> {
        var current = project
        val config = runCatching { settings.read() }.getOrNull()
        current = save(current.copy(stage = Stage.REBUILD))
        val result = try {
            builder.build(current, store.decodeDir(current.id), store.outDir(current.id)) { line ->
                onLog(line)
            }
        } catch (t: Throwable) {
            BuildResult.Failure(Trouble.plain(t))
        }
        current = when (result) {
            is BuildResult.Success -> save(
                current.copy(
                    stage = Stage.DONE,
                    outputApk = result.apk.absolutePath,
                    workingSha256 = runCatching { com.arin.bypass.util.Hashing.sha256File(result.apk) }
                        .getOrDefault(current.workingSha256),
                    dryRun = config?.dryRun ?: current.dryRun
                )
            )
            is BuildResult.Failure -> save(current.copy(stage = Stage.STOPPED))
        }
        return current to result
    }

    suspend fun rescan(project: Project, onLog: (String) -> Unit): Project {
        val dir = store.decodeDir(project.id)
        if (!File(dir, "AndroidManifest.xml").exists()) {
            onLog("The project is not unpacked yet")
            return project
        }
        val found = scanner.scan(dir, store.javaDir(project.id)) { onLog(it) }
        return save(project.copy(findings = found, stage = Stage.SCAN))
    }

    suspend fun apply(project: Project, findingId: String, onLog: (String) -> Unit): Project {
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return project
        val result = actions.patch(project, finding)
        onLog(result.message)
        return save(result.project.copy(stage = Stage.REVIEW))
    }

    suspend fun preview(project: Project, findingId: String): String {
        val finding = project.findings.firstOrNull { it.id == findingId } ?: return ""
        return actions.preview(project, finding)
    }
}
