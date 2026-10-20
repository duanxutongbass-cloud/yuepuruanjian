package com.ensemble.scorelibrary.sync

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TeamStagedDiscardTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun discardDeletesOnlyUnsubmittedCacheAndRejectsOriginalOutsidePrivateAccount() {
        val root = Files.createTempDirectory("discard-test").toFile()
        try {
            val journal = TeamPublishJournal(File(root, "cache"))
            val key = id()
            val original = File(root, "original.pdf").apply { writeText("original PDF preserved") }
            val cache = File(journal.directory(key), "${id()}.pdf").apply { writeBytes(original.readBytes()) }
            val other = File(journal.directory(key), "${id()}.pdf").apply { writeText("other cache") }
            val d = TeamPublishDetails("合成", "03 中提琴", "0301001", "01 分类", setOf(id()))
            val job = TeamPublishJob(key, cache, "original.pdf", "hash", cache.length(), 1, d)
            assertTrue(runCatching { journal.discardStaged(job.copy(file = original)) }.isFailure)
            assertTrue(runCatching { journal.discardStaged(job.copy(completed = true)) }.isFailure)
            journal.discardStaged(job)
            assertFalse(cache.exists()); assertEquals("original PDF preserved", original.readText()); assertEquals("other cache", other.readText())
        } finally { check(root.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile); root.deleteRecursively() }
    }
    @Test fun pendingPublicationCannotUseLocalDiscardAndReceiptRemains() {
        val root = Files.createTempDirectory("discard-pending").toFile()
        try {
            val journal = TeamPublishJournal(root)
            val key = id()
            val cache = File(journal.directory(key), "${id()}.pdf").apply { writeText("private cache") }
            val d = TeamPublishDetails("合成", "03 中提琴", "0301001", "01 分类", setOf(id()))
            val job = TeamPublishJob(key, cache, "new.pdf", TeamLibrarySync.hash(cache), cache.length(), 1, d)
            journal.save(job)
            val receipt = File(journal.directory(key), "request.json").readBytes()
            assertTrue(runCatching { journal.discardStaged(job) }.isFailure)
            assertEquals("private cache", cache.readText())
            assertArrayEquals(receipt, File(journal.directory(key), "request.json").readBytes())
        } finally { check(root.canonicalFile.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile); root.deleteRecursively() }
    }
}
