package com.arin.bypass.engine

import com.arin.bypass.data.AppContainer
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One project, one run, in the order the spec demands: the integrity sweep always
 * comes before any paywall patch, and a dry run stops before installing.
 */
class Pipeline(private val c: AppContainer) {

    suspend fun run(
        start: Project,
        publish: (Project) -> Unit,
        onProgress: (String, Int) -> Unit,
        onLog: (String) -> Unit
    ): Result<Project> = withContext(Dispatchers.IO) {
        try {
            val settings = c.settings.read()
            var project = start

            onProgress("Checking the tools", 3)
            val tools = c.toolchain.install { text, percent -> onProgress(text, 3 + percent / 8) }
            tools.onFailure { return@withContext Result.failure(Exception(it.message ?: "The tools could not be installed.")) }

            project = project.copy(stage = Stage.TOOLCHAIN)
            publish(project)

            onProgress("Unpacking the app", 16)
            val decode = c.projects.decodeDir(project.id)
            val java = c.projects.javaDir(project.id)
            val decoded = c.decoder.decode(c.projects.workingFile(project), decode, java, onLog)
            decoded.onFailure { return@withContext Result.failure(Exception(it.message ?: "Unpacking failed.")) }
            project = project.copy(stage = Stage.DECODE)
            publish(project)

            onProgress("Scanning every smali file", 42)
            val found = c.scanner.scan(decode, java, onLog)
            val seen = project.findings.map { it.ruleId + it.file + it.symbol }.toSet()
            project = project.copy(
                findings = project.findings + found.filter { (it.ruleId + it.file + it.symbol) !in seen },
                stage = Stage.SCAN
            )
            publish(project)

            onProgress("Integrity sweep before any paywall patch", 58)
            val (swept, integrityNotes) = c.integrity.sweep(project, decode) { onLog(it) }
            integrityNotes.forEach(onLog)
            project = swept.copy(stage = Stage.INTEGRITY)
            publish(project)

            onProgress("Bypassing the entitlement", 68)
            val (billed, billingNotes) = c.billing.apply(project) { onLog(it) }
            billingNotes.forEach(onLog)
            project = billed.copy(stage = Stage.BILLING)
            publish(project)

            onProgress("Repairing the manifest", 76)
            val (repaired, repairNotes) = c.repairs.repair(project) { onLog(it) }
            repairNotes.forEach(onLog)
            project = repaired.copy(
                stillBroken = (repaired.stillBroken + repairNotes.filter {
                    it.contains("raised") || it.contains("cleartext") || it.contains("Exported")
                }).distinct()
            )
            publish(project)

            if (settings.renameClones) {
                onProgress("Renaming so the clone installs beside the original", 82)
                val target = c.renamer.suggest(project.renameTo ?: project.packageName)
                val (renamed, note) = c.renamer.rename(project, decode, target) { onLog(it) }
                onLog(note)
                project = renamed
                publish(project)
            }

            onProgress("Rebuilding and signing", 88)
            val outDir = c.projects.outDir(project.id)
            val built = c.builder.build(project, decode, outDir, onLog)
            val apk = when (built) {
                is BuildResult.Failure -> {
                    onLog(built.message)
                    val stopped = project.copy(stage = Stage.STOPPED, stopped = true)
                    publish(stopped)
                    return@withContext Result.failure(Exception(built.message))
                }
                is BuildResult.Success -> built.apk
            }
            project = project.copy(stage = Stage.VERIFY, outputApk = apk.absolutePath)
            publish(project)

            if (project.dryRun) {
                onLog("Dry run is on, so nothing was installed. The built file waits in the project folder.")
                val report = c.reporter.markdown(project, "built but not installed (dry run)")
                c.projects.writeReport(project, report)
                val ready = project.copy(stage = Stage.REVIEW, report = report)
                publish(ready)
                return@withContext Result.success(ready)
            }

            onProgress("Launching the clone", 93)
            val (looped, problem) = c.loop.run(project, apk, settings.roundLimit, onLog) { current ->
                onLog("Rebuilding after a failed round")
                when (val again = c.builder.build(current, decode, outDir, onLog)) {
                    is BuildResult.Success -> again.apk
                    is BuildResult.Failure -> null
                }
            }

            val verdict = problem ?: "installed and stayed open"
            val report = c.reporter.markdown(looped, verdict)
            c.projects.writeReport(project, report)
            val finished = looped.copy(
                report = report,
                stage = if (problem == null) Stage.REVIEW else Stage.STOPPED,
                stopped = problem != null,
                stillBroken = looped.stillBroken.distinct()
            )
            publish(finished)
            Result.success(finished)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(Trouble.plain(e)))
        }
    }
}
