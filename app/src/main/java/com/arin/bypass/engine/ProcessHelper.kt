package com.arin.bypass.engine

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Checks whether a launched app is still alive, through the activity manager and /proc. */
object ProcessHelper {

    fun isRunning(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (runningViaActivityManager(context, packageName)) return true
        return runningViaProc(packageName)
    }

    @Suppress("DEPRECATION")
    private fun runningViaActivityManager(context: Context, packageName: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.any { it.processName == packageName } == true
    }.getOrDefault(false)

    private fun runningViaProc(packageName: String): Boolean = runCatching {
        val dirs = File("/proc").listFiles() ?: return false
        dirs.any { dir ->
            if (!dir.isDirectory || !dir.name.all { it.isDigit() }) return@any false
            val cmdline = File(dir, "cmdline")
            if (!cmdline.isFile) return@any false
            val text = runCatching { cmdline.readBytes().decodeToString().trim('\u0000', ' ') }
                .getOrDefault("")
            text == packageName || text.startsWith("$packageName:")
        }
    }.getOrDefault(false)

    suspend fun running(context: Context, packageName: String): Boolean =
        withContext(Dispatchers.IO) { isRunning(context, packageName) }
}
