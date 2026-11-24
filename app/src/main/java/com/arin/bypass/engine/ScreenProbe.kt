package com.arin.bypass.engine

import android.content.Context

/** Reads whether a package is still alive and keeps the last reason when it is not. */
object ScreenProbe {

    @Volatile
    private var lastError: String? = null

    fun alive(context: Context, packageName: String): Boolean = runCatching {
        ProcessHelper.isRunning(context, packageName)
    }.getOrElse { error ->
        lastError = error.message ?: "The process list could not be read."
        false
    }

    fun note(message: String) {
        lastError = message
    }

    fun lastError(): String = lastError ?: ""

    fun describe(context: Context, packageName: String): String =
        if (alive(context, packageName)) "The app is still running." else "The app is no longer running."
}
