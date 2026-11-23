package com.arin.bypass.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Installs through the package installer and tells the repair loop whether the clone lives. */
class Installer(private val context: Context) {

    fun installIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canInstall(): Boolean = runCatching {
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    suspend fun install(apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!apk.isFile) {
                return@withContext Result.failure(Exception("The built file is missing, so there is nothing to install."))
            }
            context.startActivity(installIntent(apk))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(Trouble.plain(e)))
        }
    }

    fun launch(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }
    }.getOrDefault(false)

    fun isRunning(packageName: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.runningAppProcesses?.any { it.processName == packageName } == true
    }.getOrDefault(false)

    fun installedVersion(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()
}
