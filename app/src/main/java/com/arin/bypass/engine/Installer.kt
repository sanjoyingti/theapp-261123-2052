=== ABOUT: Opens the rebuilt app in the phone's installer and watches whether the clone is still running ===
package com.arin.bypass.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Installs through the package installer and polls the launched clone for death. */
class Installer(private val context: Context) {

    fun installIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun install(apk: File): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            context.startActivity(installIntent(apk))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(Trouble.plain(if (Build.VERSION.SDK_INT >= 26 ||
                context.packageManager.canRequestPackageInstalls()
            ) e else Exception("Install permission is off. Allow installing unknown apps for APK Bypass in Settings.")))
        }
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** Returns true when the clone has been launched and is alive. */
    suspend fun launch(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Polls until the process is gone, so a crash or a bounce can be flagged. */
    suspend fun watchUntilDeath(packageName: String, maxMillis: Long = 20_000): WatchResult =
        withContext(Dispatchers.IO) {
            var seenAlive = false
            val started = System.currentTimeMillis()
            var foreground = false
            while (System.currentTimeMillis() - started < maxMillis) {
                val alive = isProcessAlive(packageName)
                if (alive) seenAlive = true
                if (alive && !seenAlive) foreground = true
                if (!alive && seenAlive) return@withContext WatchResult.DIED
                delay(700)
            }
            if (seenAlive) WatchResult.ALIVE else WatchResult.NEVER_STARTED
        }

    private fun isProcessAlive(packageName: String): Boolean =
        ProcessHelper.isRunning(context, packageName)

    enum class WatchResult { DIED, ALIVE, NEVER_STARTED }
}
