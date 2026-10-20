package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class UploadComparisonGuardTest {
    private fun id() = UUID.randomUUID().toString()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = TeamLibraryScope(context)
    private val principal = TeamPrincipal(id(), id(), id(), "Synthetic", "admin", "Admin", "admin", true)
    private val session = TeamSession("https://example.invalid", "synthetic-token", System.currentTimeMillis()/1000 + 3600, principal)
    private val collection by lazy { scope.activate(session) }
    private val file by lazy { File.createTempFile("guard-", ".pdf", context.cacheDir).apply { writeText("private synthetic fixture") } }
    private val details = TeamPublishDetails("合成", "03 中提琴", "0301001", "01 分类", setOf(principal.accountId))
    private fun job() = TeamPublishJob(collection.key, file, "guard.pdf", TeamLibrarySync.hash(file), file.length(), 1, details)
    @After fun close() { scope.deactivate(); collection.database.close(); file.delete() }
    @Test fun actualAccountSwitchRejectsOldBindingAndDoesNotDeleteFile() {
        val job = job(); val state = TeamPublishState(key = collection.key, batch = listOf(job)); val stamp = scope.preparationStamp(collection)
        requireUploadComparisonCurrent(scope, collection, stamp, state, listOf(job))
        val other = scope.activate(session.copy(principal = principal.copy(accountId = id(), memberId = id())))
        try { assertTrue(runCatching { requireUploadComparisonCurrent(scope, collection, stamp, state, listOf(job)) }.isFailure) }
        finally { other.database.close() }
        assertEquals("private synthetic fixture", file.readText())
    }
    @Test fun authorizationGenerationChangesEvenWhenUpdateHasEndedRejectLateFrame() {
        val job = job(); val state = TeamPublishState(key = collection.key, batch = listOf(job)); val stamp = scope.preparationStamp(collection)
        scope.beginAuthorizationChange(collection); scope.endAuthorizationChange(collection)
        assertTrue(runCatching { requireUploadComparisonCurrent(scope, collection, stamp, state, listOf(job)) }.isFailure)
    }
    @Test fun removedChangedOrCommittedUploadCannotPublishComparisonFrame() {
        val job = job(); val state = TeamPublishState(key = collection.key, batch = listOf(job)); val stamp = scope.preparationStamp(collection)
        listOf(state.copy(batch = emptyList()), state.copy(batch = listOf(job.copy(hash = "0".repeat(64)))), state.copy(job = job), state.copy(busy = true)).forEach {
            assertTrue(runCatching { requireUploadComparisonCurrent(scope, collection, stamp, it, listOf(job)) }.isFailure)
        }
    }
    @Test fun stagedDuplicateSelectionUsesExplicitPreviewAndNeverMistakesServerArtifactForCache() {
        val job = job(); val other = job.copy(file = File(context.cacheDir, "other-staged.pdf"))
        val row = details.copy(title = "编辑后标题")
        val match = ExistingTeamScore(row.number, row.title, row.category, row.part, false)
        assertEquals(other, stagedComparisonMatch(listOf(job, other), listOf(details, row), 0, match))
        assertNull(stagedComparisonMatch(listOf(job, other), listOf(details, row), 0, match.copy(artifactId = id())))
        assertNull(stagedComparisonMatch(listOf(job, other), listOf(details, row.copy(title = "别的曲目")), 0, match))
        val state = TeamPublishState(key = collection.key, batch = listOf(job, other)); val stamp = scope.preparationStamp(collection)
        requireUploadComparisonCurrent(scope, collection, stamp, state, listOf(job, other))
        assertTrue(runCatching { requireUploadComparisonCurrent(scope, collection, stamp, state.copy(batch = listOf(job)), listOf(job, other)) }.isFailure)
    }
}
