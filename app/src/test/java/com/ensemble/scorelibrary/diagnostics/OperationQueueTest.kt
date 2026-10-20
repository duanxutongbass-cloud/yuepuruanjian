package com.ensemble.scorelibrary.diagnostics

import com.ensemble.scorelibrary.account.AccountVault
import com.ensemble.scorelibrary.account.AccountRejected
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class OperationQueueTest {
    @Test fun encryptedRestartReplayAndLostAckDoNotLoseRecords() {
        val root=Files.createTempDirectory("opq-").toFile()
        try {
            val file=File(root,"events.bin")
            val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            fun queue()=OperationQueue(AccountVault(file,512*1024) { key },"synthetic-owner/device")
            val original=(1..21).map { OperationRecord(operation=UUID.randomUUID().toString(),event=TestEvent.STAMP_OPEN) }
            original.forEach { queue().add(it) }
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("stamp_open"))
            val pending=queue().batch()
            assertEquals(original.take(20),pending)
            // Server received them but its acknowledgement was lost: reopen and resend exact IDs.
            assertEquals(pending,queue().batch())
            assertTrue(runCatching { queue().acknowledge(listOf(UUID.randomUUID().toString()),pending) }.isFailure)
            assertEquals(21,queue().size())
            queue().acknowledge(pending.map { it.id },pending)
            assertEquals(listOf(original.last()),queue().batch())
            assertTrue(runCatching { OperationQueue(AccountVault(file,512*1024) { key },"other-owner/device").batch() }.isFailure)
            assertEquals(listOf(original.last()),queue().batch())
        } finally { check(root.canonicalFile.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile);root.deleteRecursively() }
    }
    @Test fun queueIsBoundedAndErrorMessagesNeverBecomeDiagnosticContent() {
        val root=Files.createTempDirectory("opb-").toFile()
        try {
            val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val queue=OperationQueue(AccountVault(File(root,"events.bin"),512*1024) {key},"owner/device")
            var last:OperationRecord?=null
            repeat(503) {
                last=OperationRecord(operation=UUID.randomUUID().toString(),event=TestEvent.UPLOAD_PUBLISH,
                    result=TestResult.ERROR,code=TestOperations.error(IllegalStateException("private file title password token")))
                queue.add(last!!)
            }
            assertEquals(500,queue.size())
            val raw=last!!.json().toString()
            listOf("private","password","token").forEach { assertFalse(raw.contains(it)) }
            assertEquals(last,OperationRecord.read(last!!.json()))
            assertEquals(TestCode.PART_VERSION_CONFLICT,TestOperations.error(AccountRejected(409,"part_version_conflict")))
            assertTrue(runCatching { last!!.copy(elapsed=-1) }.isFailure)
        } finally { check(root.canonicalFile.parentFile==File(System.getProperty("java.io.tmpdir")).canonicalFile);root.deleteRecursively() }
    }
}
