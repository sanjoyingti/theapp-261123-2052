package com.arin.bypass.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Reads the on-screen text of the launched clone. Used only by the repair loop to
 * tell a paywall redirect or a spinner apart from a healthy start.
 */
class BypassAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        val builder = StringBuilder()
        event.text?.forEach { builder.append(it).append(' ') }
        val content = event.contentDescription
        if (!content.isNullOrBlank()) builder.append(content)
        val text = builder.toString()
        if (text.isBlank()) return
        lastText = text.lowercase()
        lastAt = System.currentTimeMillis()
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: BypassAccessibilityService? = null

        @Volatile
        var lastText: String = ""

        @Volatile
        var lastAt: Long = 0

        private val paywallMarks = listOf(
            "subscribe", "upgrade to", "go premium", "start free trial", "restore purchase", "paywall", "unlock"
        )
        private val spinnerMarks = listOf("please wait", "loading", "connecting", "checking")

        val running: Boolean get() = instance != null

        fun freshText(maxAgeMillis: Long = 20_000): String {
            if (System.currentTimeMillis() - lastAt > maxAgeMillis) return ""
            return lastText
        }

        fun looksLikePaywall(): Boolean {
            val text = freshText()
            return text.isNotBlank() && paywallMarks.any { text.contains(it) }
        }

        fun looksStuck(): Boolean {
            val text = freshText()
            return text.isNotBlank() && spinnerMarks.any { text.contains(it) }
        }

        fun forget() {
            lastText = ""
            lastAt = 0
        }
    }
}
