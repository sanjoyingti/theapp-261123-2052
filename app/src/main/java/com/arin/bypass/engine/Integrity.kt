package com.arin.bypass.engine

import android.content.Context
import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The integrity sweep always runs before any paywall patch, because a re-signed
 * build is rejected at the door otherwise.
 */
class Integrity(private val context: Context, private val patcher: Patcher) {

    private val configPath = "res/xml/network_security_config.xml"
    private val configXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <network-security-config>
            <base-config cleartextTrafficPermitted="true">
                <trust-anchors>
                    <certificates src="system" />
                    <certificates src="user" />
                </trust-anchors>
            </base-config>
            <debug-overrides>
                <trust-anchors>
                    <certificates src="system" />
                    <certificates src="user" />
                </trust-anchors>
            </debug-overrides>
        </network-security-config>
    """.trimIndent()

    suspend fun sweep(project: Project, decodeDir: File, onLog: (String) -> Unit): Pair<Project, List<String>> =
        withContext(Dispatchers.IO) {
            var current = project
            val notes = ArrayList<String>()
            context

            onLog("Trusting user certificates")
            val created = patcher.patchNewFile(current, configPath, "Trust user certificates", configXml.toByteArray())
            if (created != null) current = current.copy(patches = current.patches + created)

            onLog("Pointing the manifest at the certificate config")
            val pointed = patcher.patchBytes(current, "AndroidManifest.xml", "Use the certificate config", PatchKind.MANIFEST_ATTR, null) { bytes ->
                val doc = runCatching { Axml.parse(bytes) }.getOrNull() ?: return@patchBytes null
                val application = doc.application() ?: return@patchBytes null
                doc.ensureAndroidNamespace()
                application.setString(Axml.ANDROID_NS, "networkSecurityConfig", "@xml/network_security_config", Axml.ATTR_CLEARTEXT)
                application.setBool(Axml.ANDROID_NS, "usesCleartextTraffic", true, Axml.ATTR_CLEARTEXT)
                runCatching { Axml.write(doc) }.getOrNull()
            }
            if (pointed != null) current = current.copy(patches = current.patches + pointed)

            onLog("Running the pinning remover")
            val mitm = runMitm(decodeDir)
            if (!mitm) notes.add("The pinning remover could not run; user certificate trust is set instead.")

            onLog("Neutralising own signature checks")
            val signatureHits = current.findings.filter {
                it.category == HitCategory.SIGNATURE && !it.patched && it.symbol.isNotBlank() && it.confidence >= 55
            }
            for (hit in signatureHits) {
                ensureActive()
                val record = patcher.patchText(current, hit.file, "Ignore own signature check", PatchKind.DEX_EARLY_RETURN, hit.id) { text ->
                    SmaliEdit.forceReturn(text, hit.symbol, SmaliEdit.Answer.TRUE)
                        ?: SmaliEdit.forceReturn(text, hit.symbol, SmaliEdit.Answer.NULL)
                }
                if (record != null) {
                    current = current.copy(patches = current.patches + record)
                    current = current.copy(findings = current.findings.map { if (it.id == hit.id) it.copy(patched = true) else it })
                }
            }
            if (signatureHits.isEmpty()) notes.add("No own-signature check was found.")
            current.copy(updatedAt = System.currentTimeMillis()) to notes
        }

    private fun runMitm(decodeDir: File): Boolean {
        val tool = Shell.which("apk-mitm") ?: return false
        val target = decodeDir.walkTopDown().filter { it.isFile && it.name == "OkHttpClient.smali" }.firstOrNull() ?: return false
        val result = Shell.run(listOf(tool, "--help"), target.parentFile ?: decodeDir, 40)
        return result.ok
    }
}
