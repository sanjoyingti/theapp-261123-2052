package com.arin.bypass.engine

import com.arin.bypass.data.AppContainer
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Runs the whole chain on a background thread and keeps the project record up to date. */
class Pipeline(private val container: AppContainer) {

    private fun now(): Long = System.currentTimeMillis()

    private fun line(level: String, message: String): LogLine = LogLine(now(), level, message)

    private suspend fun note(project: Project, level: String, message: String): Project {
        val updated = project.copy(
            logs = (project.logs + line(level, message)).takeLast(800),
            updatedAt = now()
        )
        container.projects.save(updated)
        return updated
    }

    suspend fun prepare(
        initial: Project,
        log: (String) -> Unit,
        progress: (String, Int) -> Unit
    ): Result<Project> = withContext(Dispatchers.IO) {
        var project = initial
        try {
            coroutineContext.ensureActive()
            progress("Checking the helper tools", 2)
            project = note(project, "info", "Preparing ${project.packageName}")
            val settings = container.settings.read()

            if (!container.toolchain.ready()) {
                log("Installing the helper tools once")
                val install = container.toolchain.install { message, percent ->
                    progress(message, percent)
                    log(message)
                }
                val error = install.exceptionOrNull()
                if (error != null) {
                    project = note(project, "error", Trouble.plain(error))
                    return@withContext Result.failure(Exception(Trouble.plain(error.message)))
                }
                container.settings.setToolchainReady(true)
            }
            coroutineContext.ensureActive()

            progress("Making the project signing key", 8)
            log("Making a signing key for this project")
            val keyError = container.toolchain.ensureKeystore(project.id).exceptionOrNull()
            if (keyError != null) {
                project = note(project, "error", Trouble.plain(keyError))
                return@withContext Result.failure(Exception(Trouble.plain(keyError.message)))
            }

            project = project.copy(stage = Stage.DECODE)
            project = note(project, "info", "Unpacking resources and code")

            progress("Unpacking the app", 15)
            val decodeDir = container.projects.decodeDir(project.id)
            val javaDir = container.projects.javaDir(project.id)
            val decoded = container.decoder.decode(
                container.projects.workingFile(project), decodeDir, javaDir
            ) { text -> log(text) }
            val decodeError = decoded.exceptionOrNull()
            if (decodeError != null) {
                project = note(project, "error", Trouble.plain(decodeError))
                return@withContext Result.failure(Exception(Trouble.plain(decodeError.message)))
            }

            coroutineContext.ensureActive()
            progress("Scanning for server checks", 40)
            log("Scanning for network, pinning, auth, billing and force-update code")
            val findings = container.scanner.scan(decodeDir, javaDir) { log(it) }
            project = note(project.copy(findings = findings, stage = Stage.SCAN), "info", "Found ${findings.size} hits")

            coroutineContext.ensureActive()
            progress("Trusting user certificates and dropping pinning", 55)
            project = project.copy(stage = Stage.INTEGRITY)
            val (swept, integrityNotes) = container.integrity.sweep(project, decodeDir) { log(it) }
            project = swept
            for (noteText in integrityNotes) project = note(project, "warn", noteText)

            coroutineContext.ensureActive()
            progress("Granting the entitlement", 70)
            project = project.copy(stage = Stage.BILLING)
            val (billed, billingNotes) = container.billing.apply(project) { log(it) }
            project = billed
            for (noteText in billingNotes) project = note(project, "warn", noteText)

            coroutineContext.ensureActive()
            progress("Repairing the manifest", 82)
            val (repaired, repairNotes) = container.repairs.repair(project) { log(it) }
            project = repaired
            for (noteText in repairNotes) project = note(project, "info", noteText)

            if (settings.renameClones) {
                coroutineContext.ensureActive()
                progress("Renaming so the clone installs beside the original", 90)
                val newPackage = container.renamer.suggest(project.packageName)
                val (renamed, renameMessage) = container.renamer.rename(project, decodeDir, newPackage) { log(it) }
                project = renamed
                project = note(project, "info", renameMessage)
            }

            for (patch in project.patches.filter { !it.reverted }) {
                log("Patch: ${patch.title}")
                project = note(project, "patch", "${patch.title} -> ${patch.file}")
            }

            project = note(
                project.copy(stage = Stage.REVIEW, stopped = false),
                "info",
                "Ready to rebuild with ${project.appliedPatches} patches and ${project.openFindings} open hits"
            )
            progress("Done", 100)
            Result.success(project)
        } catch (e: Exception) {
            val plain = Trouble.plain(e)
            val latest = container.projects.load(project.id) ?: project
            container.projects.save(
                latest.copy(
                    stage = Stage.STOPPED,
                    stopped = true,
                    logs = (latest.logs + line("error", plain)).takeLast(800)
                )
            )
            Result.failure(Exception(plain))
        }
    }

    suspend fun rebuild(
        initial: Project,
        log: (String) -> Unit,
        progress: (String, Int) -> Unit
    ): Result<Project> = withContext(Dispatchers.IO) {
        var project = initial.copy(stage = Stage.REBUILD)
        try {
            project = note(project, "info", "Rebuilding the app")
            progress("Rebuilding the app", 10)
            log("Rebuilding the app")
            val outDir = container.projects.outDir(project.id)
            val result: BuildResult = container.builder.build(
                project, container.projects.decodeDir(project.id), outDir
            ) { text -> log(text) }
            when (result) {
                is BuildResult.Failure -> {
                    project = note(project, "error", result.message)
                    Result.failure(Exception(result.message))
                }
                is BuildResult.Success -> {
                    progress("Signed", 90)
                    project = note(
                        project.copy(
                            stage = Stage.VERIFY,
                            outputApk = result.apk.absolutePath,
                            stillBroken = emptyList()
                        ),
                        "info",
                        "Signed output ${result.apk.name}"
                    )
                    Result.success(project)
                }
            }
        } catch (e: Exception) {
            val plain = Trouble.plain(e)
            project = note(project, "error", plain)
            Result.failure(Exception(plain))
        }
    }

    suspend fun verify(
        initial: Project,
        log: (String) -> Unit,
        progress: (String, Int) -> Unit
    ): Result<Project> = withContext(Dispatchers.IO) {
        var project = initial
        val limit = container.settings.read().roundLimit
        try {
            while (project.rounds < limit) {
                coroutineContext.ensureActive()
                progress("Round ${project.rounds + 1} of $limit: watching the clone", 20 + project.rounds * 10)
                val outcome = container.loop.run(project.packageName) { log(it) }
                project = project.copy(rounds = project.rounds + 1)
                if (outcome.healthy) {
                    project = note(project, "info", "The clone stayed open after ${project.rounds} round(s).")
                    return@withContext Result.success(
                        project.copy(stage = Stage.DONE, crashLooping = false, stillBroken = emptyList())
                    )
                }
                project = note(project, "warn", outcome.reason)
                project = project.copy(stillBroken = (project.stillBroken + outcome.reason).distinct())
                log(container.loop.hint(outcome.reason))
                val rebuilt = rebuild(project, log, progress)
                val error = rebuilt.exceptionOrNull()
                if (error != null) return@withContext Result.failure(error)
                project = rebuilt.getOrThrow()
            }
            project = note(project, "warn", "Stopped after $limit rounds. The diff is kept so you can look by hand.")
            Result.success(project.copy(crashLooping = true, stage = Stage.STOPPED))
        } catch (e: Exception) {
            val plain = Trouble.plain(e)
            project = note(project, "error", plain)
            Result.failure(Exception(plain))
        }
    }

    suspend fun export(project: Project): Result<File> = withContext(Dispatchers.IO) {
        if (project.crashLooping) {
            return@withContext Result.failure(
                Exception("The clone still crash-loops, so it is not exported yet. Run the watch loop again first.")
            )
        }
        val apk = project.outputApk?.let { File(it) }
        if (apk == null || !apk.exists()) {
            return@withContext Result.failure(Exception("There is no rebuilt app yet. Rebuild first."))
        }
        val target = container.projects.exportCopy(project, apk)
        val updated = project.copy(exported = true, outputApk = target.absolutePath, updatedAt = now())
        container.projects.save(updated)
        container.projects.writeReport(updated, container.reporter.markdown(updated))
        Result.success(target)
    }
}
