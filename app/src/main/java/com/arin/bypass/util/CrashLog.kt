package com.arin.bypass.util

import android.content.Context
import android.content.Intent
import com.arin.bypass.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every failure is written to a file *before* the crash screen is opened, so the
 * cause is still on disk after the process died. Nothing in here is allowed to throw.
 */
object CrashLog {

    private const val FOLDER = "crashes"
    private const val LAST = "last_crash.txt"
    private const val KEEP = 20

    @Volatile
    private var app: Context? = null

    fun install(context: Context) {
        val application = context.applicationContext
        app = application
        runCatching { folder(application).mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Catcher) return
        runCatching { Thread.setDefaultUncaughtExceptionHandler(Catcher(application, previous)) }
    }

    fun folder(context: Context): File = File(context.filesDir, FOLDER)

    fun path(context: Context): File = File(folder(context), LAST)

    fun write(where: String, error: Throwable?) {
        stash(report(where, error, null))
    }

    fun write(where: String, message: String?) {
        stash(report(where, null, message))
    }

    fun write(where: String) {
        stash(report(where, null, null))
    }

    fun log(where: String) = write(where)

    fun log(where: String, error: Throwable?) = write(where, error)

    fun report(error: Throwable?): String = report("uncaught", error, null)

    fun hasCrash(context: Context): Boolean = lastCrash(context) != null

    /** Newest saved trace, or null when nothing was recorded. */
    fun lastCrash(context: Context): String? {
        val direct = File(folder(context), LAST)
        val text = runCatching { if (direct.exists()) direct.readText() else "" }.getOrDefault("")
        if (text.isNotBlank()) return text
        val newest = folder(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.maxByOrNull { it.lastModified() }
        val older = newest?.let { file -> runCatching { file.readText() }.getOrNull() }
        return older?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { folder(context).listFiles()?.forEach { it.delete() } }
    }

    private fun stash(text: String) {
        val context = app ?: return
        runCatching {
            val dir = folder(context).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            File(dir, "crash-$stamp.txt").writeText(text)
            File(dir, LAST).writeText(text)
            trim(dir)
        }
    }

    private fun trim(dir: File) {
        val old = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?: return
        old.forEach { runCatching { it.delete() } }
    }

    private fun report(where: String, error: Throwable?, message: String?): String {
        val builder = StringBuilder()
        builder.append("When: ")
            .append(SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.US).format(Date()))
            .append('\n')
        builder.append("Where: ").append(where).append('\n')
        builder.append("Thread: ").append(Thread.currentThread().name).append('\n')
        if (!message.isNullOrBlank()) builder.append("Message: ").append(message).append('\n')
        if (error != null) {
            val writer = StringWriter()
            runCatching { error.printStackTrace(PrintWriter(writer)) }
            builder.append('\n').append(writer.toString())
        }
        builder.append('\n')
        return builder.toString()
    }

    private class Catcher(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            // file first, screen second, hand back to the system last
            runCatching { write("uncaught on ${thread.name}", error) }
            runCatching { openScreen() }
            val fallback = previous
            if (fallback != null && fallback !== this) {
                runCatching { fallback.uncaughtException(thread, error) }
            }
        }

        private fun openScreen() {
            val intent = Intent(context, CrashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
