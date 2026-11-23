package com.arin.bypass.engine

import android.content.Context
import com.arin.bypass.model.Project
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class BuildResult {
    data class Success(val apk: File) : BuildResult()
    data class Failure(val message: String) : BuildResult()
}

/** Repackage, align, sign, verify. Every step runs in the background and is logged. */
class Rebuilder(private val context: Context, private val toolchain: Toolchain) {

    suspend fun build(project: Project, decodeDir: File, outDir: File, onLine: (String) -> Unit): BuildResult =
        withContext(Dispatchers.IO) {
            try {
                if (!File(decodeDir, "AndroidManifest.xml").exists()) {
                    return@withContext BuildResult.Failure("The project is not unpacked, so there is nothing to rebuild.")
                }
                val apktool = Shell.which("apktool")
                    ?: return@withContext BuildResult.Failure("The unpacking tool is missing. Install the tools first.")

                outDir.mkdirs()
                outDir.listFiles()?.forEach { it.delete() }

                val unsigned = File(outDir, "unsigned.apk")
                onLine("Repackaging the edited folders")
                val assemble = Shell.run(
                    listOf(apktool, "b", decodeDir.absolutePath, "-o", unsigned.absolutePath),
                    outDir,
                    900
                ) { onLine(it) }
                if (!assemble.ok || !unsigned.exists() || unsigned.length() < 2048L) {
                    val reason = assemble.output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(4).joinToString(" ")
                    return@withContext BuildResult.Failure("Repackaging failed. $reason")
                }

                val keystore = toolchain.ensureKeystore(project.id).getOrElse { error ->
                    return@withContext BuildResult.Failure(error.message ?: "The signing key could not be created.")
                }
                val password = toolchain.keystorePassword(project.id)

                var working = unsigned
                val align = Shell.which("zipalign")
                if (align != null) {
                    onLine("Aligning the archive")
                    val aligned = File(outDir, "aligned.apk")
                    val alignResult = Shell.run(
                        listOf(align, "-f", "-p", "4", unsigned.absolutePath, aligned.absolutePath),
                        outDir,
                        180
                    ) { onLine(it) }
                    if (!alignResult.ok || !aligned.exists()) {
                        return@withContext BuildResult.Failure("Aligning failed, so the file would not install.")
                    }
                    working = aligned
                } else {
                    onLine("The aligning tool is missing, keeping the file unaligned")
                }

                val signed = File(outDir, "signed.apk")
                val signer = Shell.which("apksigner")
                if (signer != null) {
                    onLine("Signing with the project key")
                    val signResult = Shell.run(
                        listOf(
                            signer, "sign",
                            "--ks", keystore.absolutePath,
                            "--ks-pass", "pass:$password",
                            "--key-pass", "pass:$password",
                            "--ks-key-alias", "bypass",
                            "--v1-signing-enabled", "false",
                            "--v2-signing-enabled", "true",
                            "--v3-signing-enabled", "true",
                            "--out", signed.absolutePath,
                            working.absolutePath
                        ),
                        outDir,
                        300
                    ) { onLine(it) }
                    if (!signResult.ok || !signed.exists()) {
                        return@withContext BuildResult.Failure("Signing failed. ${lastLine(signResult.output)}")
                    }
                    onLine("Checking the signature")
                    val verify = Shell.run(listOf(signer, "verify", "--print-certs", signed.absolutePath), outDir, 120) { onLine(it) }
                    if (!verify.ok) {
                        return@withContext BuildResult.Failure("The signature could not be verified, so the phone would reject it.")
                    }
                } else {
                    val uber = Shell.which("uber-apk-signer")
                        ?: return@withContext BuildResult.Failure("The signing tool is missing. Install the tools first.")
                    onLine("Signing with the fallback signer")
                    val signResult = Shell.run(
                        listOf(
                            uber, "-a", working.absolutePath,
                            "--ks", keystore.absolutePath,
                            "--ksAlias", "bypass",
                            "--ksPass", password,
                            "--ksKeyPass", password,
                            "--allowResign",
                            "--overwrite"
                        ),
                        outDir,
                        300
                    ) { onLine(it) }
                    val produced = outDir.listFiles()?.firstOrNull { it.name.contains("signed") && it.extension == "apk" } ?: working
                    if (!signResult.ok && produced == working) {
                        return@withContext BuildResult.Failure("Signing failed. ${lastLine(signResult.output)}")
                    }
                    if (produced != working) produced.copyTo(signed, overwrite = true)
                    if (!signed.exists()) return@withContext BuildResult.Failure("The signed file was not produced.")
                }

                unsigned.delete()
                BuildResult.Success(signed)
            } catch (e: Exception) {
                BuildResult.Failure(Trouble.plain(e))
            }
        }

    private fun lastLine(output: String): String =
        output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(2).joinToString(" ")
}
