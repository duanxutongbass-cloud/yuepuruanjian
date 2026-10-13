package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ComplexPdfAcceptanceTest {
    private fun descriptors() = File("/proc/self/fd").list()?.size ?: error("FD counter unavailable")
    private fun fixture(file: File) {
        val pixels = IntArray(1024 * 1536) { i ->
            val x = i % 1024; val y = i / 1024
            val gray = if (y % 128 in 24..27 || x % 128 in 40..44) 20 else 220 + ((x * 13 + y * 7) % 36)
            Color.rgb(gray, gray, gray)
        }
        val raster = Bitmap.createBitmap(pixels, 1024, 1536, Bitmap.Config.ARGB_8888)
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = .6f; textSize = 18f }
            for (number in 1..24) {
                val height = if (number % 3 == 0) 1400 else 900
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, height, number).create())
                page.canvas.drawColor(Color.WHITE)
                if (number % 2 == 0) page.canvas.drawBitmap(raster, null, Rect(20, 60, 580, height - 20), null)
                else {
                    for (staff in 0..35) for (line in 0..4) {
                        val y = 70f + staff * (height - 110f) / 36 + line * 2
                        page.canvas.drawLine(25f, y, 575f, y, paint)
                        for (note in 0..19) page.canvas.drawCircle(35f + note * 27, y + (note % 3), 1.5f, paint)
                    }
                }
                page.canvas.drawText("Synthetic resource page $number", 25f, 30f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close(); raster.recycle() }
    }
    @Test fun mixedRasterVectorPagesRespectRangesAcrossRepeatedLargeViewportRendering() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "mixed-${UUID.randomUUID()}.pdf")
        try {
            fixture(file)
            val segment = PdfFixture.segment(file, PdfFixture.hash(file)).copy(startPage = 2, endPage = 23)
            val engine = NativePdfEngine(context)
            assertEquals(PageSize(600, 900), engine.inspect(listOf(segment)).single())
            val warm = engine.render(PdfPageRef(segment, 1), 1280, 1700, FitMode.PAGE, 0f); warm.bitmap.recycle()
            val initial = descriptors(); val times = mutableListOf<Long>()
            repeat(66) { index ->
                val source = 1 + index % 22
                val started = SystemClock.elapsedRealtimeNanos()
                val frame = engine.render(PdfPageRef(segment, source), 1280, 1700,
                    if (index < 44) FitMode.PAGE else FitMode.WIDTH, (index % 4) / 3f)
                times += SystemClock.elapsedRealtimeNanos() - started
                try {
                    assertEquals(PageSize(600, if ((source + 1) % 3 == 0) 1400 else 900), frame.transform.page)
                    assertEquals(1280L * 1700 * 4, frame.bitmap.allocationByteCount.toLong())
                    if (index < 44) {
                        var dark = 0
                        for (y in 0 until 1700 step 8) for (x in 0 until 1280 step 8)
                            if (Color.red(frame.bitmap.getPixel(x, y)) < 100) dark++
                        assertTrue("Mixed PDF source page ${source + 1} must not be blank", dark > 20)
                        val screen = frame.transform.normalizedToScreen(.4f, .3f)
                        val normalized = requireNotNull(frame.transform.screenToNormalized(screen))
                        assertEquals(.4f, normalized.x, .0001f); assertEquals(.3f, normalized.y, .0001f)
                    }
                } finally { frame.bitmap.recycle() }
            }
            for (source in listOf(0, 23)) {
                try { engine.render(PdfPageRef(segment, source), 1280, 1700, FitMode.PAGE, 0f); fail("Outside binding must reject") }
                catch (failure: PdfFailure) { assertEquals(ReaderError.INVALID_PAGE_RANGE, failure.reason) }
            }
            val final = descriptors(); assertTrue("FD growth: $initial -> $final", final <= initial + 8)
            val sorted = times.sorted()
            val report = JSONObject().put("scope", "Synthetic mixed raster/vector PDF, engine-only emulator test; not scanned user scores or long-duration UI acceptance")
                .put("pdfBytes", file.length()).put("documentPages", 24).put("authorizedRange", "2-23")
                .put("renders", times.size).put("viewport", "1280x1700")
                .put("medianMs", sorted[sorted.size / 2] / 1_000_000.0)
                .put("p95Ms", sorted[(sorted.size * 95 / 100) - 1] / 1_000_000.0)
                .put("maxMs", sorted.last() / 1_000_000.0).put("initialFd", initial).put("finalFd", final)
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase9-mixed-pdf.json")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(report.toString(2).toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { file.delete() }
    }
}
