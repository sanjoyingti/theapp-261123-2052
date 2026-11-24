package com.arin.bypass.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.arin.bypass.util.CrashLog

/** Guarded foreground start: on Android 13+ nothing starts without POST_NOTIFICATIONS. */
object SyncStarter {

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start(context: Context): Boolean {
        if (!allowed(context)) {
            CrashLog.write("sync start", "POST_NOTIFICATIONS not granted, worker skipped")
            return false
        }
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundSyncService::class.java)
            )
            true
        }.getOrElse {
            CrashLog.write("sync start", it)
            false
        }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, ForegroundSyncService::class.java)) }
    }
}
