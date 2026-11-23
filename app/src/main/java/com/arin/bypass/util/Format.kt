package com.arin.bypass.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun time(millis: Long): String = clock.format(Date(millis))

    fun day(millis: Long): String =
        SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(Date(millis))

    fun bytes(count: Long): String {
        if (count < 1024) return "$count B"
        val kb = count / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun sha(hex: String): String = if (hex.length <= 16) hex else hex.take(16) + "…"
}
