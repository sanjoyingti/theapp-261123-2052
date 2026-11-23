package com.arin.bypass.engine

import com.arin.bypass.model.Finding
import com.arin.bypass.model.HitCategory
import com.arin.bypass.model.PatchKind
import com.arin.bypass.model.Project

/** Maps a finding to the edit that neutralises it. Nothing else is ever touched. */
class Actions(private val patcher: Patcher) {

    data class Result(val project: Project, val message: String, val diff: String)

    suspend fun patch(project: Project, finding: Finding): Result {
        if (finding.file.isBlank()) return Result(project, "This hit has no file to edit.", "")

        val answer = when (finding.category) {
            HitCategory.FORCE_UPDATE -> SmaliEdit.Answer.FALSE
            HitCategory.PINNING -> SmaliEdit.Answer.NULL
            HitCategory.SIGNATURE, HitCategory.AUTH, HitCategory.TOKEN, HitCategory.FLAG,
            HitCategory.REMOTE_CONFIG, HitCategory.NSFW, HitCategory.BILLING, HitCategory.PAYWALL ->
                SmaliEdit.Answer.TRUE
            else -> SmaliEdit.Answer.TRUE
        }

        val record = if (finding.symbol.isNotBlank()) {
            patcher.patchText(project, finding.file, finding.title, PatchKind.DEX_EARLY_RETURN, finding.id) { text ->
                SmaliEdit.forceReturn(text, finding.symbol, answer)
            }
        } else null

        val fallback = if (record == null && finding.category == HitCategory.ENDPOINT && finding.snippet.isNotBlank()) {
            patcher.patchText(project, finding.file, finding.title, PatchKind.DEX_STRING, finding.id) { text ->
                SmaliEdit.replaceString(text, finding.snippet, finding.snippet)
            }
        } else null

        val made = record ?: fallback
        return if (made == null) {
            Result(project, "Nothing to change in ${finding.file.substringAfterLast('/')} for this hit.", "")
        } else {
            Result(
                project.copy(
                    findings = project.findings.map { if (it.id == finding.id) it.copy(patched = true) else it },
                    patches = project.patches + made,
                    updatedAt = System.currentTimeMillis()
                ),
                "Patched: ${finding.title}",
                made.diff
            )
        }
    }

    suspend fun replaceEndpoint(project: Project, finding: Finding, newUrl: String): Result {
        val record = patcher.patchText(project, finding.file, "Base URL → $newUrl", PatchKind.DEX_STRING, finding.id) { text ->
            SmaliEdit.replaceString(text, finding.snippet, newUrl)
        } ?: return Result(project, "That URL string is no longer in the file.", "")
        return Result(
            project.copy(
                findings = project.findings.map { if (it.id == finding.id) it.copy(patched = true) else it },
                patches = project.patches + record,
                updatedAt = System.currentTimeMillis()
            ),
            "Base URL replaced",
            record.diff
        )
    }

    fun preview(project: Project, finding: Finding): String {
        if (finding.file.isBlank() || finding.symbol.isBlank()) return ""
        return patcher.previewOf(project, finding.file) { text ->
            SmaliEdit.forceReturn(
                text,
                finding.symbol,
                if (finding.category == HitCategory.FORCE_UPDATE) SmaliEdit.Answer.FALSE else SmaliEdit.Answer.TRUE
            )
        }
    }
}
