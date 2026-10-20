package com.ensemble.scorelibrary.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TeamPublishQueueJournalTest {
    private fun id() = UUID.randomUUID().toString()
    private fun fixture(count: Int, check: (File, TeamPublishJournal, String, List<TeamPublishJob>) -> Unit) {
        val root = Files.createTempDirectory("queue-journal-").toFile()
        try {
            val journal = TeamPublishJournal(root)
            val key = id()
            val jobs = (1..count).map { n ->
                val file = File(journal.directory(key), "${id()}.pdf").apply { writeText("synthetic-$n") }
                TeamPublishJob(key, file, "谱$n.pdf", TeamLibrarySync.hash(file), file.length(), 1,
                    TeamPublishDetails("曲$n", "01 Violin", "0101${n.toString().padStart(3, '0')}", "01 Category", setOf(id())))
            }
            check(root, journal, key, jobs)
        } finally {
            check(root.canonicalFile.parentFile == File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
            root.deleteRecursively()
        }
    }
    @Test fun stores101WithoutFiftyLimitAndRestoresExactly() = fixture(101) { root, journal, key, jobs ->
        journal.saveBatch(jobs)
        assertEquals(jobs, TeamPublishJournal(root).readBatch(key))
        val index = JSONObject(File(journal.directory(key), "request.json").readText())
        assertEquals(3, index.getInt("chunks")); assertEquals(101, index.getInt("count"))
        assertTrue(File(journal.directory(key), index.getString("generation")).listFiles()!!.all { it.length() <= 512 * 1024 })
        assertTrue(File(journal.directory(key), "request.json").length() < 512)
    }
    @Test fun totalJournalCanExceedOld512KiBLimit() = fixture(151) { _, journal, key, jobs ->
        val recipients = (1..100).map { id() }.toSet()
        val large = jobs.map { it.copy(details = it.details.copy(recipients = recipients)) }
        journal.saveBatch(large)
        val index = JSONObject(File(journal.directory(key), "request.json").readText())
        val generation = File(journal.directory(key), index.getString("generation"))
        assertTrue(generation.listFiles()!!.sumOf { it.length() } > 512 * 1024)
        assertEquals(large, journal.readBatch(key))
    }
    @Test fun readsLegacySingleFileJournalThenMigratesOnSave() = fixture(2) { _, journal, key, jobs ->
        journal.saveBatch(jobs)
        val target = File(journal.directory(key), "request.json")
        val index = JSONObject(target.readText())
        val legacy = File(File(journal.directory(key), index.getString("generation")), "0.json").readText()
        target.writeText(legacy)
        assertEquals(jobs, journal.readBatch(key))
        journal.saveBatch(jobs)
        assertEquals(2, JSONObject(target.readText()).getInt("version"))
        assertEquals(jobs, journal.readBatch(key))
    }
    @Test fun invalidLaterChunkPreservesPreviousCommittedGeneration() = fixture(51) { _, journal, key, jobs ->
        journal.saveBatch(jobs.take(1))
        val target = File(journal.directory(key), "request.json")
        val before = target.readBytes()
        val bad = jobs.dropLast(1) + jobs.last().copy(file = File("outside.pdf"))
        assertTrue(runCatching { journal.saveBatch(bad) }.isFailure)
        assertArrayEquals(before, target.readBytes())
        assertEquals(jobs.take(1), journal.readBatch(key))
        assertTrue(jobs.all { it.file.exists() })
    }
    @Test fun persistsCompletedAndCancelledPartsWithoutDeletingOriginalCaches() = fixture(51) { _, journal, key, jobs ->
        val state = jobs.mapIndexed { n, job -> when(n) { 0 -> job.copy(completed = true); 50 -> job.copy(cancelled = true); else -> job } }
        journal.saveBatch(state)
        assertEquals(state, journal.readBatch(key))
        assertTrue(journal.readBatch(key).first().finished)
        assertTrue(journal.readBatch(key).last().finished)
        assertFalse(journal.readBatch(key)[1].finished)
        assertTrue(jobs.all { it.file.exists() })
    }
    @Test fun missingChunkFailsClosedAndKeepsOtherFiles() = fixture(51) { _, journal, key, jobs ->
        journal.saveBatch(jobs)
        val target = File(journal.directory(key), "request.json")
        val before = target.readBytes()
        val generation = File(journal.directory(key), JSONObject(target.readText()).getString("generation"))
        check(File(generation, "1.json").delete())
        assertTrue(runCatching { journal.readBatch(key) }.isFailure)
        assertArrayEquals(before, target.readBytes())
        assertTrue(jobs.all { it.file.exists() })
    }
    @Test fun refusesEscapedGenerationAndDuplicateUploadIds() = fixture(2) { _, journal, key, jobs ->
        journal.saveBatch(jobs)
        assertTrue(runCatching { journal.saveBatch(listOf(jobs[0], jobs[1].copy(uploadRequest = jobs[0].uploadRequest))) }.isFailure)
        assertEquals(jobs, journal.readBatch(key))
        val target = File(journal.directory(key), "request.json")
        target.writeText(JSONObject(target.readText()).put("generation", "../outside").toString())
        assertTrue(runCatching { journal.readBatch(key) }.isFailure)
        assertTrue(jobs.all { it.file.exists() })
    }
    @Test fun partialForgetCannotEraseRemainingPendingQueue() = fixture(51) { _, journal, key, jobs ->
        journal.saveBatch(jobs)
        assertTrue(runCatching { journal.forgetBatch(listOf(jobs.first().copy(completed = true))) }.isFailure)
        assertEquals(jobs, journal.readBatch(key))
        assertTrue(jobs.all { it.file.exists() })
        journal.forgetBatch(jobs.map { it.copy(cancelled = true) })
        assertTrue(journal.readBatch(key).isEmpty())
        assertTrue(jobs.none { it.file.exists() })
    }
    @Test fun atomicIndexBackupRestoresPreviousQueue() = fixture(51) { _, journal, key, jobs ->
        journal.saveBatch(jobs)
        val target = File(journal.directory(key), "request.json")
        check(target.renameTo(File(target.path + ".bak")))
        assertEquals(jobs, journal.readBatch(key))
    }
    @Test fun repeatedCommitReplacesIndexAndRetainsLatestProgress() = fixture(51) { _, journal, key, jobs ->
        journal.saveBatch(jobs)
        val old = JSONObject(File(journal.directory(key), "request.json").readText()).getString("generation")
        val updated = jobs.map { it.copy(completed = true) }
        journal.saveBatch(updated)
        assertEquals(updated, journal.readBatch(key))
        assertFalse(File(journal.directory(key), old).exists())
        assertTrue(jobs.all { it.file.exists() })
    }
}
