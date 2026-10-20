package com.ensemble.scorelibrary.domain.sync

data class SyncIdentity(val accountId: String, val deviceId: String)
data class SyncSession(val identity: SyncIdentity, val sessionId: String, val sessionEpoch: Long)

/** Mapping includes the transformation identity; a verifier must check its actual geometry. */
data class SyncPage(val sourcePage: Int, val transform: String)
data class SyncArtifact(
    val artifactId: String,
    val sourceVersionId: String,
    val hash: String,
    val size: Long,
    val pageMap: List<SyncPage>,
) {
    val pageCount: Int get() = pageMap.size
}

/** Full authorized replacement collection, never a patch to an existing collection. */
data class SyncManifest(
    val protocolVersion: Int,
    val revision: Long,
    val operationId: String,
    val artifacts: List<SyncArtifact>,
    val containsPersonalPayload: Boolean = false,
) {
    internal fun snapshot() = copy(artifacts = artifacts.map { it.copy(pageMap = it.pageMap.toList()) })
}

enum class SyncPhase {
    MANIFEST_RECEIVED, VERIFYING, VERIFIED, VERIFICATION_FAILED,
    COMMITTING, COMMIT_FAILED, COMMIT_UNKNOWN, QUERYING_COMMIT,
    RECEIPT_PENDING, CONFIRMING, SERVICE_CONFIRMED, CONSENT_REQUIRED,
}
enum class SyncPermission { ALLOWED, FORBIDDEN, DEVICE_REVOKED }
enum class SyncDecision { ACCEPTED, DUPLICATE, CONFLICT, STALE, BLOCKED, INVALID, UNSUPPORTED_PROTOCOL }
enum class SyncAction { VERIFY_RESOURCES, COMMIT_COLLECTION, QUERY_COMMIT, SEND_RECEIPT }

data class SyncToken(
    val session: SyncSession,
    val revision: Long,
    val operationId: String,
    val requestNumber: Long,
    val action: SyncAction,
)
data class SyncCommit(val commitId: String, val artifacts: List<SyncArtifact>)
data class SyncReceipt(val session: SyncSession, val revision: Long, val operationId: String, val commit: SyncCommit)
data class SyncCommand(val token: SyncToken, val manifest: SyncManifest, val receipt: SyncReceipt?)
sealed interface SyncCommitResult {
    data class Committed(val evidence: SyncCommit) : SyncCommitResult
    data object NotCommitted : SyncCommitResult
    data object Unknown : SyncCommitResult
}

/** Independent injected checks; neither commit nor receipt proves a PDF can be opened. */
data class SyncReadiness(val programId: String, val frozenRevision: String, val evidenceId: String)
data class SyncView(
    val session: SyncSession,
    val manifest: SyncManifest,
    val phase: SyncPhase,
    val permission: SyncPermission,
    val commit: SyncCommit?,
    val locallyReadable: Boolean,
    val readiness: SyncReadiness?,
)
