package com.ensemble.scorelibrary.sync

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.PreviewImage
import com.ensemble.scorelibrary.ui.PreviewImageView
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

internal data class UploadPdfSource(val file: File, val hash: String, val size: Long, val pages: Int, val label: String)
internal class UploadComparisonFiles(val incoming: UploadPdfSource, val existing: UploadPdfSource,
    val verifyLocal: () -> Unit, val verifyRemote: suspend () -> Unit, private val cleanup: () -> Unit) : Closeable {
    private var closed = false
    @Synchronized override fun close() { if (!closed) { closed = true; cleanup() } }
}

internal fun stagedComparisonMatch(batch: List<TeamPublishJob>, planned: List<TeamPublishDetails>, index: Int,
    match: ExistingTeamScore): TeamPublishJob? {
    require(planned.size == batch.size && index in batch.indices)
    if (match.artifactId.isNotEmpty()) return null
    return batch.indices.filter { it != index && planned[it].number == match.number && planned[it].title == match.title &&
        planned[it].category == match.category && planned[it].part == match.part }.singleOrNull()?.let(batch::get)
}

internal fun requireUploadComparisonCurrent(scope: TeamLibraryScope, collection: TeamCollection, stamp: Long,
    current: TeamPublishState, sources: List<TeamPublishJob>) {
    scope.requireCurrent(collection)
    check(scope.preparationStamp(collection) == stamp && current.key == collection.key && current.job == null && !current.busy &&
        sources.all { source -> current.batch.any { it.file == source.file && it.hash == source.hash } }) {
        "账号、上传批次或阅读授权已变化，请重新打开对比"
    }
}

/** Hash and render one opened inode; this viewer never edits the original or the staged upload. */
internal fun renderUploadPdf(source: UploadPdfSource, index: Int, width: Int, height: Int): PdfFrame {
    require(source.pages in 1..2000 && index in 0 until source.pages && width > 0 && height > 0)
    require(source.hash.matches(Regex("[a-f0-9]{64}")) && source.size in 1..MAX_TEAM_UPLOAD)
    val (w, h) = uploadComparisonViewport(width, height)
    openVerifiedUploadPdf(source).use { descriptor ->
        return PdfRenderer(descriptor).use { renderer ->
            check(renderer.pageCount == source.pages) { "PDF页数已变化，请重新选择" }
            renderer.openPage(index).use { page ->
                val transform = PdfPageTransform.fit(PageSize(page.width, page.height), w, h, FitMode.PAGE)
                val bitmap = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
                    catch (_: OutOfMemoryError) { throw IllegalStateException("内存不足，请关闭对比后重试") }
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply { setValues(floatArrayOf(transform.scale, 0f, transform.offsetX,
                        0f, transform.scale, transform.offsetY, 0f, 0f, 1f)) }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    PdfFrame(bitmap, transform)
                } catch (_: OutOfMemoryError) { bitmap.recycle(); throw IllegalStateException("内存不足，请关闭对比后重试") }
                catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
    }
}

internal fun uploadComparisonViewport(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val factor = minOf(1.0, sqrt(minOf(2_000_000L, Runtime.getRuntime().maxMemory() / 32).coerceAtLeast(1).toDouble() / (width.toLong() * height)))
    return (width * factor).toInt().coerceAtLeast(1) to (height * factor).toInt().coerceAtLeast(1)
}
internal fun openVerifiedUploadPdf(source: UploadPdfSource): ParcelFileDescriptor {
    require(source.pages in 1..2000 && source.hash.matches(Regex("[a-f0-9]{64}")) && source.size in 1..MAX_TEAM_UPLOAD)
    val descriptor = ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        check(descriptor.statSize == source.size) { "PDF大小已变化，请重新选择" }
        val digest = MessageDigest.getInstance("SHA-256")
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == source.hash) { "PDF校验失败，未显示已变化的文件" }
        android.system.Os.lseek(descriptor.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
        return descriptor
    } catch (error: Throwable) { descriptor.close(); throw error }
}

@Composable
internal fun UploadComparisonDialog(load: suspend () -> UploadComparisonFiles, onDismiss: () -> Unit,
    render: (UploadPdfSource, Int, Int, Int) -> PdfFrame = ::renderUploadPdf) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) onDismiss() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var files by remember { mutableStateOf<UploadComparisonFiles?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var owned: UploadComparisonFiles? = null
        try {
            owned = load()
            owned.verifyLocal()
            files = owned
            while (true) { delay(1000); owned.verifyLocal() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { files = null; error = failure.message ?: "无法核验原谱，请关闭后重试" }
        finally { owned?.close() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("upload-compare-root")) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上传原谱对比", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("upload-compare-close")) { Text("返回上传") }
                }
                Text("独立翻页核对后返回，明确选择新建版本或取消本份。")
                val current = files
                when {
                    error != null -> Text(error!!, Modifier.testTag("upload-compare-error"))
                    current == null -> CircularProgressIndicator(Modifier.testTag("upload-compare-loading"))
                    else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if (maxWidth >= 600.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UploadComparisonPane(current, current.incoming, "incoming", Modifier.weight(1f).fillMaxHeight(), render)
                            UploadComparisonPane(current, current.existing, "existing", Modifier.weight(1f).fillMaxHeight(), render)
                        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            UploadComparisonPane(current, current.incoming, "incoming", Modifier.weight(1f).fillMaxWidth(), render)
                            UploadComparisonPane(current, current.existing, "existing", Modifier.weight(1f).fillMaxWidth(), render)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadComparisonPane(files: UploadComparisonFiles, source: UploadPdfSource, side: String, modifier: Modifier,
    render: (UploadPdfSource, Int, Int, Int) -> PdfFrame) {
    var page by remember(source) { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<PreviewImage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { image?.releaseOwner() } }
    LaunchedEffect(files, source, page, width, height) {
        image?.releaseOwner(); image = null; error = null
        if (width <= 0 || height <= 0) return@LaunchedEffect
        loading = true
        var result: PreviewImage? = null
        try {
            files.verifyRemote()
            withContext(NonCancellable + Dispatchers.IO) { result = PreviewImage(render(source, page, width, height)) }
            ensureActive()
            files.verifyRemote()
            files.verifyLocal()
            image = result; result = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "PDF无法显示，请核对文件" }
        finally { result?.releaseOwner(); loading = false }
    }
    Column(modifier) {
        Text(source.label, maxLines = 2, style = MaterialTheme.typography.titleSmall)
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { width = it.width; height = it.height }, contentAlignment = Alignment.Center) {
            val visible = image?.takeIf { it.canDisplay() }
            when {
                visible != null -> AndroidView(factory = { PreviewImageView(it) }, modifier = Modifier.fillMaxSize().testTag("upload-compare-page-$side"),
                    onReset = { it.clearPreview() }, onRelease = { it.clearPreview() }, update = { it.show(visible) })
                error != null -> Text(error!!, Modifier.testTag("upload-compare-error-$side"))
                loading -> CircularProgressIndicator()
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page-- }, enabled = !loading && page > 0, modifier = Modifier.testTag("upload-compare-prev-$side")) { Text("上一页") }
            Text("${page + 1} / ${source.pages}", Modifier.testTag("upload-compare-index-$side"))
            TextButton(onClick = { page++ }, enabled = !loading && page + 1 < source.pages, modifier = Modifier.testTag("upload-compare-next-$side")) { Text("下一页") }
        }
    }
}
