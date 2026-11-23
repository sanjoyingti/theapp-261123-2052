package com.arin.bypass.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Minimal repair loop: launch, poll, classify, then hand the reason back to the pipeline. */
class RepairLoop(private val context: Context, private val installer: Installer) {

    data class Outcome(val healthy: Boolean, val reason: String)

    suspend fun run(packageName: String, onLog: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        onLog("Launching $packageName")
        val launched = installer.launch(packageName)
        if (!launched) {
            onLog("The clone has no launcher entry, so it could not be started.")
            return@withContext Outcome(false, "The app has no launcher screen, so it could not be started.")
        }

        delay(2500)
        val watch = installer.watchUntilDeath(packageName, 20_000)
        when (watch) {
            Installer.WatchResult.NEVER_STARTED -> {
                onLog("The process never appeared: it dies immediately.")
                Outcome(false, "The app closes instantly when opened.")
            }
            Installer.WatchResult.DIED -> {
                onLog("The process died inside 20 seconds.")
                Outcome(false, "The app crashed or closed itself shortly after opening.")
            }
            Installer.WatchResult.ALIVE -> {
                onLog("Still alive after 20 seconds, checking what is on screen")
                val foreground = ProcessHelper.foregroundPackage(context)
                if (foreground.isNotEmpty() && foreground != packageName) {
                    onLog("The screen went back to $foreground, which looks like a paywall bounce.")
                    return@withContext Outcome(
                        false,
                        "The app jumped back to another screen, which looks like a paywall bounce."
                    )
                }
                onLog("Clone looks healthy")
                Outcome(true, "The clone stayed open.")
            }
        }
    }

    /** Plain-language hint for a failed round, used in the report and the log. */
    fun hint(reason: String): String = when {
        "crash" in reason.lowercase() || "closes" in reason.lowercase() ->
            "A crash usually means a signature check or a missing manifest export. The integrity sweep is re-run first."
        "paywall" in reason.lowercase() ->
            "A bounce means the entitlement producer was not reached. The billing patch is widened to the seeds."
        "launcher" in reason.lowercase() ->
            "No launcher means the manifest lost its MAIN/LAUNCHER filter during the edit."
        else -> "Rebuilding with the same patches plus one more round of repairs."
    }
}
