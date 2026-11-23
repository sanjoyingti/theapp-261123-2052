package com.arin.bypass.engine

import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fixes the three manifest problems that stop a rebuilt app from installing. */
class ManifestRepair(private val patcher: Patcher) {

    private val minimumTarget = 34

    suspend fun repair(project: Project, onLog: (String) -> Unit): Pair<Project, List<String>> = withContext(Dispatchers.IO) {
        var current = project
        val notes = ArrayList<String>()

        val needsCleartext = current.findings.any { it.category == HitCategory.ENDPOINT && it.snippet.startsWith("http://") }
        val targetSdk = current.findings.isEmpty().let { 0 }

        val record = patcher.patchBytes(current, "AndroidManifest.xml", "Manifest repair", PatchKind.MANIFEST_ATTR, null) { bytes ->
            val doc = runCatching { Axml.parse(bytes) }.getOrNull() ?: return@patchBytes null
            val manifest = doc.manifest()
            val application = doc.application() ?: return@patchBytes null
            doc.ensureAndroidNamespace()

            var changed = false

            val currentTarget = manifest.intValue(Axml.ANDROID_NS, "targetSdkVersion") ?: 0
            if (currentTarget != 0 && currentTarget < minimumTarget) {
                manifest.setInt(Axml.ANDROID_NS, "targetSdkVersion", minimumTarget, Axml.ATTR_TARGET_SDK)
                notes.add("Target SDK raised from $currentTarget to $minimumTarget so it still installs.")
                changed = true
            }

            if (needsCleartext) {
                application.setBool(Axml.ANDROID_NS, "usesCleartextTraffic", true, Axml.ATTR_CLEARTEXT)
                notes.add("Plain http endpoints found, so cleartext traffic is allowed.")
                changed = true
            }

            val components = ArrayList<Axml.Element>()
            application.children.filter { it.name == "activity" || it.name == "service" || it.name == "receiver" || it.name == "provider" }
                .forEach { components.add(it) }
            for (component in components) {
                if (component.child("intent-filter") == null) continue
                if (component.attr(Axml.ANDROID_NS, "exported") != null) continue
                component.setBool(Axml.ANDROID_NS, "exported", true, Axml.ATTR_EXPORTED)
                notes.add("Exported flag added to ${component.stringValue(Axml.ANDROID_NS, "name") ?: "a component"} so it keeps working.")
                changed = true
            }

            if (!changed) null else runCatching { Axml.write(doc) }.getOrNull()
        }

        if (record != null) {
            current = current.copy(patches = current.patches + record)
            onLog("Manifest repaired: ${notes.size} fixes")
            current to notes
        } else {
            onLog("Manifest needs no repair")
            current to listOf("Manifest needed no repair. Target $targetSdk SDK.")
        }
    }
}
