package com.arin.bypass.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.arin.bypass.service.BypassAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Launches the rebuilt app and reports how it behaved. */
class RepairLoop(private val context: Context, private val installer: Installer) {

    enum class Verdict { ALIVE, DIED, PAYWALL, SPINNER, NO_LAUNCHER }

    suspend fun launchAndWatch(appPackage: String, log: (String) -> Unit): Verdict = withContext(Dispatchers.IO) {
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(appPackage) }.getOrNull()
        if (launch == null) {
            log("No launch screen found for $appPackage")
            return@withContext Verdict.NO_LAUNCHER
        }
        BypassAccessibilityService.forget()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { context.startActivity(launch) }.isSuccess
        if (!started) {
            log("The clone could not be started")
            return@withContext Verdict.DIED
        }
        log("Launched $appPackage, watching for 20 seconds")

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var seen = false
        var lastSeen = System.currentTimeMillis()

        repeat(20) { tick ->
            delay(1000)
            val running = isRunning(manager, appPackage)
            if (running) {
                seen = true
                lastSeen = System.currentTimeMillis()
            }
            if (BypassAccessibilityService.looksLikePaywall() && tick >= 3) {
                log("Screen still shows a paywall")
                return@withContext Verdict.PAYWALL
            }
            if (seen && !running && System.currentTimeMillis() - lastSeen > 2500) {
                log("The clone died after launch")
                return@withContext Verdict.DIED
            }
        }
        if (!seen) {
            log("The clone never became visible")
            return@withContext Verdict.DIED
        }
        if (BypassAccessibilityService.looksStuck()) {
            log("The clone looks stuck on a spinner")
            return@withContext Verdict.SPINNER
        }
        Verdict.ALIVE
    }

    private fun isRunning(manager: ActivityManager?, appPackage: String): Boolean {
        if (manager == null) return true
        val processes = runCatching { manager.runningAppProcesses }.getOrNull() ?: return true
        return processes.any { it.processName == appPackage || it.processName.startsWith("$appPackage:") }
    }
}
