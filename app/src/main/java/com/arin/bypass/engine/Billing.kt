package com.arin.bypass.engine

import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Patches the method that produces the entitlement, never the call sites, and
 * also the stored seeds that feed it, so a stale cache cannot bounce back to the paywall.
 */
class Billing(private val patcher: Patcher) {

    private val seedPairs = listOf(
        "\"is_premium\":false" to "\"is_premium\":true",
        "\"is_premium\": false" to "\"is_premium\": true",
        "\"premium\":false" to "\"premium\":true",
        "\"premium\": false" to "\"premium\": true",
        "\"isPro\":false" to "\"isPro\":true",
        "\"is_pro\":false" to "\"is_pro\":true",
        "\"subscribed\":false" to "\"subscribed\":true",
        "\"is_subscribed\":false" to "\"is_subscribed\":true",
        "\"active\":false" to "\"active\":true",
        "\"entitlement\":null" to "\"entitlement\":\"premium\""
    )

    suspend fun apply(project: Project, onLog: (String) -> Unit): Pair<Project, List<String>> = withContext(Dispatchers.IO) {
        var current = project
        val notes = ArrayList<String>()

        val libraries = detectLibraries(current)
        if (libraries.isNotEmpty()) onLog("Billing libraries found: ${libraries.joinToString(", ")}") else onLog("No billing library found, using hand written flags")

        val producers = current.findings.filter {
            !it.patched && it.symbol.isNotBlank() && it.confidence >= 55 &&
                (it.category == HitCategory.PAYWALL || it.category == HitCategory.BILLING)
        }
        onLog("Patching ${producers.size} entitlement producers")
        for (hit in producers) {
            ensureActive()
            val record = patcher.patchText(current, hit.file, "Always grant entitlement", PatchKind.DEX_EARLY_RETURN, hit.id) { text ->
                SmaliEdit.forceReturn(text, hit.symbol, SmaliEdit.Answer.TRUE)
            }
            if (record != null) {
                current = current.copy(
                    patches = current.patches + record,
                    findings = current.findings.map { if (it.id == hit.id) it.copy(patched = true) else it }
                )
            }
        }

        onLog("Neutralising the cached seeds")
        val seeds = current.findings.filter { it.ruleId == "bill.seed" && !it.patched }
        for (seed in seeds) {
            ensureActive()
            val record = patcher.patchText(current, seed.file, "Seed reads as paid", PatchKind.DEX_STRING, seed.id) { text ->
                seedPairs.firstNotNullOfOrNull { (from, to) -> SmaliEdit.replaceString(text, from, to) }
            }
            if (record != null) current = current.copy(patches = current.patches + record)
        }

        // entitlement tolerance: a free looking payload must read as paid, and the token stays
        onLog("Making a free answer read as paid")
        val parseHits = current.findings.filter { it.category == HitCategory.TOKEN && it.symbol.isNotBlank() && !it.patched }
        for (hit in parseHits.take(20)) {
            ensureActive()
            val record = patcher.patchText(current, hit.file, "Expired token cannot bounce back", PatchKind.DEX_EARLY_RETURN, hit.id) { text ->
                SmaliEdit.forceReturn(text, hit.symbol, SmaliEdit.Answer.FALSE)
            }
            if (record != null) current = current.copy(patches = current.patches + record)
        }

        if (producers.isEmpty()) notes.add("No entitlement producer was found, look at the paywall hits by hand.")
        current.copy(entitlementSymbols = producers.map { it.symbol }.distinct(), updatedAt = System.currentTimeMillis()) to notes
    }

    private fun detectLibraries(project: Project): List<String> {
        val markers = listOf(
            "Play Billing" to "com/android/billingclient",
            "RevenueCat" to "com/revenuecat",
            "Adapty" to "com/adapty",
            "Qonversion" to "com/qonversion",
            "Superwall" to "com/superwall"
        )
        val blob = project.findings.joinToString(" ") { it.snippet + " " + it.symbol }
        return markers.filter { blob.contains(it.second, ignoreCase = true) }.map { it.first }
    }
}
