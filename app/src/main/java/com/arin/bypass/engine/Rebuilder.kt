package com.arin.bypass.engine

import android.content.Context
import com.arin.bypass.data.ProjectStore
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class BuildResult {
    data class Success(val apk: File) : BuildResult()
    data class Failure(val message: String) : BuildResult()
}

/** Build, align, sign, verify. Every step runs in the background and is logged. */
class Rebuilder(private val context: Context, private val toolchain: Toolchain) {

    suspend fun build(project: Project, decodeDir: File, outDir: File, onLine: (String) -> Unit): BuildResult =
        withContext(Dispatchers.IO) {
            try {
                val apktool = Shell.which("apktool")
                    ?: return@withContext BuildResult.Failure("The unpacking tool is missing, so it cannot be rebuilt. Install the tools first.")

                outDir.mkdirs()
                val unsigned = File(outDir, "unsigned.apk")
                onLine("Rebuilding the app")
                val assemble = Shell.run(
                    listOf(apktool, "b", decodeDir.absolutePath, "-o", unsigned.absolutePath),
                    outDir,
                    900
                ) { onLine(it) }
                if (!assemble.ok || !unsigned.exists() || unsigned.length() < 2048L) {
                    val reason = assemble.output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" ")
                    return@withContext BuildResult.Failure("Rebuilding failed. $reason")
                }

                val keystore = toolchain.ensureKeystore(project.id).getOrElse { error ->
                    return@withContext BuildResult.Failure(error.message ?: "The signing key could not be made.")
                }
                val password = toolchain.keystorePassword(project.id)

                val aligned = File(outDir, "aligned.apk")
                val zipalign = Shell.which("zipalign")
                if (zipalign != null) {
                    onLine("Aligning")
                    val align = Shell.run(listOf(zipalign, "-f", "-p", "4", unsigned.absolutePath, aligned.absolutePath), outDir, 300) { onLine(it) }
                    if (!align.ok) return@withContext BuildResult.Failure("Aligning failed.")
                    unsigned.delete()
                } else {
                    unsigned.copyTo(aligned, overwrite = true)
                }

                val signed = File(outDir, "signed.apk")
                val signer = Shell.which("apksigner")
                if (signer != null) {
                    onLine("Signing")
                    val sign = Shell.run(
                        listOf(
                            signer, "sign", "--ks", keystore.absolutePath,
                            "--ks-pass", "pass:$password", "--key-pass", "pass:$password",
                            "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                            "--out", signed.absolutePath, aligned.absolutePath
                        ),
                        outDir, 300
                    ) { onLine(it) }
                    if (!sign.ok) return@withContext BuildResult.Failure("Signing failed. The key or password was rejected.")
                } else {
                    val uber = File(toolchain.bin(), "uber-apk-signer-1.3.0.jar")
                    val java = Shell.which("java")
                    if (java != null && uber.exists()) {
                        onLine("Signing with the bundled signer")
                        val sign = Shell.run(
                            listOf(
                                java, "-jar", uber.absolutePath, "--apks", aligned.absolutePath,
                                "--ks", keystore.absolutePath, "--ksPass", password, "--ksKeyPass", password,
                                "--allowResign", "--overwrite"
                            ),
                            outDir, 300
                        ) { onLine(it) }
                        if (!sign.ok) return@withContext BuildResult.Failure("Signing failed. The key or password was rejected.")
                        aligned.copyTo(signed, overwrite = true)
                    } else {
                        return@withContext BuildResult.Failure("The signing tool is missing. Install the tools first.")
                    }
                }
                aligned.delete()

                onLine("Verifying the signature")
                val verify = if (signer != null) {
                    Shell.run(listOf(signer, "verify", "--print-certs", signed.absolutePath), outDir, 120) { onLine(it) }
                } else {
                    ShellResult(0, "Signature check skipped", false)
                }
                if (!verify.ok) return@withContext BuildResult.Failure("The rebuilt app is not correctly signed.")

                BuildResult.Success(signed)
            } catch (e: Exception) {
                BuildResult.Failure(e.message ?: "Rebuilding failed.")
            }
        }
}

/** Convenience for the store so the builder does not need to know the folder layout. */
fun Project.outDir(store: ProjectStore): File = store.outDir(id)
