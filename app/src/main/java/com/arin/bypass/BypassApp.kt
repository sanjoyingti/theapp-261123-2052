package com.arin.bypass

import android.app.Application
import com.arin.bypass.data.AppContainer
import com.arin.bypass.util.CrashLog

/**
 * Start-up stays boring on purpose. Nothing touches disk, preferences or a service
 * here; the tool objects are only built the first time the tab asks for them.
 */
class BypassApp : Application() {

    @Volatile
    private var cached: AppContainer? = null

    /** Built on first use, never in onCreate, and never allowed to abort the launch. */
    val container: AppContainer
        get() = cached ?: synchronized(this) {
            cached ?: build().also { cached = it }
        }

    override fun onCreate() {
        super.onCreate()
        step("crash recorder") { CrashLog.install(this) }
        step("data folder") { filesDir.mkdirs() }
    }

    private fun build(): AppContainer {
        return try {
            AppContainer(this)
        } catch (t: Throwable) {
            CrashLog.write("container build", t)
            // second attempt with a bare container; its members are all lazy anyway
            AppContainer(this)
        }
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            CrashLog.write("startup step: $name", t)
        }
    }
}
