package com.arin.bypass.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Only reads the screen text of the app in front so the loop can flag a paywall bounce. */
class BypassAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event ?: return
        if (source.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = source.packageName?.toString() ?: return
        if (pkg == packageName) return
        lastForeground = pkg
        lastScreen = runCatching { read(rootInActiveWindow) }.getOrDefault("")
    }

    override fun onInterrupt() = Unit

    private fun read(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = StringBuilder()
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12) return
            current.text?.let { text.append(it).append(' ') }
            current.contentDescription?.let { text.append(it).append(' ') }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(node, 0)
        return text.toString().take(4000)
    }

    companion object {
        @Volatile
        var lastForeground: String? = null

        @Volatile
        var lastScreen: String = ""
    }
}
