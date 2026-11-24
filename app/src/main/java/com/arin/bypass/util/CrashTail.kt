package com.arin.bypass.util

import android.content.Context
import java.io.File

/**
 * Best effort reader for the newest saved crash report. The crash recorder is asked
 * first by reflection (so a differently named member can never break the build), and
 * if that gives nothing the app folder is scanned for a crash file. Never throws.
 */
object CrashTail {

    private val methodNames = listOf("recent", "read", "lastCrash", "load", "tail", "report", "text")

    private val folderNames = listOf("", "logs", "crash", "crashes")

    fun read(context: Context): String? = runCatching {
        val text = viaRecorder(context) ?: fromDisk(context)
        text?.trim()?.takeIf { it.isNotEmpty() }?.take(2000)
    }.getOrNull()

    private fun viaRecorder(context: Context): String? {
        val methods = runCatching { CrashLog::class.java.methods.toList() }.getOrDefault(emptyList())
        for (name in methodNames) {
            val method = methods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Context::class.java
            } ?: continue
            val value = runCatching { method.invoke(CrashLog, context) }.getOrNull() ?: continue
            val text = when (value) {
                is String -> value
                is File -> runCatching { value.takeIf { it.isFile }?.readText() }.getOrNull()
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun fromDisk(context: Context): String? {
        val folders = folderNames
            .map { name -> if (name.isEmpty()) context.filesDir else File(context.filesDir, name) }
            .plus(context.cacheDir)
        val newest = folders.filter { it.isDirectory }
            .flatMap { folder -> runCatching { folder.listFiles()?.toList() }.getOrNull() ?: emptyList() }
            .filter { file ->
                file.isFile && file.length() in 1..2_000_000L &&
                    file.name.lowercase().let { it.contains("crash") || it.contains("last_error") }
            }
            .maxByOrNull { it.lastModified() }
        return newest?.let { tail(it) }
    }

    private fun tail(file: File): String? = runCatching {
        val text = file.readText()
        if (text.length <= 1500) text else text.takeLast(1500)
    }.getOrNull()
}
