package com.ensemble.scorelibrary.pdf

import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import org.junit.Assert.*
import org.junit.Test

class ReaderGeometryTest {
    private fun segment(start: Int, end: Int, id: String = "b") = ResolvedScoreSegment(id, null, "pdf", "v", "file", "hash", start, end, CacheStatus.CACHED)
    @Test fun authorizedRangesConcatenateWithoutExposingGaps() {
        val sequence = PageSequence(listOf(segment(3, 5), segment(9, 10, "second")))
        assertEquals(listOf(2, 3, 4, 8, 9), (0 until sequence.count).map { sequence.page(it).pageIndex })
        assertEquals(0, sequence.move(0, ReaderAction.PREVIOUS_PAGE))
        assertEquals(4, sequence.move(4, ReaderAction.NEXT_PAGE))
        assertEquals(1, sequence.move(0, ReaderAction.NEXT_PAGE))
        assertThrows(IllegalArgumentException::class.java) { sequence.page(5) }
        assertThrows(IllegalArgumentException::class.java) { PageSequence(listOf(segment(0, 2))) }
        assertThrows(IllegalArgumentException::class.java) { PageSequence(emptyList()) }
    }
    @Test fun fitPageCentersAndCoordinateRoundTripSurvivesWindowSizes() {
        listOf(800 to 1100, 2160 to 3600, 1200 to 800).forEach { (w, h) ->
            val t = PdfPageTransform.fit(PageSize(600, 900), w, h, FitMode.PAGE)
            val point = t.normalizedToScreen(0.25f, 0.75f)
            val restored = t.screenToNormalized(point)!!
            assertEquals(0.25f, restored.x, 0.00001f)
            assertEquals(0.75f, restored.y, 0.00001f)
            assertTrue(t.offsetX >= 0 && t.offsetY >= 0)
        }
    }
    @Test fun widthFitPanUsesPageCoordinatesAndRejectsLetterboxTouches() {
        val top = PdfPageTransform.fit(PageSize(600, 900), 1200, 800, FitMode.WIDTH)
        val bottom = PdfPageTransform.fit(PageSize(600, 900), 1200, 800, FitMode.WIDTH, 1f)
        assertEquals(2f, top.scale, 0f)
        assertEquals(-1000f, bottom.offsetY, 0f)
        val point = bottom.normalizedToScreen(0.5f, 0.8f)
        assertEquals(0.8f, bottom.screenToNormalized(point)!!.y, 0.00001f)
        val fit = PdfPageTransform.fit(PageSize(600, 900), 1200, 800, FitMode.PAGE)
        assertNull(fit.screenToPage(PagePoint(0f, 0f)))
    }
}
