package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Color
import android.os.ParcelFileDescriptor
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
class ExternalPdfAcceptanceTest {
    @Test fun rendersAuthorizedPagesAtBothWindowSizes() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val external = InstrumentationRegistry.getArguments().getString("privatePdf") == "true"
        val pages = if (external) InstrumentationRegistry.getArguments().getString("privatePdfPages")!!.toInt() else 4
        require(pages >= 4)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "acceptance-${UUID.randomUUID()}.pdf")
        try {
            if (external) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "cat /data/local/tmp/phase9-private-score.pdf"
                )).use { input -> file.outputStream().use { input.copyTo(it) } }
            } else PdfFixture.write(file)
            val segment = PdfFixture.segment(file, PdfFixture.hash(file)).copy(startPage = 2, endPage = pages - 1)
            val engine = NativePdfEngine(context)
            engine.inspect(listOf(segment))
            var renders = 0
            for ((width, height) in listOf(800 to 1100, 1280 to 1700)) {
                for (source in 1 until pages - 1) {
                    for (mode in listOf(FitMode.PAGE, FitMode.WIDTH)) {
                        val frame = engine.render(PdfPageRef(segment, source), width, height, mode, 0f)
                        try {
                            assertEquals(width, frame.bitmap.width)
                            assertEquals(height, frame.bitmap.height)
                            var dark = 0
                            for (y in 0 until height step 8) for (x in 0 until width step 8)
                                if (Color.red(frame.bitmap.getPixel(x, y)) < 150) dark++
                            assertTrue("Source page ${source + 1} must contain visible content", dark > 20)
                            if (mode == FitMode.PAGE) {
                                val point = frame.transform.normalizedToScreen(.4f, .3f)
                                val restored = requireNotNull(frame.transform.screenToNormalized(point))
                                assertEquals(.4f, restored.x, .0001f)
                                assertEquals(.3f, restored.y, .0001f)
                            }
                            renders++
                        } finally { frame.bitmap.recycle() }
                    }
                }
            }
            for (source in listOf(0, pages - 1)) {
                try {
                    engine.render(PdfPageRef(segment, source), 800, 1100, FitMode.PAGE, 0f)
                    fail("Page outside binding must be rejected")
                } catch (failure: PdfFailure) { assertEquals(ReaderError.INVALID_PAGE_RANGE, failure.reason) }
            }
            val report = JSONObject().put("externalFixture", external).put("pages", pages)
                .put("bytes", file.length()).put("sha256", PdfFixture.hash(file)).put("renders", renders)
                .put("range", "2-${pages - 1}").put("scope", "Native engine only; not UI or physical-device acceptance")
            val pipes = instrumentation.uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase9-external-pdf.json")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(report.toString(2).toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { file.delete() }
    }
}
