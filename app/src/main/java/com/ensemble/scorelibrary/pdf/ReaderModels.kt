package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import kotlin.math.min

enum class FitMode { WIDTH, PAGE }
enum class ReaderError { FILE_MISSING, CORRUPTED, ACTIVE_VERSION_MISSING, INVALID_PAGE_RANGE, LOAD_FAILED, PROGRAM_CHANGED, RENDER_TIMEOUT }
enum class ReaderAction { NEXT_PAGE, PREVIOUS_PAGE }
data class ReaderView(val zoom: Float = 1f, val x: Float = .5f, val y: Float = 0f)

fun readerSwipe(dx: Float, dy: Float, threshold: Float): ReaderAction? = when {
    !dx.isFinite() || !dy.isFinite() || !threshold.isFinite() || threshold <= 0 -> null
    kotlin.math.abs(dx) < threshold || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f -> null
    dx < 0 -> ReaderAction.NEXT_PAGE
    else -> ReaderAction.PREVIOUS_PAGE
}
data class PdfPageRef(val segment: ResolvedScoreSegment, val pageIndex: Int)

class PageSequence(val segments: List<ResolvedScoreSegment>) {
    val count: Int
    init {
        require(segments.isNotEmpty())
        require(segments.all { it.startPage >= 1 && it.endPage >= it.startPage })
        val total = segments.sumOf { it.endPage.toLong() - it.startPage + 1 }
        require(total <= Int.MAX_VALUE)
        count = total.toInt()
    }
    fun page(index: Int): PdfPageRef {
        require(index in 0 until count)
        var remaining = index
        for (segment in segments) {
            val size = segment.endPage - segment.startPage + 1
            if (remaining < size) return PdfPageRef(segment, segment.startPage - 1 + remaining)
            remaining -= size
        }
        error("Invalid page sequence")
    }
    fun move(index: Int, action: ReaderAction): Int = when (action) {
        ReaderAction.NEXT_PAGE -> (index + 1).coerceAtMost(count - 1)
        ReaderAction.PREVIOUS_PAGE -> (index - 1).coerceAtLeast(0)
    }
}

data class PageSize(val width: Int, val height: Int)
data class PagePoint(val x: Float, val y: Float)

// Top-left PDF page coordinates, in points (1/72 inch), independent of display density.
data class PdfPageTransform(
    val page: PageSize, val viewportWidth: Int, val viewportHeight: Int,
    val scale: Float, val offsetX: Float, val offsetY: Float
) {
    fun pageToScreen(point: PagePoint) = PagePoint(point.x * scale + offsetX, point.y * scale + offsetY)
    fun screenToPage(point: PagePoint): PagePoint? {
        if (point.x !in 0f..viewportWidth.toFloat() || point.y !in 0f..viewportHeight.toFloat()) return null
        val x = (point.x - offsetX) / scale
        val y = (point.y - offsetY) / scale
        return if (x in 0f..page.width.toFloat() && y in 0f..page.height.toFloat()) PagePoint(x, y) else null
    }
    fun normalizedToScreen(x: Float, y: Float): PagePoint {
        require(x in 0f..1f && y in 0f..1f)
        return pageToScreen(PagePoint(x * page.width, y * page.height))
    }
    fun screenToNormalized(point: PagePoint): PagePoint? = screenToPage(point)?.let { PagePoint(it.x / page.width, it.y / page.height) }
    companion object {
        fun view(page: PageSize, width: Int, height: Int, mode: FitMode, view: ReaderView): PdfPageTransform {
            val base = fit(page, width, height, mode)
            val scale = base.scale * view.zoom.coerceIn(1f, 4f)
            val overflowX = (page.width * scale - width).coerceAtLeast(0f)
            val overflowY = (page.height * scale - height).coerceAtLeast(0f)
            return PdfPageTransform(page, width, height, scale,
                if (overflowX > 0) -overflowX * view.x.coerceIn(0f, 1f) else (width - page.width * scale) / 2,
                if (overflowY > 0) -overflowY * view.y.coerceIn(0f, 1f) else (height - page.height * scale) / 2)
        }
        fun fit(page: PageSize, width: Int, height: Int, mode: FitMode, scrollFraction: Float = 0f): PdfPageTransform {
            require(page.width > 0 && page.height > 0 && width > 0 && height > 0)
            val scale = if (mode == FitMode.WIDTH) width.toFloat() / page.width
                else min(width.toFloat() / page.width, height.toFloat() / page.height)
            val overflow = (page.height * scale - height).coerceAtLeast(0f)
            return PdfPageTransform(page, width, height, scale,
                (width - page.width * scale) / 2,
                if (overflow > 0) -overflow * scrollFraction.coerceIn(0f, 1f) else (height - page.height * scale) / 2)
        }
    }
}
