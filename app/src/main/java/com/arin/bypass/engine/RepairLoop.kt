package com.arin.bypass.engine

import android.content.Context
import com.arin.bypass.model.LogLine
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import kotlinx.coroutines.delay

/**
 * Builds, installs and watches. If the app dies it goes another round until the
 * limit is used up, then it stops with the reason written into the project.
 */
class RepairLoop(
    private val context: Context,
    private val installer: Installer,
    private val rounds: RoundMonitor,
    private val pipeline: Pipeline
) {

    private fun note(project: Project, message: String): Project =
        project.copy(
            logs = (project.logs + LogLine(System.currentTimeMillis(), "w", message)).takeLast(400)
        )

    suspend fun run(project: Project, limit: Int, onLog: (String) -> Unit): Project {
        var current = project
        val cap = limit.coerceIn(1, 6)
        while (rounds.used(current.id) < cap && !current.stopped) {
            val round = rounds.bump(current.id)
            onLog("Round $round of $cap")
            current = current.copy(rounds = round)
            if (current.findings.isEmpty()) current = pipeline.rescan(current, onLog)
            current = pipeline.prepare(current, onLog)
            if (current.stage == Stage.STOPPED) {
                onLog("Preparation stopped, trying the build anyway")
            }
            val built = pipeline.build(current, onLog)
            current = built.first
            val result = built.second
            if (result is BuildResult.Failure) {
                onLog(result.message)
                return note(current.copy(stage = Stage.STOPPED), result.message)
            }
            val apk = (result as BuildResult.Success).apk
            current = note(current, "Built ${apk.name}")

            if (current.dryRun) {
                onLog("Dry run is on, nothing is installed")
                return current.copy(stage = Stage.DONE)
            }

            val permission = installer.install(apk)
            if (permission.isFailure) {
                val reason = permission.exceptionOrNull()?.message ?: "The installer would not open."
                onLog(reason)
                return note(current.copy(stage = Stage.STOPPED), reason)
            }

            delay(3000)
            val alive = ScreenProbe.alive(context, current.packageName)
            if (alive) {
                onLog("The app is running after the round")
                return current.copy(stage = Stage.DONE)
            }
            onLog("The app did not stay open")
            val looping = rounds.markDeath(current.id)
            ScreenProbe.note("The rebuilt app closed right after opening.")
            current = note(current.copy(crashLooping = looping), "The app closed right after opening")
            if (loop_count(current) >= cap) break
        }
        val message = "Stopped after ${rounds.used(current.id)} rounds. ${ScreenProbe.lastError()}"
        onLog(message)
        return note(current.copy(stage = Stage.STOPPED), message)
    }

    private fun loop_count(project: Project): Int = project.rounds
}
