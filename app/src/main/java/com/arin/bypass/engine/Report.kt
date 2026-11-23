package com.arin.bypass.engine

import com.arin.bypass.model.Project
import com.arin.bypass.util.Format

/** Builds the per-project markdown report: what was found, what changed, what is still wrong. */
class Report {

    fun markdown(project: Project): String = build(project)

    companion object {

        fun markdown(project: Project): String = build(project)

        private fun build(project: Project): String {
            val out = StringBuilder()
            out.append("# Report: ").append(project.name).append("\n\n")
            out.append("- Package: `").append(project.packageName).append("`\n")
            out.append("- Version: ").append(project.versionName.ifBlank { "unknown" }).append("\n")
            out.append("- Created: ").append(Format.day(project.createdAt)).append("\n")
            out.append("- Updated: ").append(Format.day(project.updatedAt)).append("\n")
            out.append("- Stage: ").append(project.stage.name).append("\n")
            out.append("- Rounds used: ").append(project.rounds).append("\n")
            out.append("- Renamed to: ").append(project.renameTo ?: "not renamed").append("\n")
            out.append("- Dry run: ").append(if (project.dryRun) "yes" else "no").append("\n")
            out.append("- Original SHA-256: `").append(project.originalSha256.ifBlank { "unknown" }).append("`\n")
            out.append("- Working SHA-256: `").append(project.workingSha256.ifBlank { "unknown" }).append("`\n\n")

            out.append("## Found\n\n")
            val found = project.findings.filter { !it.dismissed }
            if (found.isEmpty()) {
                out.append("Nothing matched the scanner rules.\n\n")
            } else {
                for (hit in found.sortedByDescending { it.confidence }.take(200)) {
                    out.append("- **").append(hit.title).append("** — ")
                        .append(hit.confidence).append("% confidence, ")
                        .append(hit.category.name.lowercase()).append(", `").append(hit.file)
                    if (hit.line > 0) out.append(":").append(hit.line)
                    out.append("`\n")
                    if (hit.detail.isNotBlank()) out.append("  - ").append(hit.detail).append("\n")
                    if (hit.symbol.isNotBlank()) out.append("  - symbol: `").append(hit.symbol).append("`\n")
                    out.append("  - ").append(if (hit.patched) "patched" else "not patched").append("\n")
                }
                out.append('\n')
            }

            out.append("## Changed\n\n")
            val applied = project.patches.filter { !it.reverted }
            if (applied.isEmpty()) {
                out.append("No change has been written for this project yet.\n\n")
            } else {
                for (patch in applied) {
                    out.append("### ").append(patch.title).append("\n\n")
                    out.append("- File: `").append(patch.file).append("`\n")
                    out.append("- Kind: ").append(patch.kind.name).append("\n")
                    out.append("- When: ").append(Format.day(patch.appliedAt)).append("\n\n")
                    out.append("```diff\n").append(patch.diff.trimEnd()).append("\n```\n\n")
                }
            }
            val reverted = project.patches.count { it.reverted }
            if (reverted > 0) out.append("Reverted patches: ").append(reverted).append("\n\n")

            out.append("## Entitlement producers touched\n\n")
            if (project.entitlementSymbols.isEmpty()) {
                out.append("None reached.\n\n")
            } else {
                for (symbol in project.entitlementSymbols) {
                    out.append("- `").append(symbol).append("`\n")
                }
                out.append('\n')
            }

            out.append("## Still broken\n\n")
            if (project.stillBroken.isEmpty() && !project.crashLooping) {
                out.append("Nothing is flagged right now.\n\n")
            } else {
                if (project.crashLooping) {
                    out.append("- The clone crash-loops, so the export gate stays closed.\n")
                }
                for (line in project.stillBroken) out.append("- ").append(line).append("\n")
                out.append('\n')
                out.append("> ").append(hintFor(project)).append("\n\n")
            }

            out.append("## Tool log\n\n")
            val logs = project.logs.sortedBy { it.time }.takeLast(120)
            if (logs.isEmpty()) {
                out.append("No tool call has been logged yet.\n")
            } else {
                for (line in logs) {
                    out.append("- ").append(Format.time(line.time)).append(" [")
                        .append(line.level).append("] ").append(line.message).append("\n")
                }
            }
            return out.toString()
        }

        private fun hintFor(project: Project): String = when {
            project.rounds >= 6 -> "Six rounds are used up. Stop here and read the diff before trying again."
            project.crashLooping -> "A crash is usually a signature check or a lost manifest export. The integrity sweep runs first next round."
            project.stillBroken.any { it.contains("paywall", true) } ->
                "A bounce means the entitlement producer was not reached. The next round widens the billing patch to the stored seeds."
            else -> "The next round repeats the repairs with one more fix on top."
        }
    }
}
