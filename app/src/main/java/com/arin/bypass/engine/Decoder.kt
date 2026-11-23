package com.arin.bypass.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Runs apktool (multi dex aware) and JADX. Never on the main thread. */
class Decoder {

    suspend fun decode(apk: File, decodeDir: File, javaDir: File, onLine: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                decodeDir.deleteRecursively()
                decodeDir.mkdirs()
                javaDir.deleteRecursively()
                javaDir.mkdirs()

                val apktool = Shell.which("apktool")
                    ?: return@withContext Result.failure(Exception("The unpacking tool is missing. Open Tools and install everything first."))
                onLine("Unpacking resources and smali")
                val unpack = Shell.run(
                    listOf(apktool, "d", "-f", "-o", decodeDir.absolutePath, apk.absolutePath),
                    decodeDir.parentFile ?: decodeDir,
                    600
                ) { onLine(it) }
                if (!unpack.ok) {
                    return@withContext Result.failure(Exception("Unpacking failed: ${unpack.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "unknown reason"}"))
                }
                if (!File(decodeDir, "AndroidManifest.xml").exists()) {
                    return@withContext Result.failure(Exception("Unpacking finished but no manifest came out."))
                }

                val jadx = Shell.which("jadx")
                if (jadx != null) {
                    onLine("Writing the Java view")
                    val java = Shell.run(
                        listOf(jadx, "-d", javaDir.absolutePath, "--no-res", apk.absolutePath),
                        javaDir.parentFile ?: javaDir,
                        900
                    ) { onLine(it) }
                    if (!java.ok) onLine("The Java view is incomplete, smali is still complete")
                } else {
                    onLine("The Java viewer is missing, smali only")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Unpacking failed."))
            }
        }
}
