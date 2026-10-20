package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Color
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfResourceAcceptanceTest {
    private fun descriptors() = File("/proc/self/fd").list()?.size ?: error("FD counters unavailable")
    @Test fun repeatedNativeRenderingReleasesDescriptorsAndKeepsViewportAllocationBounded() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "resource-${UUID.randomUUID()}.pdf")
        try {
            val segment = PdfFixture.segment(file, PdfFixture.write(file))
            val engine = NativePdfEngine(context)
            val warm = engine.render(PdfPageRef(segment, 1), 800, 1100, FitMode.PAGE, 0f)
            warm.bitmap.recycle()
            val initialFd = descriptors()
            val times = mutableListOf<Long>()
            val samples = JSONArray()
            var maxFd = initialFd
            repeat(100) { index ->
                val width = if (index < 80) 800 else 1280
                val height = if (index < 80) 1100 else 1700
                val source = 1 + index % 2
                val started = SystemClock.elapsedRealtimeNanos()
                val frame = engine.render(PdfPageRef(segment, source), width, height,
                    if (index % 2 == 0) FitMode.PAGE else FitMode.WIDTH, (index % 5) / 4f)
                times += SystemClock.elapsedRealtimeNanos() - started
                try {
                    assertEquals(width, frame.bitmap.width); assertEquals(height, frame.bitmap.height)
                    assertEquals(width.toLong() * height * 4, frame.bitmap.allocationByteCount.toLong())
                    assertEquals(frame.transform.viewportWidth, width)
                    if (index % 2 == 0) {
                        var dark = 0
                        for (y in 0 until height step 8) for (x in 0 until width step 8)
                            if (Color.red(frame.bitmap.getPixel(x, y)) < 100) dark++
                        assertTrue("Real PDF must remain nonblank on repeated rendering", dark > 10)
                    }
                } finally { frame.bitmap.recycle() }
                val fd = descriptors(); maxFd = maxOf(maxFd, fd)
                if (index % 10 == 9) {
                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                    samples.put(JSONObject().put("iteration", index + 1).put("pssKb", memory.totalPss)
                        .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("openDescriptors", fd))
                }
            }
            val finalFd = descriptors()
            assertTrue("PDF descriptors must not grow with page count: $initialFd -> $finalFd", finalFd <= initialFd + 8)
            val sorted = times.sorted()
            val report = JSONObject().put("scope", "100 engine renders of small four-page real PDF; not UI or physical-device acceptance")
                .put("deviceModel", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("iterations", times.size).put("pdfBytes", file.length())
                .put("viewportSizes", "800x1100 (80), 1280x1700 (20)")
                .put("medianRenderMs", sorted[sorted.size / 2] / 1_000_000.0)
                .put("p95RenderMs", sorted[(sorted.size * 95 / 100) - 1] / 1_000_000.0)
                .put("maxRenderMs", sorted.last() / 1_000_000.0)
                .put("initialDescriptors", initialFd).put("finalDescriptors", finalFd).put("maxDescriptors", maxFd)
                .put("memorySamples", samples)
                .put("thresholdPolicy", "FD growth <=8; exact viewport allocation; nonblank fit-page. Timing/PSS are observations, not acceptance thresholds.")
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw(
                "dd of=/data/local/tmp/phase9-render-metrics.json")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(report.toString(2).toByteArray(Charsets.UTF_8)) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { file.delete() }
    }
}
