package com.ensemble.scorelibrary.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ManagedPdf(val file: File, val hash: String, val size: Long, val pages: Int, val uri: String)

class ManagedPdfImporter(private val context: Context) {
    suspend fun <T> publish(uri: Uri, commit: suspend (ManagedPdf) -> T): T = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请通过系统文件选择器选择 PDF" }
        val directory = File(context.filesDir, "scores").apply { check(exists() || mkdirs()) }
        val file = File(directory, "${UUID.randomUUID()}.pdf")
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 256L * 1024 * 1024) { "PDF 超过 256 MB，请拆分后导入" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } } ?: error("无法读取所选文件")
            val pages = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount > 0)
                        repeat(renderer.pageCount) { ensureActive(); renderer.openPage(it).use { page -> require(page.width > 0 && page.height > 0) } }
                        renderer.pageCount
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw IllegalArgumentException("PDF 损坏、加密或无法读取", failure) }
            ensureActive()
            val staged = ManagedPdf(file, digest.digest().joinToString("") { "%02x".format(it) }, size, pages, uri.toString())
            withContext(NonCancellable) { commit(staged).also { committed = true } }
        } finally { if (!committed) file.delete() }
    }
}
