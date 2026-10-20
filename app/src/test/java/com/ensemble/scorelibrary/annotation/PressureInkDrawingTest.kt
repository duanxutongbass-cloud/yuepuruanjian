package com.ensemble.scorelibrary.annotation

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PressureInkDrawingTest {
    private fun draw(mark:InkMark):Bitmap=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).also { bitmap ->
        CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,Canvas(bitmap.asImageBitmap()),Size(400f,400f)) {
            drawRect(Color.White)
            drawPressureInk(mark,400f,Color(mark.color)) { Offset(it.x*400,it.y*400) }
        }
    }
    @Test fun savedPressureReallyChangesRenderedWidthAndReloadProducesIdenticalPixels() {
        val mark=InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.5f),InkPoint(.5f,.5f),InkPoint(.8f,.5f)),
            width=.02f,color=0xff000000,pressures=listOf(.1f,.5f,1f))
        val bitmap=draw(mark);val restored=draw(InkCodec.decode(InkCodec.encode(listOf(mark))).single())
        try {
            fun span(x:Int)=(170..230).count { bitmap.getPixel(x,it)!=android.graphics.Color.WHITE }
            assertTrue("Heavy pressure must render a visibly wider stroke",span(320)>=span(80)+5)
            val a=IntArray(400*400);val b=IntArray(a.size)
            bitmap.getPixels(a,0,400,0,0,400,400);restored.getPixels(b,0,400,0,0,400,400)
            assertArrayEquals(a,b)
            val file=File("build/reports/screenshots/0127-pressure-width.png");file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { bitmap.recycle();restored.recycle() }
    }
    @Test fun overlappingPressureContoursDoNotMultiplyTransparencyAtSamplePoints() {
        val mark=InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.5f),InkPoint(.5f,.5f),InkPoint(.8f,.5f)),
            width=.02f,color=0x800000ff,pressures=listOf(.5f,.5f,.5f))
        val bitmap=draw(mark)
        try {
            assertEquals(bitmap.getPixel(140,200),bitmap.getPixel(200,200))
            assertTrue(android.graphics.Color.red(bitmap.getPixel(200,200)) in 120..135)
        } finally { bitmap.recycle() }
    }
}
