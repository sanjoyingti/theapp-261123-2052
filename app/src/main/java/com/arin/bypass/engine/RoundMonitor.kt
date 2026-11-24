package com.arin.bypass.engine

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Tracks how many repair rounds a project has used and whether it keeps dying. */
class RoundMonitor(private val context: Context) {

    private val rounds = ConcurrentHashMap<String, Int>()
    private val deaths = ConcurrentHashMap<String, Int>()

    fun used(projectId: String): Int = rounds[projectId] ?: 0

    fun bump(projectId: String): Int {
        val next = used(projectId) + 1
        rounds[projectId] = next
        return next
    }

    fun reset(projectId: String) {
        rounds.remove(projectId)
        deaths.remove(projectId)
    }

    fun markDeath(projectId: String): Boolean {
        val count = (deaths[projectId] ?: 0) + 1
        deaths[projectId] = count
        return count >= 3
    }

    fun deaths(projectId: String): Int = deaths[projectId] ?: 0

    fun summary(projectId: String): String {
        context.cacheDir.mkdirs()
        return "Round ${used(projectId)}, ${deaths(projectId)} deaths"
    }
}
