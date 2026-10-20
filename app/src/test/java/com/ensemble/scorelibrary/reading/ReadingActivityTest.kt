package com.ensemble.scorelibrary.reading

import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReadingActivityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
    private val storage get() = ReadingActivityStore(temporary.root) { key }
    private val dispatcher = StandardTestDispatcher()
    private val worker = CoroutineScope(SupervisorJob() + dispatcher)
    private var member = "m"
    private var space = "local"
    private var valid = true
    private val activity get() = ReadingActivity(storage, { m ->
        val expected = ReadingOwner(space, m)
        ReadingIdentity(expected) { check(valid && member == m && space == expected.space) }
    }, flowOf(Unit), worker)
    private fun score(piece: String = "p", part: String = "part", version: String = "v") =
        ResolvedConcertItem("", piece, "Score", VisiblePart(part, "definition", "Part"),
            listOf(ResolvedScoreSegment("binding", null, "pdf", version, "file", "a".repeat(64), 1, 10, CacheStatus.NOT_CACHED)))
    @After fun cleanup() { worker.cancel() }

    @Test fun acceptedPageSurvivesReaderRetirementAndNewService() = runTest(dispatcher) {
        val service = activity; val session = service.session(member, score())
        service.record(session, 7); service.retire(session); service.record(session, 2)
        service.awaitIdle()
        assertEquals(7, activity.resume(activity.session(member, score())).page)
    }
    @Test fun rapidFramesCoalesceToNewestPositionWithoutChangingOtherParts() = runTest(dispatcher) {
        val service = activity; val a = service.session(member, score()); val b = service.session(member, score(part = "other"))
        repeat(1000) { service.record(a, it % 10) }
        service.record(b, 3); service.record(a, 8); service.awaitIdle()
        val data = service.read(service.identity(member))
        assertEquals(listOf("part", "other"), data.recent.map { it.part })
        assertEquals(listOf(8, 3), data.recent.map { it.page })
    }
    @Test fun actualSuccessfulFrameOrderAcrossScoresIsPreserved() = runTest(dispatcher) {
        val service = activity
        val a = service.session(member, score("a")); val b = service.session(member, score("b"))
        service.record(a, 0); service.record(b, 2); service.record(a, 3); service.awaitIdle()
        assertEquals(listOf("a", "b"), service.read(service.identity(member)).recent.map { it.piece })
    }
    @Test fun pendingPagesCannotWriteIntoAnotherIdentityAfterSwitchOrExpiry() = runTest(dispatcher) {
        val service = activity; val session = service.session(member, score())
        service.record(session, 5); member = "other"; service.awaitIdle()
        assertTrue(storage.read(ReadingOwner("local", "m")).recent.isEmpty())
        assertTrue(service.read(service.identity(member)).recent.isEmpty()); assertNull(service.issue.value)
        val second = service.session(member, score()); service.record(second, 4); valid = false; service.awaitIdle()
        assertTrue(storage.read(ReadingOwner("local", "other")).recent.isEmpty())
    }
    @Test fun newBindingReportsChangedAndCannotRestoreOldPage() = runTest(dispatcher) {
        val service = activity; service.record(service.session(member, score()), 8); service.awaitIdle()
        assertEquals(ReadingResume(changed = true), service.resume(service.session(member, score(version = "new"))))
        assertEquals(ReadingResume(), service.resume(service.session(member, score(part = "other"))))
    }
    @Test fun clearHistoryWaitsForAcceptedWritesAndKeepsFavorites() = runTest(dispatcher) {
        val service = activity; val identity = service.identity(member)
        service.favorite(identity, "p", true); service.record(service.session(member, score()), 4)
        val cleared = service.clearRecent(identity)
        assertTrue(cleared.recent.isEmpty()); assertEquals(setOf("p"), cleared.favorites)
        assertEquals(cleared, storage.read(identity.owner))
    }
    @Test fun failureIsVisibleButCannotOverwriteDamagedRecord() = runTest(dispatcher) {
        val service = activity; val identity = service.identity(member)
        storage.record(identity.owner, RecentReading("p", "part", "a".repeat(64), 1))
        val file = storage.file(identity.owner); file.writeBytes(byteArrayOf(1, 2, 3))
        service.record(service.session(member, score()), 5); service.awaitIdle()
        assertEquals(identity.owner, service.issue.value?.owner); assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
    @Test fun sourceChangedAndInvalidPagesCannotPoisonExistingHistory() = runTest(dispatcher) {
        val service = activity; val session = service.session(member, score())
        service.record(session, 4); service.awaitIdle(); service.record(session, -1); service.record(session, 10000)
        service.awaitIdle(); assertEquals(4, service.resume(session).page)
    }
}
