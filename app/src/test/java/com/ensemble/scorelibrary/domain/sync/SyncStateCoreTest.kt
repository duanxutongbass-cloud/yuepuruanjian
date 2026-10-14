package com.ensemble.scorelibrary.domain.sync

import org.junit.Assert.*
import org.junit.Test

class SyncStateCoreTest {
    private val a = SyncIdentity("account-a", "device-1")
    private fun artifact(id: String) = SyncArtifact(id, "source-$id", "hash-$id", 100,
        listOf(SyncPage(2, "identity"), SyncPage(0, "identity")))
    private fun manifest(revision: Long = 1, id: String = "op-$revision") =
        SyncManifest(1, revision, id, listOf(artifact("new-1"), artifact("new-2")))
    private class Harness(val core: SyncStateCore = SyncStateCore()) {
        val session = core.switchSession(SyncIdentity("account-a", "device-1"), "session")
        val store = FakeStore()
        val transport = FakeTransport()
        fun receive(m: SyncManifest) = core.receive(session, m)
        fun verify(): SyncCommand = core.next()!!.also { core.verified(it.token, it.manifest.artifacts) }
        fun commit(): SyncCommand = core.next()!!.also { core.committed(it.token, store.commit(it)) }
        fun confirm(): SyncCommand = core.next()!!.also { core.confirmed(it.token, transport.send(it)) }
    }

    /** Synthetic collection replacement only: no Room, disk, PDF bytes or crash durability. */
    private class FakeStore {
        private val old = listOf(SyncArtifact("old", "old-source", "old-hash", 10, listOf(SyncPage(0, "identity"))))
        private val collections = mutableMapOf<SyncIdentity, List<SyncArtifact>>()
        val collection get() = collection(SyncIdentity("account-a", "device-1"))
        fun collection(identity: SyncIdentity) = collections[identity] ?: old
        private val journal = mutableMapOf<Pair<SyncIdentity, String>, Pair<SyncManifest, SyncCommit>>()
        var submissions = 0
        var replacements = 0
        var failBeforeSwap = false
        var loseReplyAfterSwap = false
        fun commit(c: SyncCommand): SyncCommitResult {
            submissions++
            val key = c.token.session.identity to c.token.operationId
            journal[key]?.let {
                require(it.first == c.manifest) { "operation conflict" }
                return SyncCommitResult.Committed(it.second)
            }
            if (failBeforeSwap) return SyncCommitResult.NotCommitted
            val next = c.manifest.artifacts.toList()
            val evidence = SyncCommit("commit-${c.token.operationId}", next)
            collections[c.token.session.identity] = next
            journal[key] = c.manifest to evidence
            replacements++
            return if (loseReplyAfterSwap) SyncCommitResult.Unknown else SyncCommitResult.Committed(evidence)
        }
        fun query(c: SyncCommand): SyncCommitResult = journal[c.token.session.identity to c.token.operationId]
            ?.let { SyncCommitResult.Committed(it.second) } ?: SyncCommitResult.NotCommitted
    }
    private class FakeTransport {
        val receipts = mutableSetOf<SyncReceipt>()
        var calls = 0
        var loseReply = false
        fun send(c: SyncCommand): Boolean {
            calls++
            receipts.add(requireNotNull(c.receipt))
            return !loseReply
        }
    }

    @Test fun successRequiresIndependentReadabilityAndReadinessEvidence() {
        val h = Harness()
        assertEquals(SyncDecision.ACCEPTED, h.receive(manifest()))
        assertEquals(SyncPhase.MANIFEST_RECEIVED, h.core.view()!!.phase)
        assertFalse(h.core.readinessChecked(h.session, 1, SyncReadiness("program", "frozen", "check")))
        assertNull(h.verify().receipt)
        assertEquals(SyncPhase.VERIFIED, h.core.view()!!.phase)
        assertNull(h.commit().receipt)
        assertEquals(SyncPhase.RECEIPT_PENDING, h.core.view()!!.phase)
        assertFalse(h.core.view()!!.locallyReadable)
        h.confirm()
        assertEquals(SyncPhase.SERVICE_CONFIRMED, h.core.view()!!.phase)
        assertNull(h.core.next())
        assertTrue(h.core.readabilityChecked(h.session, 1, "commit-op-1", true))
        assertNull(h.core.view()!!.readiness)
        assertTrue(h.core.readinessChecked(h.session, 1, SyncReadiness("program", "frozen", "check")))
        assertNotNull(h.core.view()!!.readiness)
        h.core.readabilityChecked(h.session, 1, "commit-op-1", false)
        assertNull(h.core.view()!!.readiness)
    }

    @Test fun everyPreCommitBreakPreservesEntireOldCollection() {
        for (failure in listOf("received", "download", "hash", "commit")) {
            val h = Harness(); val old = h.store.collection
            h.receive(manifest())
            when (failure) {
                "received" -> Unit
                "download" -> h.core.failed(h.core.next()!!.token)
                "hash" -> h.core.next()!!.let { h.core.verified(it.token, emptyList()) }
                "commit" -> { h.verify(); h.store.failBeforeSwap = true; h.commit() }
            }
            assertEquals(failure, old, h.store.collection)
            assertNull(h.core.view()!!.commit)
            assertTrue(h.transport.receipts.isEmpty())
        }
    }

    @Test fun verificationBindsEveryArtifactFieldAndCompleteCollection() {
        val m = manifest(); val first = m.artifacts.first()
        val bad = listOf(
            listOf(first), m.artifacts.reversed(),
            listOf(first.copy(artifactId = "wrong"), m.artifacts[1]),
            listOf(first.copy(sourceVersionId = "wrong"), m.artifacts[1]),
            listOf(first.copy(hash = "wrong"), m.artifacts[1]),
            listOf(first.copy(size = 1), m.artifacts[1]),
            listOf(first.copy(pageMap = first.pageMap.reversed()), m.artifacts[1]),
            listOf(first.copy(pageMap = listOf(SyncPage(2, "rotate90"))), m.artifacts[1]))
        for (evidence in bad) {
            val h = Harness(); h.receive(m)
            val c = h.core.next()!!; h.core.verified(c.token, evidence)
            assertEquals(SyncPhase.VERIFICATION_FAILED, h.core.view()!!.phase)
            assertEquals(SyncAction.VERIFY_RESOURCES, h.core.next()!!.token.action)
            assertNull(h.core.view()!!.commit)
        }
    }

    @Test fun lostConfirmationRetriesOnlyIdenticalReceipt() {
        val h = Harness(); h.receive(manifest()); h.verify(); h.commit()
        h.transport.loseReply = true
        val first = h.confirm()
        assertEquals(SyncPhase.RECEIPT_PENDING, h.core.view()!!.phase)
        h.transport.loseReply = false
        val second = h.confirm()
        assertEquals(first.receipt, second.receipt)
        assertEquals(1, h.store.submissions)
        assertEquals(1, h.transport.receipts.size)
        assertEquals(2, h.transport.calls)
        assertEquals(SyncDecision.STALE, h.core.confirmed(first.token, true))
    }

    @Test fun unknownCommittedQueriesOriginalOperationThenOnlyConfirms() {
        val h = Harness(); h.receive(manifest()); h.verify()
        h.store.loseReplyAfterSwap = true
        val commit = h.commit()
        assertEquals(manifest().artifacts, h.store.collection)
        assertEquals(SyncPhase.COMMIT_UNKNOWN, h.core.view()!!.phase)
        assertEquals(SyncDecision.BLOCKED, h.receive(manifest(2)))
        val query = h.core.next()!!
        assertEquals(SyncAction.QUERY_COMMIT, query.token.action)
        assertEquals(commit.token.operationId, query.token.operationId)
        h.core.failed(query.token)
        val retryQuery = h.core.next()!!
        assertEquals(SyncAction.QUERY_COMMIT, retryQuery.token.action)
        assertEquals(SyncDecision.STALE, h.core.committed(query.token, h.store.query(query)))
        h.core.committed(retryQuery.token, h.store.query(retryQuery))
        h.confirm()
        assertEquals(1, h.store.submissions)
        assertEquals(1, h.store.replacements)
    }

    @Test fun unknownNotCommittedQueriesBeforeSafeOriginalIdRetry() {
        val h = Harness(); h.receive(manifest()); h.verify()
        val first = h.core.next()!!
        h.core.failed(first.token)
        val query = h.core.next()!!
        h.core.committed(query.token, h.store.query(query))
        val retry = h.commit()
        assertEquals(first.manifest, retry.manifest)
        assertEquals(first.token.operationId, retry.token.operationId)
        assertEquals(1, h.store.replacements)
        assertEquals(SyncDecision.STALE, h.core.committed(first.token, SyncCommitResult.NotCommitted))
    }

    @Test fun duplicateConflictOutOfOrderAndLateRevisionAreRejected() {
        val h = Harness(); val m = manifest(); h.receive(m)
        assertEquals(SyncDecision.DUPLICATE, h.receive(m))
        assertEquals(SyncDecision.CONFLICT, h.receive(m.copy(artifacts = emptyList())))
        val old = h.core.next()!!
        assertEquals(SyncDecision.INVALID, h.core.confirmed(old.token, true))
        assertEquals(SyncDecision.ACCEPTED, h.receive(manifest(2)))
        assertEquals(SyncDecision.STALE, h.core.verified(old.token, old.manifest.artifacts))
        assertEquals(SyncDecision.STALE, h.receive(m))
        assertEquals(SyncDecision.STALE, h.receive(manifest(2, "another")))
        h.verify(); h.commit(); h.confirm()
        assertEquals(SyncDecision.DUPLICATE, h.receive(manifest(2)))
    }

    @Test fun aToBToAUsesStrictlyIncreasingEpochEvenWithReusedSessionId() {
        val h = Harness(); h.receive(manifest()); val old = h.core.next()!!
        val b = h.core.switchSession(a.copy(accountId = "b"), "session")
        assertNull(h.core.view())
        val again = h.core.switchSession(a, "session")
        assertTrue(h.session.sessionEpoch < b.sessionEpoch && b.sessionEpoch < again.sessionEpoch)
        assertEquals(SyncDecision.STALE, h.core.receive(h.session, manifest(2)))
        assertEquals(SyncDecision.ACCEPTED, h.core.receive(again, manifest(2)))
        assertEquals(SyncDecision.STALE, h.core.verified(old.token, old.manifest.artifacts))
        assertEquals(SyncDecision.STALE, h.core.receive(again, manifest()))
        h.core.logout(); assertNull(h.core.view()); assertNull(h.core.next())
    }

    @Test fun devicesAndAccountsDoNotShareOperationsOrReceipts() {
        val h = Harness(); h.receive(manifest()); h.verify(); h.commit()
        val first = h.core.next()!!
        val second = h.core.switchSession(a.copy(deviceId = "device-2"), "session")
        assertEquals(SyncDecision.ACCEPTED, h.core.receive(second, manifest().copy(artifacts = listOf(artifact("device-2-only")))))
        assertEquals(SyncDecision.STALE, h.core.confirmed(first.token, true))
        val v = h.core.next()!!; h.core.verified(v.token, v.manifest.artifacts)
        val c = h.core.next()!!; h.core.committed(c.token, h.store.commit(c))
        val receipt = h.core.next()!!.receipt!!
        assertNotEquals(first.receipt!!.session.identity, receipt.session.identity)
        assertEquals(manifest().artifacts, h.store.collection(a))
        assertEquals(listOf(artifact("device-2-only")), h.store.collection(second.identity))
    }

    @Test fun revocationAtEveryBoundaryBlocksAllNewCommandsAndIsSticky() {
        for (device in listOf(false, true)) for (boundary in 0..5) {
            val h = Harness(); h.receive(manifest())
            if (boundary >= 1) h.verify()
            var pending: SyncCommand? = null
            if (boundary == 2) pending = h.core.next()
            if (boundary >= 3) h.commit()
            if (boundary == 4) pending = h.core.next()
            if (boundary == 5) h.confirm()
            // The store has already committed before revocation; only its callback is delayed.
            val delayedResult = if (boundary == 2) h.store.commit(pending!!) else null
            val collection = h.store.collection
            h.core.revoke(a, device)
            if (boundary == 2) {
                assertFalse(h.core.mayExecute(pending!!.token))
                h.core.committed(pending.token, delayedResult!!)
                assertNotNull(h.core.view()!!.commit)
                assertEquals(manifest().artifacts, h.store.collection)
            } else assertEquals(collection, h.store.collection)
            if (boundary == 4) assertEquals(SyncDecision.BLOCKED, h.core.confirmed(pending!!.token, true))
            assertNull(h.core.next())
            assertFalse(h.core.view()!!.locallyReadable)
            assertEquals(if (device) SyncPermission.DEVICE_REVOKED else SyncPermission.FORBIDDEN, h.core.view()!!.permission)
            val again = h.core.switchSession(a, "new-session")
            assertEquals(SyncDecision.BLOCKED, h.core.receive(again, manifest(9)))
            if (device) {
                val b = h.core.switchSession(a.copy(accountId = "b"), "b")
                assertEquals(SyncDecision.BLOCKED, h.core.receive(b, manifest()))
            }
        }
    }

    @Test fun lateVerificationAfterRevocationCannotEnableCommit() {
        val h = Harness(); h.receive(manifest()); val c = h.core.next()!!
        assertTrue(h.core.mayExecute(c.token))
        h.core.revoke(a)
        assertFalse(h.core.mayExecute(c.token))
        assertEquals(SyncDecision.BLOCKED, h.core.verified(c.token, c.manifest.artifacts))
        assertNull(h.core.next()); assertNull(h.core.view()!!.commit)
    }

    @Test fun unknownProtocolAndPersonalPayloadNeverProduceCommandsOrDeleteOldData() {
        val h = Harness(); val old = h.store.collection
        assertEquals(SyncDecision.UNSUPPORTED_PROTOCOL, h.receive(manifest().copy(protocolVersion = 99)))
        assertNull(h.core.view()); assertNull(h.core.next())
        h.receive(manifest().copy(containsPersonalPayload = true))
        repeat(3) {
            assertEquals(SyncPhase.CONSENT_REQUIRED, h.core.view()!!.phase)
            assertNull(h.core.next())
        }
        assertEquals(old, h.store.collection)
        assertTrue(h.transport.receipts.isEmpty())
    }

    @Test fun malformedCommitEvidenceRequiresQueryAndNeverProducesReceipt() {
        val h = Harness(); h.receive(manifest()); h.verify()
        val c = h.core.next()!!
        assertEquals(SyncDecision.INVALID, h.core.committed(c.token,
            SyncCommitResult.Committed(SyncCommit("wrong", listOf(artifact("other"))))))
        assertNull(h.core.view()!!.commit)
        assertEquals(SyncAction.QUERY_COMMIT, h.core.next()!!.token.action)
    }

    @Test fun emptyCollectionIsExplicitCompleteReplacementAndMalformedManifestIsRejected() {
        val h = Harness()
        assertEquals(SyncDecision.INVALID, h.receive(manifest().copy(artifacts = listOf(artifact("x"), artifact("x")))))
        h.receive(manifest().copy(artifacts = emptyList())); h.verify(); h.commit(); h.confirm()
        assertTrue(h.store.collection.isEmpty())
        assertEquals(1, h.store.replacements)
    }

    @Test fun inputAndOutputMutationCannotRewriteAcceptedPayload() {
        val h = Harness(); val pages = mutableListOf(SyncPage(0, "identity"))
        val artifacts = mutableListOf(artifact("x").copy(pageMap = pages))
        h.receive(manifest().copy(artifacts = artifacts))
        pages.clear(); artifacts.clear()
        assertEquals(1, h.core.view()!!.manifest.artifacts.size)
        assertEquals(1, h.core.view()!!.manifest.artifacts[0].pageCount)
        val c = h.core.next()!!
        (c.manifest.artifacts as MutableList).clear()
        assertEquals(1, h.core.view()!!.manifest.artifacts.size)
    }
    @Test fun definiteFailureRetriesCommitWithoutDownloadingAgain() {
        val h = Harness(); h.receive(manifest()); h.verify()
        h.store.failBeforeSwap = true
        val first = h.commit()
        assertEquals(SyncPhase.COMMIT_FAILED, h.core.view()!!.phase)
        h.store.failBeforeSwap = false
        val second = h.commit()
        assertEquals(first.token.operationId, second.token.operationId)
        assertEquals(SyncAction.COMMIT_COLLECTION, second.token.action)
        assertEquals(1, h.store.replacements)
        assertEquals(2, h.store.submissions)
    }

    @Test fun failedVerificationAttemptCannotSatisfyLaterRetry() {
        val h = Harness(); h.receive(manifest())
        val first = h.core.next()!!
        assertNull(h.core.next())
        h.core.failed(first.token)
        val second = h.core.next()!!
        assertEquals(SyncDecision.STALE, h.core.verified(first.token, first.manifest.artifacts))
        assertEquals(SyncDecision.ACCEPTED, h.core.verified(second.token, second.manifest.artifacts))
        assertEquals(SyncDecision.STALE, h.core.verified(second.token, second.manifest.artifacts))
    }

    @Test fun fakeStoreIsIdempotentAndConflictingPayloadCannotReplaceCollection() {
        val h = Harness(); h.receive(manifest()); h.verify()
        val c = h.commit(); val collection = h.store.collection
        assertTrue(h.store.commit(c) is SyncCommitResult.Committed)
        try {
            h.store.commit(c.copy(manifest = c.manifest.copy(artifacts = emptyList())))
            fail("same operation with different payload must conflict")
        } catch (_: IllegalArgumentException) { }
        assertEquals(collection, h.store.collection)
        assertEquals(1, h.store.replacements)
    }

    @Test fun unresolvedCommitAcrossSessionSwitchIsBlockedPendingFutureRecoveryAdapter() {
        val h = Harness(); h.receive(manifest()); h.verify()
        val c = h.core.next()!!
        h.core.failed(c.token)
        val again = h.core.switchSession(a, "again")
        assertNull(h.core.view())
        assertEquals(SyncDecision.STALE, h.core.committed(c.token, SyncCommitResult.NotCommitted))
        assertEquals(SyncDecision.BLOCKED, h.core.receive(again, manifest(2)))
        assertNull(h.core.next())
    }

    @Test fun pendingReceiptCannotBeOverwrittenByLaterManifest() {
        val h = Harness(); h.receive(manifest()); h.verify(); h.commit()
        assertEquals(SyncDecision.BLOCKED, h.receive(manifest(2)))
        h.confirm()
        assertEquals(SyncDecision.ACCEPTED, h.receive(manifest(2)))
        assertFalse(h.core.view()!!.locallyReadable)
        assertNull(h.core.view()!!.readiness)
    }

}
