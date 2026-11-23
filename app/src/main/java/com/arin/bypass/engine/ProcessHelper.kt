=== ABOUT: Checks whether a launched app is still running, using the activity manager and the process list ===
package com.arin.bypass.engine

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ProcessHelper {

    fun isRunning(context: Context, packageName: String): Boolean {
        if (runningViaActivityManager(context, packageName)) return true
        return runningViaProc(packageName)
    }

    @Suppress("DEPRECATION")
    private fun runningViaActivityManager(context: Context, packageName: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.any { it.processName == packageName } == true
    }.getOrDefault(false)

    private suspend fun runningViaProc(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            File("/proc").listFiles()?.any { dir ->
                val cmdline = File(dir, "cmdline")
                dir.isDirectory && dir.name.all { it.isDigit() } &&
                    cmdline.isFile && runCatching {
                        cmdline.readBytes().decodeToString().trim('\u0000', ' ')
                    }.getOrDefault("") == packageName
            } == true
        }.getOrDefault(false)
    }

    suspend fun foregroundPackage(context: Context): String = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName ?: ""
        }.getOrDefault("")
    }
}
