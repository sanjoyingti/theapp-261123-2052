package com.arin.bypass.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.arin.bypass.model.InstalledApp

/** Reads the list of installed apps so one can be picked instead of a file. */
object InstalledReader {

    @Suppress("DEPRECATION")
    fun list(context: Context): List<InstalledApp> = runCatching {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA).map { app ->
            val system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val info = runCatching { pm.getPackageInfo(app.packageName, 0) }.getOrNull()
            InstalledApp(
                label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(app.packageName),
                packageName = app.packageName,
                versionName = info?.versionName ?: "",
                isSystem = system
            )
        }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }.getOrDefault(emptyList())
}
