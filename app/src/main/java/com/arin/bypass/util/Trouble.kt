package com.arin.bypass.util

/** Every error shown in the app goes through here so nobody ever sees a stack trace. */
object Trouble {

    fun plain(t: Throwable): String = plain(t.message)

    fun plain(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return "Something went wrong and the tool gave no reason."
        val lower = raw.lowercase()
        return when {
            "no space" in lower || "enospc" in lower ->
                "The phone ran out of free space. Free some room and start again."
            "permission denied" in lower || "eacces" in lower ->
                "A file could not be written because its folder is read-only."
            "read-only file system" in lower ->
                "That folder is read-only, so the file could not be saved."
            "keytool" in lower && "not found" in lower ->
                "The key generator is missing. Install the tools first."
            "java" in lower && ("not found" in lower || "no such file" in lower) ->
                "Java is missing in the Linux shell, so the helper programs cannot run."
            "apktool" in lower && ("not found" in lower || "no such file" in lower) ->
                "The unpacking tool is missing. Open Tools and install everything first."
            "jadx" in lower && ("not found" in lower || "no such file" in lower) ->
                "The Java viewer is missing. Open Tools and install everything first."
            "zipalign" in lower && "not found" in lower ->
                "The aligning tool is missing. Install the tools first."
            "apksigner" in lower && "not found" in lower ->
                "The signing tool is missing. Install the tools first."
            "certificate" in lower && "path" in lower ->
                "The signing key could not be read. Rebuild the project key in Tools."
            "downloaded file is empty" in lower ->
                "A download came back empty. Check the connection and try again."
            "download failed" in lower ->
                "A download failed. Check the connection and try again."
            "timeout" in lower || "timed out" in lower ->
                "The step took too long and was stopped."
            "not a zip" in lower || "zip" in lower && "invalid" in lower ->
                "That file is not a readable archive."
            "already exists" in lower ->
                "The target already exists. Remove it or use a different name."
            "out of memory" in lower ->
                "The phone ran short of memory. Close other apps and retry."
            else -> firstLine(raw)
        }
    }

    private fun firstLine(raw: String): String {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("at ") && !it.startsWith("Caused by") }
            ?: raw
        return line.take(240)
    }
}
