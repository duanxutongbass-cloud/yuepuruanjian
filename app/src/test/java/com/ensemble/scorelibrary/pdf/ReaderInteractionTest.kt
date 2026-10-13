package com.ensemble.scorelibrary.pdf

import org.junit.Assert.*
import org.junit.Test

class ReaderInteractionTest {
    @Test fun swipesRequireHorizontalIntentAndThreshold() {
        assertEquals(ReaderAction.NEXT_PAGE, readerSwipe(-150f, 20f, 64f))
        assertEquals(ReaderAction.PREVIOUS_PAGE, readerSwipe(150f, -20f, 64f))
        assertNull(readerSwipe(30f, 0f, 64f))
        assertNull(readerSwipe(120f, 100f, 64f))
        assertNull(readerSwipe(0f, -200f, 64f))
    }

    @Test fun zoomCoordinatesRoundTripAcrossWindowSizesAndPanBounds() {
        for ((width, height) in listOf(800 to 1200, 1280 to 1800)) for (zoom in listOf(1f, 2f, 4f)) {
            val t = PdfPageTransform.view(PageSize(600, 900), width, height, FitMode.PAGE, ReaderView(zoom, .5f, .5f))
            val center = t.normalizedToScreen(.5f, .5f)
            val back = t.screenToNormalized(center)!!
            assertEquals(.5f, back.x, .00001f); assertEquals(.5f, back.y, .00001f)
            assertEquals(width, t.viewportWidth); assertEquals(height, t.viewportHeight)
        }
        val t = PdfPageTransform.view(PageSize(600, 900), 800, 1200, FitMode.PAGE, ReaderView(10f, -5f, 5f))
        assertEquals(0f, t.offsetX, .001f)
        assertEquals(1200f - 900 * t.scale, t.offsetY, .001f)
    }
}
