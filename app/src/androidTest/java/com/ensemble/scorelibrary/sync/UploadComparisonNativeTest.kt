package com.ensemble.scorelibrary.sync

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires an Android device/emulator with PdfRenderer; do not count desktop Robolectric as this check. */
@RunWith(AndroidJUnit4::class)
class UploadComparisonNativeTest {
    @Test fun twoRealPagesRenderDifferentlyAndOriginalIsPreserved() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("upload-native-", ".pdf", context.cacheDir)
        try {
            val doc = PdfDocument()
            try {
                repeat(2) { i ->
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(240, 320, i + 1).create())
                    page.canvas.drawColor(if (i == 0) Color.WHITE else Color.LTGRAY)
                    doc.finishPage(page)
                }
                file.outputStream().use(doc::writeTo)
            } finally { doc.close() }
            val before = file.readBytes()
            val source = UploadPdfSource(file, TeamLibrarySync.hash(file), file.length(), 2, "Synthetic native PDF")
            val one = renderUploadPdf(source, 0, 300, 400)
            val two = renderUploadPdf(source, 1, 300, 400)
            try {
                assertNotEquals(one.bitmap.getPixel(150, 200), two.bitmap.getPixel(150, 200))
                assertArrayEquals(before, file.readBytes())
            } finally { one.bitmap.recycle(); two.bitmap.recycle() }
            assertTrue(runCatching { renderUploadPdf(source.copy(pages = 3), 0, 100, 100) }.isFailure)
            val big = renderUploadPdf(source, 0, 5000, 5000)
            try { assertTrue(big.bitmap.width.toLong() * big.bitmap.height <= 2_000_000) }
            finally { big.bitmap.recycle() }
        } finally { file.delete() }
    }
}
