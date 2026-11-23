package com.arin.bypass.engine

import java.io.File
import java.util.concurrent.TimeUnit

class ShellResult(val exit: Int, val output: String, val timedOut: Boolean) {
    val ok: Boolean get() = exit == 0 && !timedOut
}

/** Runs command line programs without ever blocking the main thread. */
object Shell {

    var binDir: File? = null

    fun run(
        command: List<String>,
        workDir: File,
        timeoutSeconds: Long = 300,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {}
    ): ShellResult {
        val builder = ProcessBuilder(command)
        builder.directory(workDir)
        builder.redirectErrorStream(true)
        val environment = builder.environment()
        environment["PATH"] = buildPath()
        environment["HOME"] = (workDir.absoluteFile.parentFile ?: workDir).absolutePath
        environment.putAll(env)
        val process = try {
            builder.start()
        } catch (e: Exception) {
            return ShellResult(-1, "Could not start ${command.firstOrNull()}: ${e.message}", false)
        }
        val collected = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(collected) { collected.append(line).append('\n') }
                    runCatching { onLine(line) }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(2000)
            return ShellResult(-1, collected.toString(), true)
        }
        reader.join(2000)
        return ShellResult(process.exitValue(), collected.toString(), false)
    }

    fun runShell(script: String, workDir: File, timeoutSeconds: Long = 300, onLine: (String) -> Unit = {}): ShellResult {
        val file = File(workDir, "step_${System.currentTimeMillis()}.sh")
        file.writeText("#!/bin/sh\nset -x\n" + script + "\n")
        file.setExecutable(true)
        return run(listOf("/system/bin/sh", file.absolutePath), workDir, timeoutSeconds, onLine = onLine)
    }

    private fun buildPath(): String {
        val parts = ArrayList<String>()
        binDir?.let { parts.add(it.absolutePath) }
        parts.add("/system/bin")
        parts.add("/system/xbin")
        parts.add("/vendor/bin")
        System.getenv("PATH")?.let { parts.add(it) }
        return parts.joinToString(":")
    }

    fun which(name: String): String? {
        val dirs = ArrayList<String>()
        binDir?.let { dirs.add(it.absolutePath) }
        dirs.add("/system/bin")
        dirs.add("/system/xbin")
        System.getenv("PATH")?.split(":")?.let { dirs.addAll(it) }
        for (dir in dirs) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return null
    }
}
