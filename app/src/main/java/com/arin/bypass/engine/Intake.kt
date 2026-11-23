package com.arin.bypass.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.arin.bypass.data.ProjectStore
import com.arin.bypass.model.Ids
import com.arin.bypass.model.Project
import com.arin.bypass.model.Stage
import com.arin.bypass.util.Hashing
import com.arin.bypass.util.Trouble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/** Takes an APK, an APKS or an XAPK (or an installed app) and prepares a project folder. */
class Intake(private val context: Context, private val store: ProjectStore) {

    suspend fun fromUri(uri: Uri, onLog: (String) -> Unit): Result<Project> = withContext(Dispatchers.IO) {
        try {
            val display = displayName(uri) ?: "picked.apk"
            val lower = display.lowercase()
            val kind = when {
                lower.endsWith(".xapk") -> "xapk"
                lower.endsWith(".apks") -> "apks"
                lower.endsWith(".apk") -> "apk"
                else -> return@withContext Result.failure(Exception("Pick a .apk, .apks or .xapk file."))
            }
            val tempDir = File(context.cacheDir, "intake").apply { mkdirs() }
            val temp = File(tempDir, "incoming.$kind")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return@withContext Result.failure(Exception("That file could not be opened."))

            if (temp.length() < 1024) return@withContext Result.failure(Exception("That file looks empty."))

            var baseApk = temp
            var splits = emptyList<String>()
            var packageHint = ""
            var versionHint = ""

            if (kind != "apk") {
                onLog("Reading the archive")
                ZipFile(temp).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    val baseName = names.firstOrNull { it.equals("base.apk", true) }
                        ?: return@withContext Result.failure(Exception("The archive has no base.apk inside it."))
                    splits = names.filter { it.endsWith(".apk") && it != baseName }
                    val extracted = File(tempDir, "base.apk")
                    zip.getInputStream(zip.getEntry(baseName)).use { input ->
                        extracted.outputStream().use { input.copyTo(it) }
                    }
                    baseApk = extracted
                    if (kind == "xapk") {
                        names.firstOrNull { it.equals("manifest.json", true) }?.let { metaName ->
                            runCatching {
                                val text = zip.getInputStream(zip.getEntry(metaName)).use { it.readBytes().decodeToString() }
                                val obj = Json.parseToJsonElement(text).jsonObject
                                packageHint = obj["package_name"]?.jsonPrimitive?.content ?: ""
                                versionHint = obj["version_name"]?.jsonPrimitive?.content ?: ""
                            }
                        }
                    }
                }
            }

            if (!ZipKit.isApk(baseApk)) {
                return@withContext Result.failure(Exception("That file has no AndroidManifest.xml, so it is not an installable app."))
            }

            val manifest = Axml.parse(ZipKit.read(baseApk, "AndroidManifest.xml") ?: ByteArray(0))
            val packageName = manifest.manifest().stringValue(null, "package") ?: packageHint
            if (packageName.isBlank()) {
                return@withContext Result.failure(Exception("The package name could not be read from the manifest."))
            }
            val versionName = manifest.manifest().stringValue(Axml.ANDROID_NS, "versionName") ?: versionHint

            val missing = missingSplits(splits)
            if (missing.isNotEmpty()) onLog("Warning: ${missing.size} split parts for this phone are missing: ${missing.joinToString(", ")}")

            val project = newProject(
                name = display.substringBeforeLast('.').ifBlank { packageName },
                packageName = packageName,
                versionName = versionName,
                sourceKind = kind,
                splits = splits,
                missing = missing,
                baseApk = baseApk
            )
            onLog("Project created: ${project.name}")
            Result.success(project)
        } catch (e: Exception) {
            Result.failure(Exception(Trouble.plain(e)))
        }
    }

    suspend fun fromInstalled(packageName: String, onLog: (String) -> Unit): Result<Project> = withContext(Dispatchers.IO) {
        try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val app = info.applicationInfo ?: return@withContext Result.failure(Exception("That app could not be read."))
            val base = File(app.sourceDir)
            if (!base.isFile) return@withContext Result.failure(Exception("The installed app has no readable base file."))
            val splits = app.splitSourceDirs?.map { File(it).name } ?: emptyList()
            val missing = missingSplits(splits)
            if (missing.isNotEmpty()) onLog("Warning: split parts seem missing: ${missing.joinToString(", ")}")
            val label = runCatching { app.loadLabel(context.packageManager).toString() }.getOrDefault(packageName)
            val project = newProject(
                name = label.ifBlank { packageName },
                packageName = packageName,
                versionName = info.versionName ?: "",
                sourceKind = "installed",
                splits = splits,
                missing = missing,
                baseApk = base
            )
            onLog("Project created from the installed app: ${project.name}")
            Result.success(project)
        } catch (e: Exception) {
            Result.failure(Exception(Trouble.plain(e)))
        }
    }

    private suspend fun newProject(
        name: String,
        packageName: String,
        versionName: String,
        sourceKind: String,
        splits: List<String>,
        missing: List<String>,
        baseApk: File
    ): Project {
        val id = Ids.newId()
        val created = System.currentTimeMillis()
        val project = Project(
            id = id,
            name = name,
            packageName = packageName,
            versionName = versionName,
            sourceKind = sourceKind,
            createdAt = created,
            updatedAt = created,
            stage = Stage.INTAKE,
            splitsPresent = splits.take(60),
            missingSplits = missing
        )
        // byte-identical untouched original first, then the working copy
        baseApk.copyTo(store.originalFile(project), overwrite = true)
        baseApk.copyTo(store.workingFile(project), overwrite = true)
        val saved = project.copy(
            originalSha256 = Hashing.sha256File(store.originalFile(project)),
            workingSha256 = Hashing.sha256File(store.workingFile(project))
        )
        store.save(saved)
        return saved
    }

    private fun missingSplits(splits: List<String>): List<String> {
        if (splits.isEmpty()) return emptyList()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return emptyList()
        val density = when (context.resources.displayMetrics.densityDpi) {
            in 0..140 -> "ldpi"
            in 141..200 -> "mdpi"
            in 201..280 -> "hdpi"
            in 281..400 -> "xhdpi"
            in 401..560 -> "xxhdpi"
            else -> "xxxhdpi"
        }
        val wanted = listOf("config.$abi", "config.$density")
        return wanted.filter { want -> splits.none { it.contains(want, ignoreCase = true) } }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
