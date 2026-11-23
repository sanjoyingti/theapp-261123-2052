package com.arin.bypass.engine

import com.arin.bypass.data.ProjectStore
import com.arin.bypass.model.Ids
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.PatchRecord
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Every write goes through here: an untouched copy is kept, a unified diff is
 * produced for the preview and a ledger entry is returned. Undo restores the copy.
 */
class Patcher(private val store: ProjectStore) {

    private fun target(project: Project, relPath: String): File = File(store.decodeDir(project.id), relPath)

    private fun backup(project: Project, relPath: String): File =
        File(store.backupDir(project.id), relPath + ".orig").apply { parentFile?.mkdirs() }

    suspend fun patchText(
        project: Project,
        relPath: String,
        title: String,
        kind: PatchKind,
        findingId: String?,
        transform: (String) -> String?
    ): PatchRecord? = withContext(Dispatchers.IO) {
        val file = target(project, relPath)
        if (!file.isFile) return@withContext null
        val before = runCatching { file.readText() }.getOrNull() ?: return@withContext null
        val after = transform(before) ?: return@withContext null
        if (before == after) return@withContext null
        val copy = backup(project, relPath)
        if (!copy.exists()) copy.writeText(before)
        file.writeText(after)
        PatchRecord(
            id = Ids.newId(),
            findingId = findingId,
            title = title,
            file = relPath,
            kind = kind,
            diff = Diff.unified(relPath, before, after),
            appliedAt = System.currentTimeMillis()
        )
    }

    suspend fun patchBytes(
        project: Project,
        relPath: String,
        title: String,
        kind: PatchKind,
        findingId: String?,
        transform: (ByteArray) -> ByteArray?
    ): PatchRecord? = withContext(Dispatchers.IO) {
        val file = target(project, relPath)
        if (!file.isFile) return@withContext null
        val before = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        val after = transform(before) ?: return@withContext null
        if (before.contentEquals(after)) return@withContext null
        val copy = backup(project, relPath)
        if (!copy.exists()) copy.writeBytes(before)
        file.writeBytes(after)
        val oldView = runCatching { Axml.summary(before) }.getOrDefault("(binary)")
        val newView = runCatching { Axml.summary(after) }.getOrDefault("(binary)")
        PatchRecord(
            id = Ids.newId(),
            findingId = findingId,
            title = title,
            file = relPath,
            kind = kind,
            diff = Diff.unified("$relPath.txt", oldView, newView),
            appliedAt = System.currentTimeMillis()
        )
    }

    suspend fun patchNewFile(
        project: Project,
        relPath: String,
        title: String,
        content: ByteArray
    ): PatchRecord? = withContext(Dispatchers.IO) {
        val file = target(project, relPath)
        if (file.exists()) return@withContext null
        file.parentFile?.mkdirs()
        file.writeBytes(content)
        PatchRecord(
            id = Ids.newId(),
            title = title,
            file = relPath,
            kind = PatchKind.NETSEC_XML,
            diff = Diff.unified(relPath, "", String(content).split("\n").joinToString("\n")),
            appliedAt = System.currentTimeMillis()
        )
    }

    suspend fun revert(project: Project, patchId: String): Project = withContext(Dispatchers.IO) {
        val patch = project.patches.firstOrNull { it.id == patchId } ?: return@withContext project
        restore(project, patch.file)
        project.copy(
            patches = project.patches.map { if (it.id == patchId) it.copy(reverted = true) else it },
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun revertAll(project: Project): Project = withContext(Dispatchers.IO) {
        project.patches.filter { !it.reverted }.reversed().forEach { restore(project, it.file) }
        project.copy(
            patches = project.patches.map { it.copy(reverted = true) },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun restore(project: Project, relPath: String) {
        val copy = backup(project, relPath)
        val file = target(project, relPath)
        if (copy.exists()) file.writeBytes(copy.readBytes())
    }

    fun previewOf(project: Project, relPath: String, transform: (String) -> String?): String {
        val file = target(project, relPath)
        if (!file.isFile) return ""
        val before = runCatching { file.readText() }.getOrNull() ?: return ""
        val after = transform(before) ?: return ""
        return Diff.unified(relPath, before, after)
    }
}
