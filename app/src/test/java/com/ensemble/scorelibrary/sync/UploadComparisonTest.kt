package com.ensemble.scorelibrary.sync

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import com.ensemble.scorelibrary.pdf.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi", sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UploadComparisonTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun pdf(): UploadPdfSource {
        val file = File.createTempFile("compare-", ".pdf", ui.activity.cacheDir)
        val script = "from pypdf import PdfWriter; import sys; w=PdfWriter(); [w.add_blank_page(240,320) for _ in range(2)]; w.write(sys.argv[1])"
        val process = ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python", "-c", script, file.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return UploadPdfSource(file, TeamLibrarySync.hash(file), file.length(), 2, "合成对比谱")
    }
    @Test fun sourceOpensReadOnlyAndOriginalIsUnchanged() {
        val source = pdf()
        try {
            val before = source.file.readBytes()
            openVerifiedUploadPdf(source).use { assertEquals(source.size, it.statSize) }
            assertArrayEquals(before, source.file.readBytes())
        } finally { source.file.delete() }
    }
    @Test fun changedHashSizeAndInvalidPageMetadataAreRejected() {
        val source = pdf()
        try {
            assertTrue(runCatching { openVerifiedUploadPdf(source.copy(hash = "0".repeat(64))) }.isFailure)
            assertTrue(runCatching { openVerifiedUploadPdf(source.copy(size = source.size + 1)) }.isFailure)
            assertTrue(runCatching { openVerifiedUploadPdf(source.copy(pages = 0)) }.isFailure)
            assertTrue(runCatching { renderUploadPdf(source, 2, 100, 100) }.isFailure)
        } finally { source.file.delete() }
    }
    @Test fun bitmapBudgetBoundsLargeViewport() {
        val (width, height) = uploadComparisonViewport(5000, 5000)
        assertTrue(width.toLong() * height <= 2_000_000)
        assertEquals(300 to 400, uploadComparisonViewport(300, 400))
    }
    @Test fun cleanupRunsOnceAndDoesNotDeleteIncoming() {
        val source = pdf()
        val old = File.createTempFile("compare-old-", ".pdf", ui.activity.cacheDir).apply { source.file.copyTo(this, overwrite = true) }
        var count = 0
        val files = UploadComparisonFiles(source, source.copy(file = old), {}, {}, { count++; old.delete() })
        try {
            files.close(); files.close()
            assertEquals(1, count); assertFalse(old.exists()); assertTrue(source.file.exists())
        } finally { old.delete(); source.file.delete() }
    }
    @Test fun unauthorizedLoadShowsErrorAndCloseReturnsToUpload() {
        val shown = mutableStateOf(true)
        ui.setContent { MaterialTheme { if (shown.value) UploadComparisonDialog(load = { error("本份尚无阅读授权") }, onDismiss = { shown.value = false }) } }
        ui.onNodeWithTag("upload-compare-error").assertTextContains("阅读授权", substring = true)
        ui.onNodeWithTag("upload-compare-close").performClick()
        ui.onNodeWithTag("upload-compare-error").assertDoesNotExist()
    }
    // Injected viewport frames test Compose layout/lifetime only; native PDF pixels require androidTest.
    private fun layoutAndPages(narrow: Boolean) {
        val source = pdf(); val second = File.createTempFile("compare-layout-", ".pdf", ui.activity.cacheDir).apply { source.file.copyTo(this, overwrite = true) }
        val shown = mutableStateOf(true)
        var checks = 0
        var cleaned = false
        val files = UploadComparisonFiles(source.copy(label = "本次上传 · 天空之城 · 第一小提琴"),
            source.copy(file = second, label = "库内原谱 · 007 天空之城 · 第一小提琴"), {}, { synchronized(this) { checks++ } }, { cleaned = true; second.delete() })
        try {
            ui.setContent { MaterialTheme { if (shown.value) UploadComparisonDialog(load = { files }, onDismiss = { shown.value = false }, render = { _, page, width, height ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(if (page == 0) Color.WHITE else Color.LTGRAY) }
                PdfFrame(bitmap, PdfPageTransform.fit(PageSize(240, 320), width, height, FitMode.PAGE))
            }) } }
            try { ui.waitUntil(10000) { runCatching {
                ui.onNodeWithTag("upload-compare-page-incoming").assertExists()
                ui.onNodeWithTag("upload-compare-page-existing").assertExists()
            }.isSuccess } } catch (failure: Exception) {
                throw AssertionError(ui.onNodeWithTag("upload-compare-root").printToString(), failure)
            }
            val left = ui.onNodeWithTag("upload-compare-page-incoming").fetchSemanticsNode().boundsInRoot
            val right = ui.onNodeWithTag("upload-compare-page-existing").fetchSemanticsNode().boundsInRoot
            if (narrow) assertTrue(right.top >= left.bottom) else assertTrue(right.left >= left.right)
            ui.onNodeWithTag("upload-compare-next-incoming").performClick()
            ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("upload-compare-page-incoming").assertExists() }.isSuccess }
            ui.onNodeWithTag("upload-compare-index-incoming").assertTextEquals("2 / 2")
            ui.onNodeWithTag("upload-compare-index-existing").assertTextEquals("1 / 2")
            lateinit var bitmap: Bitmap
            ui.runOnUiThread {
                val view = checkNotNull(ShadowDialog.getLatestDialog()?.window?.decorView)
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
            }
            val target = File("build/reports/screenshots/upload-compare-${if (narrow) "compact" else "wide"}.png")
            target.parentFile!!.mkdirs(); target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            assertTrue(checks >= 4)
            ui.onNodeWithTag("upload-compare-close").performClick(); ui.waitForIdle()
            assertTrue(cleaned); assertTrue(source.file.exists())
        } finally { ui.runOnIdle { shown.value = false }; second.delete(); source.file.delete() }
    }
    @Test fun wideComparisonPlacesIndependentPagesSideBySide() = layoutAndPages(false)
    @Test @Config(qualifiers = "w360dp-h800dp-mdpi", sdk = [35])
    fun narrowComparisonStacksPagesWithBothControlsAvailable() = layoutAndPages(true)
    @Test fun closingDuringDelayedRenderingReleasesLateImages() {
        val source = pdf(); val shown = mutableStateOf(true); val entered = java.util.concurrent.atomic.AtomicInteger()
        val release = CountDownLatch(1); val images = CopyOnWriteArrayList<Bitmap>(); var cleaned = false
        val files = UploadComparisonFiles(source, source, {}, {}, { cleaned = true })
        try {
            ui.setContent { MaterialTheme { if (shown.value) UploadComparisonDialog(load = { files },
                onDismiss = { shown.value = false; release.countDown() }, render = { _, _, width, height ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); images += bitmap; entered.incrementAndGet()
                    check(release.await(10, TimeUnit.SECONDS))
                    PdfFrame(bitmap, PdfPageTransform.fit(PageSize(240, 320), width, height, FitMode.PAGE))
                }) } }
            ui.waitUntil(10000) { entered.get() >= 2 }
            ui.onNodeWithTag("upload-compare-close").performClick()
            ui.waitUntil(10000) { cleaned && images.isNotEmpty() && images.all { it.isRecycled } }
            ui.onNodeWithTag("upload-compare-root").assertDoesNotExist()
            assertTrue(source.file.exists())
        } finally { release.countDown(); ui.runOnIdle { shown.value = false }; source.file.delete() }
    }
}
