package com.arin.bypass.engine

import com.arin.bypass.model.Project
import com.arin.bypass.util.Format

/** Builds the plain-language markdown report for one project. */
class Report {

    fun markdown(project: Project): String {
        val out = StringBuilder()
        out.append("# Bypass report — ").append(project.name).append("\n\n")
        out.append("- Package: `").append(project.packageName).append("`\n")
        out.append("- Version: ").append(project.versionName.ifBlank { "unknown" }).append('\n')
        out.append("- Created: ").append(Format.day(project.createdAt)).append('\n')
        out.append("- Stage: ").append(project.stage.name).append('\n')
        out.append("- Dry run: ").append(if (project.dryRun) "yes" else "no").append('\n')
        out.append("- Rounds used: ").append(project.rounds).append('\n')
        out.append("- Original fingerprint: ").append(Format.sha(project.originalSha256)).append("\n\n")

        out.append("## Found (").append(project.findings.size).append(")\n\n")
        if (project.findings.isEmpty()) {
            out.append("- Nothing detected.\n")
        } else {
            for (finding in project.findings.sortedByDescending { it.confidence }) {
                out.append("- **").append(finding.title).append("** (")
                    .append(finding.category.name).append(", ")
                    .append(finding.confidence).append("%) at `")
                    .append(finding.file).append(':').append(finding.line).append('`')
                if (finding.patched) out.append(" — patched")
                if (finding.dismissed) out.append(" — dismissed")
                out.append('\n')
            }
        }

        out.append("\n## Changed (").append(project.patches.count { !it.reverted }).append(")\n\n")
        if (project.patches.isEmpty()) {
            out.append("- Nothing changed yet.\n")
        } else {
            for (patch in project.patches) {
                out.append("- ")
                if (patch.reverted) out.append("~~reverted~~ ") else out.append("applied ")
                out.append(patch.title).append(" in `").append(patch.file).append("`\n")
            }
        }

        out.append("\n## Still broken\n\n")
        if (project.stillBroken.isEmpty()) {
            out.append("- Nothing known right now.\n")
        } else {
            for (item in project.stillBroken) out.append("- ").append(item).append('\n')
        }

        out.append("\n## Log tail\n\n```\n")
        for (line in project.logs.takeLast(60)) {
            out.append(Format.time(line.time)).append(' ').append(line.level).append(' ').append(line.message).append('\n')
        }
        out.append("```\n")
        return out.toString()
    }
}
