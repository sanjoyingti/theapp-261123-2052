package com.arin.bypass.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Sends a built APK to the system installer. */
class Installer(private val context: Context) {

    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }

    fun openPermission(): Boolean = runCatching {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    suspend fun install(apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!apk.isFile || apk.length() < 2048L) {
                return@withContext Result.failure(Exception("The built file is missing or too small to install."))
            }
            if (!canInstall()) {
                return@withContext Result.failure(Exception("Allow installing unknown apps for this app, then tap install again."))
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(Trouble.plain(e)))
        }
    }
}
