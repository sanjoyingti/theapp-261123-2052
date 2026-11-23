package com.arin.bypass.model

import kotlinx.serialization.Serializable

/** Ordered pipeline stages of one project. */
@Serializable
enum class Stage {
    INTAKE, TOOLCHAIN, DECODE, SCAN, INTEGRITY, BILLING, REVIEW, REBUILD, VERIFY, DONE, STOPPED
}

@Serializable
enum class HitCategory {
    NETWORK, PINNING, SIGNATURE, AUTH, TOKEN, FLAG, REMOTE_CONFIG, NSFW, FORCE_UPDATE, BILLING, PAYWALL, ENDPOINT, OBFUSCATION
}

@Serializable
enum class PatchKind {
    DEX_EARLY_RETURN, DEX_STRING, MANIFEST_ATTR, NETSEC_XML, RENAME, NOTE
}

/** One thing the scanner found, with where it is and how sure we are. */
@Serializable
data class Finding(
    val id: String,
    val ruleId: String,
    val title: String,
    val detail: String,
    val category: HitCategory,
    val confidence: Int,
    val file: String,
    val dexOffset: Int = -1,
    val line: Int = -1,
    val snippet: String = "",
    val symbol: String = "",
    val patched: Boolean = false,
    val dismissed: Boolean = false
)

/** One applied change, kept so it can be reverted on its own. */
@Serializable
data class PatchRecord(
    val id: String,
    val findingId: String? = null,
    val title: String,
    val file: String,
    val kind: PatchKind,
    val diff: String,
    val appliedAt: Long,
    val reverted: Boolean = false
)

@Serializable
data class LogLine(
    val time: Long,
    val level: String,
    val message: String
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val packageName: String,
    val versionName: String = "",
    val sourceKind: String = "apk",
    val createdAt: Long,
    val updatedAt: Long,
    val stage: Stage = Stage.INTAKE,
    val originalSha256: String = "",
    val workingSha256: String = "",
    val baseApkName: String = "base.apk",
    val splitsPresent: List<String> = emptyList(),
    val missingSplits: List<String> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val patches: List<PatchRecord> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val renameTo: String? = null,
    val dryRun: Boolean = true,
    val rounds: Int = 0,
    val crashLooping: Boolean = false,
    val stopped: Boolean = false,
    val exported: Boolean = false,
    val outputApk: String? = null,
    val report: String = "",
    val entitlementSymbols: List<String> = emptyList(),
    val stillBroken: List<String> = emptyList()
) {
    val title: String get() = name
    val openFindings: Int get() = findings.count { !it.dismissed && !it.patched }
    val appliedPatches: Int get() = patches.count { !it.reverted }
}

@Serializable
data class Settings(
    val dryRun: Boolean = true,
    val renameClones: Boolean = true,
    val autoScan: Boolean = true,
    val roundLimit: Int = 6,
    val toolchainReady: Boolean = false
)

object Ids {
    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
}
