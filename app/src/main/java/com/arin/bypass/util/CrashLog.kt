package com.arin.bypass.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a crash to disk *before* the crash screen is shown, so the cause is still
 * there next time the app opens. Every call is guarded: logging may never throw.
 */
object CrashLog {

    private const val FOLDER = "crashes"
    private const val KEEP = 12

    @Volatile
    private var folder: File? = null

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (folder == null) folder = File(context.filesDir, FOLDER).apply { mkdirs() }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write("uncaught on ${thread.name}", error)
            try {
                previous?.uncaughtException(thread, error)
            } catch (t: Throwable) {
                CrashLog.write("previous handler", t)
            }
        }
    }

    /** Writes the full stack trace first, then trims old files. Never throws. */
    fun write(stage: String, error: Throwable) {
        val text = buildString {
            append(stamp())
            append("stage: ").append(stage).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.SDK_INT).append('\n')
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            append(writer.toString())
            append("\n\n")
        }
        persist(text)
    }

    fun write(stage: String, message: String) {
        persist(
            buildString {
                append(stamp())
                append("stage: ").append(stage).append('\n')
                append(message).append("\n\n")
            }
        )
    }

    private fun persist(text: String) {
        runCatching {
            val dir = folder ?: return@runCatching
            val target = File(dir, "crash.log")
            target.appendText(text)
            trim(dir)
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    fun directory(context: Context): File = folder ?: File(context.filesDir, FOLDER).apply { mkdirs() }

    fun latest(context: Context): String = runCatching {
        File(directory(context), "crash.log").takeIf { it.isFile }?.readText().orEmpty()
    }.getOrDefault("")

    fun files(context: Context): List<File> = runCatching {
        directory(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { files(context).forEach { it.delete() } }
    }

    private fun stamp(): String =
        "----- " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n"
}
