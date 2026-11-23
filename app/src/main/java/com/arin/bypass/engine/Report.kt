package com.arin.bypass.engine

import com.arin.bypass.model.Project
import com.arin.bypass.util.Format

/** Writes the per-project markdown report: found, changed, still broken. */
class Report {

    fun markdown(project: Project, verdict: String): String {
        val text = StringBuilder()
        text.append("# Bypass report — ").append(project.name).append("\n\n")
        text.append("- Package: `").append(project.packageName).append("`\n")
        project.renameTo?.let { text.append("- Clone package: `").append(it).append("`\n") }
        text.append("- Version: ").append(project.versionName.ifBlank { "unknown" }).append('\n')
        text.append("- Started: ").append(Format.day(project.createdAt)).append('\n')
        text.append("- Untouched original hash: ").append(project.originalSha256).append('\n')
        text.append("- Working copy hash: ").append(project.workingSha256).append('\n')
        text.append("- Rounds: ").append(project.rounds).append('\n')
        text.append("- Verdict: ").append(verdict).append("\n\n")

        text.append("## Found\n\n")
        if (project.findings.isEmpty()) {
            text.append("Nothing was detected.\n")
        } else {
            project.findings.sortedByDescending { it.confidence }.forEach { hit ->
                text.append("- [").append(hit.confidence).append("%] ").append(hit.title)
                text.append(" — `").append(hit.file).append('`')
                if (hit.line > 0) text.append(" line ").append(hit.line)
                if (hit.patched) text.append(" (patched)") else if (hit.dismissed) text.append(" (dismissed)")
                text.append('\n')
            }
        }

        text.append("\n## Changed\n\n")
        val applied = project.patches.filter { !it.reverted }
        if (applied.isEmpty()) {
            text.append("No change was applied.\n")
        } else {
            applied.forEach { patch ->
                text.append("### ").append(patch.title).append(" — `").append(patch.file).append("`\n\n")
                text.append("```diff\n").append(patch.diff).append("```\n\n")
            }
        }
        val undone = project.patches.count { it.reverted }
        if (undone > 0) text.append(undone).append(" patch(es) were reverted.\n")

        text.append("\n## Still broken\n\n")
        if (project.stillBroken.isEmpty()) text.append("Nothing known.\n")
        else project.stillBroken.forEach { text.append("- ").append(it).append('\n') }

        text.append("\n## Log (last 80 lines)\n\n```\n")
        project.logs.takeLast(80).forEach { line ->
            text.append(Format.time(line.time)).append(' ').append(line.level).append(": ").append(line.message).append('\n')
        }
        text.append("```\n")
        return text.toString()
    }
}
