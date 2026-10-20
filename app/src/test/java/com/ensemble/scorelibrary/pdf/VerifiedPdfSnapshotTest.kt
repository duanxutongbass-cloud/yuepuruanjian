package com.ensemble.scorelibrary.pdf

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class VerifiedPdfSnapshotTest {
    private val bytes="%PDF synthetic verification fixture".toByteArray()
    private fun hash(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    @Test fun verifiedCopyIsIndependentAndOriginalIsUntouched()=runBlocking<Unit> {
        val directory=Files.createTempDirectory("verified-pdf-").toFile();val source=directory.resolve("original.pdf")
        try {
            source.writeBytes(bytes);val copy=verifiedPdfSnapshot(source,directory,hash(bytes))
            assertArrayEquals(bytes,source.readBytes());assertArrayEquals(bytes,copy.readBytes())
            source.writeText("replacement source")
            assertArrayEquals("A changed original cannot inject bytes into the verified copy",bytes,copy.readBytes())
            copy.delete()
        } finally {directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }
    @Test fun wrongHashAndMissingSourceLeaveNoPartialCopy()=runBlocking {
        val directory=Files.createTempDirectory("verified-pdf-").toFile();val source=directory.resolve("original.pdf")
        try {
            source.writeBytes(bytes)
            try {verifiedPdfSnapshot(source,directory,"0".repeat(64));fail("Tampered source accepted")}
            catch(error:PdfFailure){assertEquals(ReaderError.CORRUPTED,error.reason)}
            assertArrayEquals(bytes,source.readBytes())
            try {verifiedPdfSnapshot(directory.resolve("missing.pdf"),directory,hash(bytes));fail("Missing source accepted")}
            catch(_:java.io.FileNotFoundException){}
            assertEquals(listOf("original.pdf"),directory.listFiles().orEmpty().map {it.name})
        } finally {directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }
    @Test fun cancelledVerificationCleansItsCopyAndPreservesSource()=runBlocking {
        val directory=Files.createTempDirectory("verified-pdf-").toFile();val source=directory.resolve("original.pdf")
        try {
            source.outputStream().use {output -> val block=ByteArray(1024*1024);repeat(16){output.write(block)}}
            val originalHash=hash(source.readBytes())
            val copying=launch(Dispatchers.IO){verifiedPdfSnapshot(source,directory,originalHash)}
            while(copying.isActive && directory.listFiles().orEmpty().none {it.name.startsWith("pdf-reader-")})yield()
            assertTrue("Cancel while the private copy is being verified",copying.isActive)
            copying.cancelAndJoin()
            assertEquals(originalHash,hash(source.readBytes()))
            assertEquals(listOf("original.pdf"),directory.listFiles().orEmpty().map {it.name})
        } finally {directory.listFiles().orEmpty().forEach {it.delete()};directory.delete()}
    }
}
