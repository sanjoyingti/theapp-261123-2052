package com.arin.bypass.engine

import com.arin.bypass.model.Finding
import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads smali and the Java view side by side, scores each hit and pins it to a file
 * and line. Java hits raise confidence but only smali hits are patchable.
 */
class Scanner {

    private val maxFileBytes = 512L * 1024L
    private val maxFindings = 500

    suspend fun scan(decodeDir: File, javaDir: File, onLog: (String) -> Unit): List<Finding> =
        withContext(Dispatchers.IO) {
            val hits = HashMap<String, Finding>()
            val hosts = HashMap<String, Finding>()
            var files = 0

            val smaliDirs = decodeDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("smali") } ?: emptyList()
            for (dir in smaliDirs) {
                dir.walkTopDown().forEach { file ->
                    ensureActive()
                    if (!file.isFile || !file.name.endsWith(".smali") || file.length() > maxFileBytes) return@forEach
                    files++
                    val rel = file.relativeTo(decodeDir).invariantSeparatorsPath
                    val lines = runCatching { file.readLines() }.getOrNull() ?: return@forEach
                    lines.forEachIndexed { index, line ->
                        for (rule in Rules.all) {
                            if (!rule.pattern.containsMatchIn(line)) continue
                            val symbol = symbolOf(line)
                            val key = rule.id + "|" + rel + "|" + symbol
                            if (hits.containsKey(key)) continue
                            hits[key] = Finding(
                                id = Ids.newId(),
                                ruleId = rule.id,
                                title = rule.title,
                                detail = rule.detail,
                                category = rule.category,
                                confidence = rule.weight,
                                file = rel,
                                line = index + 1,
                                snippet = line.trim().take(200),
                                symbol = symbol
                            )
                        }
                        for (match in Rules.endpoints.findAll(line)) {
                            val url = match.value
                            val host = url.substringAfter("://").substringBefore("/").substringBefore(":").removePrefix("www.")
                            if (host.isBlank() || host.length < 4) continue
                            hosts.getOrPut(host) {
                                Finding(
                                    id = Ids.newId(),
                                    ruleId = "endpoint.host",
                                    title = "Endpoint host",
                                    detail = "This app talks to $host.",
                                    category = HitCategory.ENDPOINT,
                                    confidence = 40,
                                    file = rel,
                                    line = index + 1,
                                    snippet = url,
                                    symbol = ""
                                )
                            }
                        }
                    }
                    if (hits.size > maxFindings) return@withContext finish(hits, hosts, onLog)
                }
            }

            onLog("Checking the Java view for confirmation")
            javaDir.walkTopDown().forEach { file ->
                ensureActive()
                if (!file.isFile || !file.name.endsWith(".java") || file.length() > maxFileBytes) return@forEach
                val rel = file.relativeTo(javaDir).invariantSeparatorsPath
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                for (rule in Rules.all) {
                    if (!rule.pattern.containsMatchIn(text)) continue
                    hits.values.filter { it.ruleId == rule.id }.take(40).forEach { hit ->
                        if (hit.confidence < 95) {
                            hits[hit.ruleId + "|" + hit.file + "|" + hit.symbol] = hit.copy(
                                confidence = (hit.confidence + 10).coerceAtMost(99),
                                detail = hit.detail + " Confirmed in the readable Java view."
                            )
                        }
                    }
                }
            }

            onLog("Scanned $files smali files")
            finish(hits, hosts, onLog)
        }

    private fun finish(hits: Map<String, Finding>, hosts: Map<String, Finding>, onLog: (String) -> Unit): List<Finding> {
        val list = ArrayList<Finding>()
        list.addAll(hits.values.sortedByDescending { it.confidence })
        list.addAll(hosts.values.sortedBy { it.snippet })
        val byCategory = list.groupingBy { it.category }.eachCount()
        onLog("Found ${list.size} hits in ${byCategory.size} categories")
        return list
    }

    private fun symbolOf(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith(".method")) {
            return trimmed.substringAfter(" ", "").substringAfter(" ", "")
        }
        val method = Regex("invoke-[a-z/]+ \\{[^}]*}, (L[^;]+;->[^:]+)").find(trimmed)
        if (method != null) return method.groupValues[1]
        val direct = Regex("(L[^;\\s]+;->[A-Za-z0-9_$<>]+)").find(trimmed)
        return direct?.groupValues?.get(1) ?: ""
    }

    fun obfuscatedFiles(decodeDir: File): List<String> =
        decodeDir.walkTopDown()
            .filter { it.isFile && Rules.obfuscationClass.containsMatchIn(it.relativeTo(decodeDir).invariantSeparatorsPath) }
            .take(400)
            .map { it.relativeTo(decodeDir).invariantSeparatorsPath }
            .toList()
}
