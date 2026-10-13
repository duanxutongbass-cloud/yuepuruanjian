package com.ensemble.scorelibrary.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class PdfFailure(val reason: ReaderError) : IOException()
interface PdfEngine {
    suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize>
    suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame
    suspend fun renderView(page: PdfPageRef, width: Int, height: Int, mode: FitMode, view: ReaderView): PdfFrame =
        render(page, width, height, mode, view.y)
}
data class PdfFrame(val bitmap: Bitmap, val transform: PdfPageTransform)

class NativePdfEngine @Inject constructor(@param:ApplicationContext private val context: Context) : PdfEngine {
    private fun file(segment: ResolvedScoreSegment): File {
        val root = context.filesDir.canonicalFile
        fun candidate(path: String?): File? {
            if (path.isNullOrBlank()) return null
            val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
            return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile && it.canRead() }
        }
        return candidate(segment.managedFilePath)
            ?: (if (segment.cacheStatus == CacheStatus.CACHED) candidate(segment.cachedFilePath) else null)
            ?: throw PdfFailure(ReaderError.FILE_MISSING)
    }
    private suspend fun verify(input: java.io.InputStream, hash: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = stream.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(hash, ignoreCase = true)) throw PdfFailure(ReaderError.CORRUPTED)
    }
    private suspend fun <T> document(segment: ResolvedScoreSegment, block: (PdfRenderer) -> T): T {
        try {
            // Hash and render the same opened inode, even if the managed path is replaced.
            ParcelFileDescriptor.open(file(segment), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                verify(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)), segment.fileHash)
                android.system.Os.lseek(descriptor.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                return PdfRenderer(descriptor).use(block)
            }
        } catch (error: PdfFailure) { throw error }
        catch (error: java.io.FileNotFoundException) { throw PdfFailure(ReaderError.FILE_MISSING) }
        catch (error: SecurityException) { throw PdfFailure(ReaderError.CORRUPTED) }
        catch (error: IOException) { throw PdfFailure(ReaderError.CORRUPTED) }
        catch (error: IllegalArgumentException) { throw PdfFailure(ReaderError.CORRUPTED) }
    }

    override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) throw PdfFailure(ReaderError.INVALID_PAGE_RANGE)
        val dimensions = ArrayList<PageSize>()
        for (segment in segments) {
            ensureActive()
            if (segment.versionId.isBlank()) throw PdfFailure(ReaderError.ACTIVE_VERSION_MISSING)
            document(segment) { renderer ->
                if (segment.startPage < 1 || segment.endPage < segment.startPage || segment.endPage > renderer.pageCount)
                    throw PdfFailure(ReaderError.INVALID_PAGE_RANGE)
                for (pageIndex in segment.startPage - 1 until segment.endPage) {
                    ensureActive()
                    renderer.openPage(pageIndex).use {
                        val size = PageSize(it.width, it.height)
                        if (pageIndex == segment.startPage - 1) dimensions.add(size)
                    }
                }
            }
        }
        dimensions
    }

    override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame {
        return renderView(page, width, height, mode, ReaderView(y = scroll))
    }

    override suspend fun renderView(page: PdfPageRef, width: Int, height: Int, mode: FitMode, view: ReaderView): PdfFrame {
        var completed: PdfFrame? = null
        try {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        if (page.segment.versionId.isBlank()) throw PdfFailure(ReaderError.ACTIVE_VERSION_MISSING)
        if (page.segment.startPage < 1 || page.segment.endPage < page.segment.startPage ||
            page.pageIndex < page.segment.startPage - 1 || page.pageIndex >= page.segment.endPage)
            throw PdfFailure(ReaderError.INVALID_PAGE_RANGE)
        // Only the visible viewport is allocated, never an entire tall off-screen PDF page.
        val pixelBudget = minOf(12_000_000L, Runtime.getRuntime().maxMemory() / 16)
        if (width <= 0 || height <= 0 || width.toLong() * height > pixelBudget) throw PdfFailure(ReaderError.LOAD_FAILED)
        completed = document(page.segment) { renderer ->
            if (page.pageIndex !in 0 until renderer.pageCount) throw PdfFailure(ReaderError.INVALID_PAGE_RANGE)
            renderer.openPage(page.pageIndex).use { pdfPage ->
                val actual = PdfPageTransform.view(PageSize(pdfPage.width, pdfPage.height), width, height, mode, view)
                val bitmap = try { Bitmap.createBitmap(actual.viewportWidth, actual.viewportHeight, Bitmap.Config.ARGB_8888) }
                    catch (_: OutOfMemoryError) { throw PdfFailure(ReaderError.LOAD_FAILED) }
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply { setValues(floatArrayOf(actual.scale, 0f, actual.offsetX, 0f, actual.scale, actual.offsetY, 0f, 0f, 1f)) }
                    pdfPage.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    PdfFrame(bitmap, actual)
                } catch (error: Exception) { bitmap.recycle(); throw error }
                catch (_: OutOfMemoryError) { bitmap.recycle(); throw PdfFailure(ReaderError.LOAD_FAILED) }
            }
        }
        }
        return completed!!
        } catch (error: kotlinx.coroutines.CancellationException) {
            completed?.bitmap?.recycle()
            throw error
        }
    }
}
