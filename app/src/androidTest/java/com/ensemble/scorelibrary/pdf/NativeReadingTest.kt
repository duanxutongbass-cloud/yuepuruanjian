package com.ensemble.scorelibrary.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeReadingTest {
    @Test fun localVerifiedCopyRendersPagesAndRetiresOnSourceChangeAndSessionEnd() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.filesDir,"native-reading-${UUID.randomUUID()}.pdf")
        val raw=InstrumentationRegistry.getInstrumentation().context.assets.open("score.pdf").use {it.readBytes()};file.writeBytes(raw)
        val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
        val segment=ResolvedScoreSegment("b",null,"pdf","v",file.path,hash,2,3,CacheStatus.CACHED)
        val engine=NativePdfEngine(context)
        try {
            engine.beginReadingSession()
            assertEquals(PageSize(600,900),engine.inspectForReading(listOf(segment)).single())
            for(index in 1..2) {
                val frame=engine.render(PdfPageRef(segment,index),600,900,FitMode.PAGE,0f)
                assertEquals(600,frame.bitmap.width);assertEquals(900,frame.bitmap.height)
                assertEquals(if(index==1)900 else 1200,frame.transform.page.height)
                frame.bitmap.recycle()
            }
            assertEquals("Page turns must reuse the verified document",1,engine.verifiedReadingCopies)
            file.writeText("corrupted source")
            try { engine.render(PdfPageRef(segment,1),600,900,FitMode.PAGE,0f);fail("Changed source must be checked") }
            catch(error:PdfFailure){assertEquals(ReaderError.CORRUPTED,error.reason)}
            file.writeBytes(raw);engine.endReadingSession();engine.beginReadingSession()
            engine.inspectForReading(listOf(segment));assertEquals(2,engine.verifiedReadingCopies)
            try { engine.inspectForReading(listOf(segment.copy(endPage=999)));fail("Range must be checked before display") }
            catch(error:PdfFailure){assertEquals(ReaderError.INVALID_PAGE_RANGE,error.reason)}
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("pdf-reader-") })
        } finally { engine.endReadingSession();file.delete() }
    }
}
