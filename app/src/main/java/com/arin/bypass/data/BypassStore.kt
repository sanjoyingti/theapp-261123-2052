package com.arin.bypass.data

import android.content.Context
import android.net.Uri
import com.arin.bypass.engine.Intake
import com.arin.bypass.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Single place the screens read and write projects through. Touches disk only on use. */
class BypassStore(private val context: Context) {

    private val projects = ProjectStore(context)
    private val intake = Intake(context, projects)

    private val _list = MutableStateFlow<List<Project>>(emptyList())
    val list: StateFlow<List<Project>> = _list.asStateFlow()

    fun refresh() {
        _list.value = projects.list()
    }

    fun project(id: String): Project? = projects.load(id)

    suspend fun save(project: Project) {
        projects.save(project)
        refresh()
    }

    suspend fun delete(id: String) {
        projects.delete(id)
        refresh()
    }

    suspend fun importUri(uri: Uri, onLog: (String) -> Unit): Result<Project> {
        val result = intake.fromUri(uri, onLog)
        result.onSuccess { refresh() }
        return result
    }

    suspend fun importInstalled(packageName: String, onLog: (String) -> Unit): Result<Project> {
        val result = intake.fromInstalled(packageName, onLog)
        result.onSuccess { refresh() }
        return result
    }

    fun directory(id: String): File = projects.dir(id)

    fun store(): ProjectStore = projects
}
