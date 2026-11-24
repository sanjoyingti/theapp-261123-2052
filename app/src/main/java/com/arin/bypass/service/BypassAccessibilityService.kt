package com.arin.bypass.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.arin.bypass.engine.ScreenProbe
import com.arin.bypass.util.CrashLog

/**
 * Read only service. It records the package that comes to the front so the repair
 * loop can tell whether a rebuilt app actually opened.
 */
class BypassAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            ScreenProbe.note("Watcher connected")
        } catch (t: Throwable) {
            CrashLog.write("watcher connect", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            val event = event ?: return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val packageName = event.packageName?.toString() ?: return
            if (packageName == this.packageName) return
            ScreenProbe.note("Screen: $packageName")
        } catch (t: Throwable) {
            CrashLog.write("watcher event", t)
        }
    }

    override fun onInterrupt() {
        ScreenProbe.note("Watcher interrupted")
    }
}
