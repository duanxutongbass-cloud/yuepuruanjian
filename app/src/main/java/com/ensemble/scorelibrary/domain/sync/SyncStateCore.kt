package com.ensemble.scorelibrary.domain.sync

/**
 * Serialized, in-memory transaction coordinator. Call from one owner thread.
 * Commands are intentions for an injected adapter, not IO or proof of authorization.
 * No persistence/restart, authentication, consent flow or performance policy is implemented.
 */
class SyncStateCore {
    private data class Operation(val identity: SyncIdentity, val id: String)
    private data class Transaction(
        val session: SyncSession,
        val manifest: SyncManifest,
        var phase: SyncPhase,
        var pending: SyncToken? = null,
        var commit: SyncCommit? = null,
        var readable: Boolean = false,
        var readiness: SyncReadiness? = null,
    )

    private var epoch = 0L
    private var request = 0L
    private var session: SyncSession? = null
    private var active: Transaction? = null
    private val operations = mutableMapOf<Operation, Transaction>()
    private val revisions = mutableMapOf<SyncIdentity, Long>()
    private val forbidden = mutableSetOf<SyncIdentity>()
    private val revokedDevices = mutableSetOf<String>()

    fun switchSession(identity: SyncIdentity, sessionId: String): SyncSession {
        require(identity.accountId.isNotBlank() && identity.deviceId.isNotBlank() && sessionId.isNotBlank())
        check(epoch < Long.MAX_VALUE)
        active = null
        return SyncSession(identity, sessionId, ++epoch).also { session = it }
    }

    fun logout() { active = null; session = null }

    private fun permission(identity: SyncIdentity) = when {
        identity.deviceId in revokedDevices -> SyncPermission.DEVICE_REVOKED
        identity in forbidden -> SyncPermission.FORBIDDEN
        else -> SyncPermission.ALLOWED
    }

    /** Trusted external revocation event. Sticky across switches; cursors cannot regrant. */
    fun revoke(identity: SyncIdentity, deviceRevoked: Boolean = false) {
        if (deviceRevoked) revokedDevices.add(identity.deviceId) else forbidden.add(identity)
        active?.takeIf { permission(it.session.identity) != SyncPermission.ALLOWED }?.let {
            it.readable = false
            it.readiness = null
        }
    }

    fun receive(expectedSession: SyncSession, incoming: SyncManifest): SyncDecision {
        if (expectedSession != session) return SyncDecision.STALE
        if (permission(expectedSession.identity) != SyncPermission.ALLOWED) return SyncDecision.BLOCKED
        val manifest = incoming.snapshot()
        val key = Operation(expectedSession.identity, manifest.operationId)
        operations[key]?.let {
            if (it.manifest != manifest) return SyncDecision.CONFLICT
            if (it === active) return SyncDecision.DUPLICATE
            // Only an explicitly re-received, latest pre-commit operation may bind to a new epoch.
            // Never carry verification evidence or pending requests across sessions.
            if (it.session == expectedSession || manifest.revision != revisions[expectedSession.identity] ||
                it.phase !in setOf(SyncPhase.MANIFEST_RECEIVED, SyncPhase.VERIFYING,
                    SyncPhase.VERIFIED, SyncPhase.VERIFICATION_FAILED)) return SyncDecision.STALE
            val rebound = Transaction(expectedSession, manifest, SyncPhase.MANIFEST_RECEIVED)
            operations[key] = rebound
            active = rebound
            return SyncDecision.ACCEPTED
        }
        if (manifest.protocolVersion != 1) return SyncDecision.UNSUPPORTED_PROTOCOL
        if (manifest.operationId.isBlank() || manifest.revision < 0 ||
            manifest.artifacts.map { it.artifactId }.distinct().size != manifest.artifacts.size ||
            manifest.artifacts.any { a -> a.artifactId.isBlank() || a.sourceVersionId.isBlank() ||
                a.hash.isBlank() || a.size <= 0 || a.pageMap.isEmpty() ||
                a.pageMap.any { it.sourcePage < 0 || it.transform.isBlank() } }) return SyncDecision.INVALID
        if (manifest.revision <= (revisions[expectedSession.identity] ?: -1)) return SyncDecision.STALE
        // Preserve unresolved commits and unsent receipts before advancing the collection.
        if (operations.values.any { it.session.identity == expectedSession.identity &&
                it.phase in setOf(SyncPhase.COMMITTING, SyncPhase.COMMIT_UNKNOWN, SyncPhase.QUERYING_COMMIT,
                    SyncPhase.RECEIPT_PENDING, SyncPhase.CONFIRMING) }) {
            return SyncDecision.BLOCKED
        }
        val transaction = Transaction(expectedSession, manifest,
            if (manifest.containsPersonalPayload) SyncPhase.CONSENT_REQUIRED else SyncPhase.MANIFEST_RECEIVED)
        operations[key] = transaction
        revisions[expectedSession.identity] = manifest.revision
        active = transaction
        return SyncDecision.ACCEPTED
    }

    /** Personal payload stays quarantined: this resource-only core has no consent/upload API. */
    fun next(): SyncCommand? {
        val t = active ?: return null
        if (permission(t.session.identity) != SyncPermission.ALLOWED || t.pending != null) return null
        val (action, phase) = when (t.phase) {
            SyncPhase.MANIFEST_RECEIVED, SyncPhase.VERIFICATION_FAILED ->
                SyncAction.VERIFY_RESOURCES to SyncPhase.VERIFYING
            SyncPhase.VERIFIED, SyncPhase.COMMIT_FAILED -> SyncAction.COMMIT_COLLECTION to SyncPhase.COMMITTING
            SyncPhase.COMMIT_UNKNOWN -> SyncAction.QUERY_COMMIT to SyncPhase.QUERYING_COMMIT
            SyncPhase.RECEIPT_PENDING -> SyncAction.SEND_RECEIPT to SyncPhase.CONFIRMING
            else -> return null
        }
        val token = SyncToken(t.session, t.manifest.revision, t.manifest.operationId, ++request, action)
        t.pending = token
        t.phase = phase
        val receipt = if (action == SyncAction.SEND_RECEIPT) SyncReceipt(
            t.session, t.manifest.revision, t.manifest.operationId, checkNotNull(t.commit).snapshot()) else null
        return SyncCommand(token, t.manifest.snapshot(), receipt)
    }

    private fun matching(token: SyncToken): Transaction? = active?.takeIf {
        token.session == session && it.pending == token
    }

    /** Adapter must recheck immediately before executing queued work, on the same owner thread. */
    fun mayExecute(token: SyncToken): Boolean = matching(token)?.let {
        permission(it.session.identity) == SyncPermission.ALLOWED
    } ?: false

    /** A timeout is never evidence that a commit did not happen. Invalidates that attempt. */
    fun failed(token: SyncToken): SyncDecision = when (token.action) {
        SyncAction.VERIFY_RESOURCES -> verified(token, null)
        SyncAction.COMMIT_COLLECTION, SyncAction.QUERY_COMMIT -> committed(token, SyncCommitResult.Unknown)
        SyncAction.SEND_RECEIPT -> confirmed(token, false)
    }

    fun verified(token: SyncToken, evidence: List<SyncArtifact>?): SyncDecision {
        val t = matching(token) ?: return SyncDecision.STALE
        if (token.action != SyncAction.VERIFY_RESOURCES) return SyncDecision.INVALID
        t.pending = null
        if (permission(t.session.identity) != SyncPermission.ALLOWED) return SyncDecision.BLOCKED
        t.phase = if (evidence == t.manifest.artifacts) SyncPhase.VERIFIED else SyncPhase.VERIFICATION_FAILED
        return SyncDecision.ACCEPTED
    }

    fun committed(token: SyncToken, result: SyncCommitResult): SyncDecision {
        val t = matching(token) ?: return SyncDecision.STALE
        if (token.action != SyncAction.COMMIT_COLLECTION && token.action != SyncAction.QUERY_COMMIT) {
            return SyncDecision.INVALID
        }
        t.pending = null
        when (result) {
            is SyncCommitResult.Committed -> {
                if (result.evidence.commitId.isBlank() || result.evidence.artifacts != t.manifest.artifacts) {
                    t.phase = SyncPhase.COMMIT_UNKNOWN
                    return SyncDecision.INVALID
                }
                // Record the objective fact even after revocation; never restore permission.
                t.commit = result.evidence.snapshot()
                t.phase = SyncPhase.RECEIPT_PENDING
            }
            SyncCommitResult.NotCommitted -> t.phase = SyncPhase.COMMIT_FAILED
            SyncCommitResult.Unknown -> t.phase = SyncPhase.COMMIT_UNKNOWN
        }
        return SyncDecision.ACCEPTED
    }

    fun confirmed(token: SyncToken, accepted: Boolean): SyncDecision {
        val t = matching(token) ?: return SyncDecision.STALE
        if (token.action != SyncAction.SEND_RECEIPT) return SyncDecision.INVALID
        t.pending = null
        if (permission(t.session.identity) != SyncPermission.ALLOWED) {
            t.phase = SyncPhase.RECEIPT_PENDING
            return SyncDecision.BLOCKED
        }
        t.phase = if (accepted) SyncPhase.SERVICE_CONFIRMED else SyncPhase.RECEIPT_PENDING
        return SyncDecision.ACCEPTED
    }

    fun readabilityChecked(expectedSession: SyncSession, revision: Long, commitId: String, readable: Boolean): Boolean {
        val t = active ?: return false
        if (expectedSession != session || t.manifest.revision != revision || t.commit?.commitId != commitId ||
            permission(t.session.identity) != SyncPermission.ALLOWED) return false
        t.readable = readable
        t.readiness = null
        return true
    }

    fun readinessChecked(expectedSession: SyncSession, revision: Long, evidence: SyncReadiness?): Boolean {
        val t = active ?: return false
        if (expectedSession != session || t.manifest.revision != revision || !t.readable ||
            permission(t.session.identity) != SyncPermission.ALLOWED) return false
        if (evidence != null && (evidence.programId.isBlank() || evidence.frozenRevision.isBlank() ||
                evidence.evidenceId.isBlank())) return false
        t.readiness = evidence
        return true
    }

    fun view(): SyncView? = active?.let {
        SyncView(it.session, it.manifest.snapshot(), it.phase, permission(it.session.identity),
            it.commit?.snapshot(), it.readable, it.readiness)
    }

    private fun SyncCommit.snapshot() = copy(artifacts = artifacts.map { it.copy(pageMap = it.pageMap.toList()) })
}
