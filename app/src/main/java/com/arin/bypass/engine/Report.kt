package com.arin.bypass.engine

import com.arin.bypass.model.Project
import com.arin.bypass.util.Format

/** Builds the per-project markdown report: what was found, what changed, what is still wrong. */
object Report {

    fun markdown(project: Project): String {
        val builder = StringBuilder()
        builder.append("# Report: ").append(project.name).append("\n\n")
        builder.append("- Package: `").append(project.packageName).append("`\n")
        builder.append("- Version: ").append(project.versionName.ifBlank { "unknown" }).append("\n")
        builder.append("- Created: ").append(Format.day(project.createdAt)).append("\n")
        builder.append("- Stage: ").append(project.stage.name).append("\n")
        builder.append("- Rounds used: ").append(project.rounds).append("\n")
        builder.append("- Renamed to: ").append(project.renameTo ?: "not renamed").append("\n")
        builder.append("- Dry run: ").append(if (project.dryRun) "yes" else "no").append("\n\n")

        builder.append("## Found\n\n")
        val found = project.findings.filter { !it.dismissed }
        if (found.isEmpty()) {
            builder.append("Nothing matched the scanner rules.\n\n")
        } else {
            for (finding in found.sortedByDescending { it.confidence }.take(150)) {
                builder.append("- **").append(finding.title).append("** (")
                    .append(finding.confidence).append("%, ")
                    .append(finding.category.name.lowercase()).append(") `")
                    .append(finding.file)
                if (finding.line > 0) builder.append(":").append(finding.line)
                builder.append("`")
                if (finding.symbol.isNotBlank()) builder.append(" - `").append(finding.symbol).append("`")
                builder.append("\n")
            }
            builder.append("\n")
        }

        builder.append("## Changed\n\n")
        val applied = project.patches.filter { !it.reverted }
        if (applied.isEmpty()) {
            builder.append("No patch was applied.\n\n")
        } else {
            for (patch in applied) {
                builder.append("- ").append(patch.title).append(" in `").append(patch.file)
                    .append("` (").append(Format.day(patch.appliedAt)).append(")\n")
            }
            builder.append("\n### Diffs\n\n")
            for (patch in applied) {
                builder.append("```diff\n").append(patch.diff.trim()).append("\n```\n\n")
            }
        }

        builder.append("## Still broken\n\n")
        if (project.stillBroken.isEmpty()) {
            builder.append("Nothing is flagged.\n\n")
        } else {
            for (item in project.stillBroken) builder.append("- ").append(item).append("\n")
            builder.append("\n")
        }

        builder.append("## Log\n\n```\n")
        project.logs.takeLast(200).forEach {
            builder.append(Format.time(it.time)).append(' ').append(it.level).append(' ').append(it.message).append('\n')
        }
        builder.append("```\n")
        return builder.toString()
    }
}
