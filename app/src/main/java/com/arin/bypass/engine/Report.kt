package com.arin.bypass.engine

import com.arin.bypass.model.Project
import com.arin.bypass.util.Format

/** Builds the markdown report for one project, in plain words. */
class Report {

    fun markdown(project: Project): String = buildString {
        append("# ").append(project.name).append("\n\n")
        append("- Package: `").append(project.packageName).append("`\n")
        append("- Version: ").append(project.versionName.ifBlank { "unknown" }).append("\n")
        append("- Created: ").append(Format.day(project.createdAt)).append("\n")
        append("- Stage: ").append(project.stage.name).append("\n")
        append("- Original hash: `").append(Format.sha(project.originalSha256)).append("`\n")
        append("- Working hash: `").append(Format.sha(project.workingSha256)).append("`\n\n")

        append("## Applied changes\n\n")
        val live = project.patches.filter { !it.reverted }
        if (live.isEmpty()) {
            append("No change was applied.\n\n")
        } else {
            live.forEach { append("- ").append(it.title).append(" in `").append(it.file).append("`\n") }
            append('\n')
        }

        val reverted = project.patches.count { it.reverted }
        if (reverted > 0) append("Reverted changes: ").append(reverted).append("\n\n")

        append("## Detected hits\n\n")
        val findings = project.findings.filter { !it.dismissed }
        if (findings.isEmpty()) {
            append("Nothing was detected.\n\n")
        } else {
            findings.sortedByDescending { it.confidence }.take(120).forEach {
                append("- [").append(it.category.name).append("] ").append(it.title)
                append(" (").append(it.confidence).append("%) at `").append(it.file)
                if (it.line > 0) append(':').append(it.line)
                append("`\n")
            }
            append('\n')
        }

        if (project.entitlementSymbols.isNotEmpty()) {
            append("## Entitlement producers\n\n")
            project.entitlementSymbols.forEach { append("- `").append(it).append("`\n") }
            append('\n')
        }

        if (project.stillBroken.isNotEmpty()) {
            append("## Still open\n\n")
            project.stillBroken.forEach { append("- ").append(it).append("\n") }
            append('\n')
        }

        append("## Notes\n\n")
        append("The untouched original is kept at `original/").append(project.baseApkName)
        append("` and every change can be reverted on its own.\n")
    }
}
