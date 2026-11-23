package com.arin.bypass.engine

import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Moves the smali folders and rewrites the package everywhere it appears. */
class Renamer(private val patcher: Patcher) {

    suspend fun rename(project: Project, decodeDir: File, newPackage: String, onLog: (String) -> Unit): Pair<Project, String> =
        withContext(Dispatchers.IO) {
            val old = project.packageName
            if (old == newPackage) return@withContext project to "Package unchanged"
            val oldPath = old.replace('.', '/')
            val newPath = newPackage.replace('.', '/')

            onLog("Renaming $old to $newPackage")
            val manifest = patcher.patchBytes(project, "AndroidManifest.xml", "Rename package", PatchKind.RENAME, null) { bytes ->
                val text = String(bytes, Charsets.ISO_8859_1)
                val doc = runCatching { Axml.parse(bytes) }.getOrNull() ?: return@patchBytes null
                doc.manifest().setString(null, "package", newPackage)
                runCatching { Axml.write(doc) }.getOrNull().also { text.length }
            }
            var current = project.copy(renameTo = newPackage)
            if (manifest != null) current = current.copy(patches = current.patches + manifest)

            val folders = decodeDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("smali") } ?: emptyList()
            for (folder in folders) {
                val from = File(folder, oldPath)
                if (!from.isDirectory) continue
                val to = File(folder, newPath)
                to.parentFile?.mkdirs()
                if (!to.exists()) from.copyRecursively(to, overwrite = true) else from.copyRecursively(to, overwrite = true)
                from.deleteRecursively()
            }

            // rewrite the descriptor text inside every smali file
            val marker = "L" + oldPath + "/"
            val replacement = "L" + newPath + "/"
            var touched = 0
            decodeDir.walkTopDown().filter { it.isFile && it.name.endsWith(".smali") }.forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                if (!text.contains(marker)) return@forEach
                runCatching { file.writeText(text.replace(marker, replacement)) }
                touched++
            }
            onLog("Rewrote $touched files for the new package")
            current.copy(updatedAt = System.currentTimeMillis()) to "Renamed to $newPackage"
        }

    fun suggest(packageName: String): String = if (packageName.endsWith(".clone")) "$packageName.2" else "$packageName.clone"
}
