package com.arin.bypass.engine

import com.arin.bypass.data.AppContainer
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Runs the stages in the only order that works: tools, decode, scan, integrity, billing, rebuild. */
class Pipeline(private val container: AppContainer) {

    suspend fun prepare(project: Project, log: (String) -> Unit): Result<Project> = withContext(Dispatchers.IO) {
        var current = project.copy(stage = Stage.TOOLCHAIN, stopped = false, updatedAt = System.currentTimeMillis())
        container.projects.save(current)

        log("Checking the helper programs")
        val tools = container.toolchain.install { message, percent -> log("$message ($percent%)") }
        if (tools.isFailure) {
            return@withContext Result.failure(tools.exceptionOrNull() ?: Exception("The tools could not be installed."))
        }
        container.settings.setToolchainReady(true)

        ensureActive()
        current = current.copy(stage = Stage.DECODE, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        val apk = container.projects.workingFile(current)
        val decoded = container.decoder.decode(
            apk,
            container.projects.decodeDir(current.id),
            container.projects.javaDir(current.id)
        ) { log(it) }
        if (decoded.isFailure) {
            return@withContext Result.failure(decoded.exceptionOrNull() ?: Exception("Unpacking failed."))
        }

        ensureActive()
        current = current.copy(stage = Stage.SCAN, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        val findings = container.scanner.scan(
            container.projects.decodeDir(current.id),
            container.projects.javaDir(current.id)
        ) { log(it) }

        current = current.copy(findings = findings, stage = Stage.REVIEW, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        Result.success(current)
    }

    suspend fun bypass(project: Project, log: (String) -> Unit): Result<Project> = withContext(Dispatchers.IO) {
        var current = project.copy(stage = Stage.INTEGRITY, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        val decode = container.projects.decodeDir(current.id)

        log("Integrity sweep first, so a re-signed build is not rejected")
        val swept = container.integrity.sweep(current, decode) { log(it) }
        current = swept.first
        swept.second.forEach { log(it) }
        container.projects.save(current)

        ensureActive()
        current = current.copy(stage = Stage.BILLING, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        val billed = container.billing.apply(current) { log(it) }
        current = billed.first
        billed.second.forEach { log(it) }

        current = current.copy(stage = Stage.REVIEW, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        Result.success(current)
    }

    suspend fun rebuild(project: Project, log: (String) -> Unit): Result<Pair<Project, File>> = withContext(Dispatchers.IO) {
        var current = project

        log("Repairing the manifest so the clone can install")
        val repaired = container.repairs.repair(current) { log(it) }
        current = repaired.first
        repaired.second.forEach { log(it) }
        container.projects.save(current)

        ensureActive()
        val settings = container.settings.read()
        if (settings.renameClones) {
            val target = current.renameTo ?: container.renamer.suggest(current.packageName)
            val renamed = container.renamer.rename(current, container.projects.decodeDir(current.id), target) { log(it) }
            current = renamed.first
            log(renamed.second)
            container.projects.save(current)
        }

        ensureActive()
        current = current.copy(stage = Stage.REBUILD, updatedAt = System.currentTimeMillis())
        container.projects.save(current)
        val result = container.builder.build(
            current,
            container.projects.decodeDir(current.id),
            container.projects.outDir(current.id)
        ) { log(it) }

        when (result) {
            is BuildResult.Failure -> {
                current = current.copy(stage = Stage.REVIEW, updatedAt = System.currentTimeMillis())
                container.projects.save(current)
                Result.failure(Exception(result.message))
            }
            is BuildResult.Success -> {
                current = current.copy(
                    stage = Stage.VERIFY,
                    outputApk = result.apk.absolutePath,
                    updatedAt = System.currentTimeMillis()
                )
                container.projects.save(current)
                Result.success(current to result.apk)
            }
        }
    }
}
