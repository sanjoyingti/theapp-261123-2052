package com.arin.bypass.engine

import android.content.Context
import com.arin.bypass.util.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One-time, idempotent setup of the helper programs and of a per-project signing key.
 * Everything runs on Dispatchers.IO.
 */
class Toolchain(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun root(): File = File(context.filesDir, "tools")

    fun bin(): File = File(root(), "bin").apply { mkdirs() }

    fun keystoreDir(): File = File(context.filesDir, "keystores").apply { mkdirs() }

    suspend fun install(onProgress: (String, Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val binDir = bin()
        Shell.binDir = binDir
        try {
            onProgress("Checking what is already installed", 5)
            val java = Shell.which("java")
            if (java == null) {
                onProgress("Java is missing: jars cannot be run yet", 20)
            }

            val apktool = File(binDir, "apktool_2.11.1.jar")
            if (java != null && !apktool.exists()) {
                onProgress("Downloading apktool", 30)
                download("https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar", apktool)
                writeWrapper("apktool", "exec \"$java\" -jar \"${apktool.absolutePath}\" \"$@\"")
            }

            val jadxDir = File(root(), "jadx")
            if (java != null && !File(jadxDir, "bin/jadx").exists()) {
                onProgress("Downloading JADX", 42)
                val zip = File(root(), "jadx.zip")
                download("https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip", zip)
                val target = File(root(), "jadx_extract").apply { mkdirs() }
                ZipKit.unpack(zip, target)
                val inner = target.listFiles()?.firstOrNull { it.name == "bin" }?.parentFile
                if (inner != null) inner.copyRecursively(jadxDir, overwrite = true)
                zip.delete()
                writeWrapper("jadx", "exec \"$java\" -cp \"${jadxDir.absolutePath}/lib/*\" jadx.cli.JadxCLI \"$@\"")
            }

            val apkEditor = File(binDir, "APKEditor-1.4.3.jar")
            if (java != null && !apkEditor.exists()) {
                onProgress("Downloading APKEditor", 58)
                download("https://github.com/REAndroid/APKEditor/releases/download/V1.4.3/APKEditor-1.4.3.jar", apkEditor)
                writeWrapper("APKEditor", "exec \"$java\" -jar \"${apkEditor.absolutePath}\" \"$@\"")
            }

            val signer = File(binDir, "uber-apk-signer-1.3.0.jar")
            if (java != null && !signer.exists()) {
                onProgress("Downloading the signer", 72)
                download("https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar", signer)
                writeWrapper("uber-apk-signer", "exec \"$java\" -jar \"${signer.absolutePath}\" \"$@\"")
            }

            onProgress("Writing the pinning helper", 84)
            writeWrapper("apk-mitm", "exec \"$java\" -jar \"${apkEditor.absolutePath}\" \"$@\"")

            onProgress("Preparing tools done", 100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(plain(e.message)))
        }
    }

    suspend fun ensureKeystore(projectId: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val store = File(keystoreDir(), "$projectId.jks")
            if (store.exists() && store.length() > 0) return@withContext Result.success(store)
            val keytool = Shell.which("keytool")
            val password = "bypass" + projectId.take(6)
            if (keytool != null) {
                val result = Shell.run(
                    listOf(
                        keytool, "-genkeypair", "-v", "-keystore", store.absolutePath,
                        "-alias", "bypass", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10000", "-storepass", password, "-keypass", password,
                        "-dname", "CN=APK Bypass, OU=Clone, O=Arin, L=Local, S=Local, C=IN"
                    ),
                    keystoreDir(),
                    120
                )
                if (!result.ok) return@withContext Result.failure(Exception(plain(result.output)))
                File(keystoreDir(), "$projectId.pass").writeText(password)
                Result.success(store)
            } else {
                Result.failure(Exception("The key generator (keytool) is not installed, so the clone cannot be signed yet."))
            }
        } catch (e: Exception) {
            Result.failure(Exception(plain(e.message)))
        }
    }

    fun keystorePassword(projectId: String): String =
        File(keystoreDir(), "$projectId.pass").takeIf { it.exists() }?.readText()?.trim() ?: ""

    fun ready(): Boolean {
        val binDir = bin()
        Shell.binDir = binDir
        return Shell.which("zipalign") != null || Shell.which("apksigner") != null ||
            File(binDir, "uber-apk-signer-1.3.0.jar").exists()
    }

    private fun writeWrapper(name: String, body: String) {
        val file = File(bin(), name)
        file.writeText("#!/system/bin/sh\n# generated\n$body\n")
        file.setExecutable(true, false)
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", "APK-Bypass").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed with code ${response.code}.")
            val body = response.body ?: throw Exception("Download returned nothing.")
            target.parentFile?.mkdirs()
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        if (target.length() < 1024) throw Exception("Downloaded file is empty.")
    }

    fun checksum(file: File): String = Hashing.sha256File(file)

    private fun plain(message: String?): String = message ?: "Unknown failure while setting up the tools."
}
