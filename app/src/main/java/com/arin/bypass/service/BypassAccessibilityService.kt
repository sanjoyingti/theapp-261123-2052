package com.arin.bypass.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Watches window changes only; the repair loop uses this to spot a bounce back to a paywall. */
class BypassAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        connected.value = true
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val from = event?.packageName?.toString() ?: return
        if (from == packageName) return
        last.value = from
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected.value = false
        super.onDestroy()
    }

    companion object {
        val connected: MutableStateFlow<Boolean> = MutableStateFlow(false)
        private val last = MutableStateFlow("")
        val lastForeground: StateFlow<String> get() = last
    }
}
