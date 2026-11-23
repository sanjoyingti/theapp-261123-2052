package com.arin.bypass.engine

import android.content.Context
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import com.arin.bypass.service.BypassAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Minimal repair loop: launch, watch, flag, rebuild. Never more than the round limit. */
class RepairLoop(private val context: Context, private val installer: Installer) {

    private val paywallWords = listOf(
        "subscribe", "upgrade", "premium", "free trial", "restore purchase", "continue with pro", "go pro"
    )

    suspend fun run(
        start: Project,
        apk: File,
        limit: Int,
        onLog: (String) -> Unit,
        rebuild: suspend (Project) -> File?
    ): Pair<Project, String?> = withContext(Dispatchers.IO) {
        var project = start
        var file = apk

        if (!installer.canInstall()) {
            onLog("The app is not yet allowed to install packages, so the clone was not launched.")
            return@withContext project.copy(
                stage = Stage.STOPPED,
                stillBroken = (project.stillBroken + "Install permission is missing").distinct()
            ) to "Install permission missing"
        }

        var round = 0
        while (true) {
            round++
            project = project.copy(rounds = round, stage = Stage.VERIFY)
            onLog("Round $round of $limit: opening the installer")

            val installed = installer.install(file)
            if (installed.isFailure) {
                val reason = installed.exceptionOrNull()?.message ?: "The installer could not be opened."
                onLog(reason)
                return@withContext project.copy(stage = Stage.STOPPED) to reason
            }
            delay(3000L)

            val clone = project.renameTo ?: project.packageName
            if (!installer.launch(clone)) {
                onLog("The clone could not be started. It may not be installed.")
                return@withContext project.copy(stage = Stage.STOPPED) to "Launch failed"
            }

            val problem = watch(clone)
            if (problem == null) {
                onLog("The clone stays open and does not bounce.")
                return@withContext project.copy(crashLooping = false) to null
            }

            onLog("Problem found: $problem")
            project = project.copy(
                crashLooping = problem.startsWith("Crash"),
                stillBroken = (project.stillBroken + problem).distinct()
            )
            if (round >= limit) break

            val rebuilt = rebuild(project)
            if (rebuilt == null) {
                onLog("The rebuild failed, so the loop stops here.")
                break
            }
            file = rebuilt
            delay(1500L)
        }
        project.copy(stage = Stage.STOPPED, stopped = true) to "Round limit reached"
    }

    private suspend fun watch(clone: String): String? {
        val until = System.currentTimeMillis() + 15_000L
        var seen = false
        while (System.currentTimeMillis() < until) {
            delay(1200L)
            val running = installer.isRunning(clone)
            if (running) seen = true
            if (seen && !running) return "Crash: the app closed itself right after starting."
            val foreground = BypassAccessibilityService.lastForeground
            if (foreground == clone) {
                val words = BypassAccessibilityService.lastScreen.lowercase()
                if (paywallWords.any { words.contains(it) }) {
                    return "Paywall: the app went straight to an upgrade screen."
                }
            }
            if (!seen && System.currentTimeMillis() > until - 6000L) {
                return "Stuck: the app never showed a window, it may be spinning."
            }
        }
        return null
    }
}
