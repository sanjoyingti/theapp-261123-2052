package com.arin.bypass.data

import android.content.Context
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** All project folders live under the app's private storage and never leave it unasked. */
class ProjectStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun root(): File = File(context.filesDir, "projects").apply { mkdirs() }

    fun exports(): File = File(context.filesDir, "exports").apply { mkdirs() }

    fun dir(id: String): File = File(root(), id).apply { mkdirs() }

    fun originalDir(id: String): File = File(dir(id), "original").apply { mkdirs() }

    fun workDir(id: String): File = File(dir(id), "work").apply { mkdirs() }

    fun decodeDir(id: String): File = File(dir(id), "decode").apply { mkdirs() }

    fun javaDir(id: String): File = File(dir(id), "java").apply { mkdirs() }

    fun backupDir(id: String): File = File(dir(id), "backup").apply { mkdirs() }

    fun outDir(id: String): File = File(dir(id), "out").apply { mkdirs() }

    fun originalFile(project: Project): File = File(originalDir(project.id), project.baseApkName)

    fun workingFile(project: Project): File = File(workDir(project.id), project.baseApkName)

    private fun record(id: String): File = File(dir(id), "project.json")

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        record(project.id).writeText(json.encodeToString(Project.serializer(), project))
    }

    fun load(id: String): Project? = runCatching {
        val file = record(id)
        if (!file.exists()) null else json.decodeFromString(Project.serializer(), file.readText())
    }.getOrNull()

    fun list(): List<Project> {
        val files = root().listFiles() ?: return emptyList()
        return files.mapNotNull { folder ->
            val file = File(folder, "project.json")
            if (!file.exists()) null else runCatching {
                json.decodeFromString(Project.serializer(), file.readText())
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun sizeOf(id: String): Long = dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun writeReport(project: Project, markdown: String): File = withContext(Dispatchers.IO) {
        val file = File(dir(project.id), "report.md")
        file.writeText(markdown)
        file
    }

    suspend fun readReport(project: Project): String = withContext(Dispatchers.IO) {
        val file = File(dir(project.id), "report.md")
        if (file.exists()) file.readText() else ""
    }

    suspend fun exportCopy(project: Project, apk: File): File = withContext(Dispatchers.IO) {
        val name = "${project.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${System.currentTimeMillis()}.apk"
        val target = File(exports(), name)
        apk.copyTo(target, overwrite = true)
        target
    }
}
