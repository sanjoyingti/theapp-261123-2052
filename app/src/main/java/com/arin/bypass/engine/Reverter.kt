package com.arin.bypass.engine

import com.arin.bypass.data.ProjectStore
import com.arin.bypass.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Restores the untouched copy of a file and marks its ledger entry as undone. */
object Reverter {

    suspend fun revert(project: Project, patchId: String, store: ProjectStore): Project =
        withContext(Dispatchers.IO) {
            val patch = project.patches.firstOrNull { it.id == patchId } ?: return@withContext project
            val copy = File(store.backupDir(project.id), patch.file + ".orig")
            val work = File(store.decodeDir(project.id), patch.file)
            if (copy.isFile) runCatching { work.writeBytes(copy.readBytes()) }
            project.copy(
                patches = project.patches.map { if (it.id == patchId) it.copy(reverted = true) else it },
                updatedAt = System.currentTimeMillis()
            )
        }
}
