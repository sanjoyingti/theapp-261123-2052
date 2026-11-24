package com.arin.bypass.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** All accessibility-service handling is guarded: a broken file can never stop the app. */
object WatcherSetup {

    fun component(context: Context): ComponentName =
        ComponentName(context, BypassAccessibilityService::class.java)

    fun isEnabled(context: Context): Boolean = runCatching {
        val expected = "${context.packageName}/${BypassAccessibilityService::class.java.name}"
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }.getOrDefault(false)

    fun open(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun describe(context: Context): String =
        if (isEnabled(context)) "The watcher is on." else "The watcher is off."
}
