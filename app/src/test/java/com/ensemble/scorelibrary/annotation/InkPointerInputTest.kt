package com.ensemble.scorelibrary.annotation

import android.view.InputDevice
import android.view.MotionEvent
import com.ensemble.scorelibrary.pdf.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InkPointerInputTest {
    private data class P(val id: Int, val type: Int, val x: Float = 100f, val y: Float = 100f,val pressure:Float=1f)
    private val pen = P(7, MotionEvent.TOOL_TYPE_STYLUS)
    private val finger = P(3, MotionEvent.TOOL_TYPE_FINGER, 250f, 250f)
    private val trail = mutableListOf<InkPoint>()
    private val written = mutableListOf<Pair<List<InkPoint>, Boolean>>()
    private var starts = 0
    private var cancels = 0
    private var ends = 0
    private val pressures=mutableListOf<Float>()
    private val writtenPressures=mutableListOf<List<Float>>()
    private fun input(mode: InkInputMode = InkInputMode.PEN,usePressure:Boolean=false,button:InkSideButton=InkSideButton.OFF) = InkPointerInput(mode,
        PdfPageTransform.fit(PageSize(400, 400), 400, 400, FitMode.PAGE), trail,
        { starts++ }, { points, erase -> written += points to erase;writtenPressures+=pressures.toList() }, { cancels++ }, { ends++ },
        pressures.takeIf { usePressure },button)
    private fun event(action: Int, vararg pointers: P, index: Int = 0, flags: Int = 0,buttons:Int=0) = MotionEvent.obtain(
        1L, 20L, action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pointers.size,
        pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.type } }.toTypedArray(),
        pointers.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = p.pressure; size = .1f } }.toTypedArray(),
        0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags)
    private fun InkPointerInput.send(action: Int, vararg pointers: P, index: Int = 0, flags: Int = 0,buttons:Int=0) {
        val e = event(action, *pointers, index = index, flags = flags,buttons=buttons)
        try { assertTrue(event(e)) } finally { e.recycle() }
    }
    @Test fun pressureTracksOwnerIndexHistoryAndZeroLiftWithoutIncludingPalmPressure() {
        val input=input(usePressure=true)
        input.send(MotionEvent.ACTION_DOWN,pen.copy(pressure=.1f))
        input.send(MotionEvent.ACTION_POINTER_DOWN,pen.copy(pressure=.1f),finger,index=1)
        input.send(MotionEvent.ACTION_MOVE,finger.copy(pressure=.99f),pen.copy(x=200f,pressure=.3f))
        input.send(MotionEvent.ACTION_POINTER_UP,pen.copy(x=200f,pressure=.3f),finger,index=1)
        val e=event(MotionEvent.ACTION_MOVE,pen.copy(x=240f,pressure=.4f))
        e.addBatch(30L,300f,100f,.9f,.1f,0)
        try { input.event(e) } finally { e.recycle() }
        input.send(MotionEvent.ACTION_UP,pen.copy(x=300f,pressure=0f))
        assertEquals(listOf(.1f,.3f,.4f,.9f),writtenPressures.single())
        assertEquals(written.single().first.size,writtenPressures.single().size)
        assertTrue(pressures.isEmpty())
    }
    @Test fun pressureIsNotInventedForFingerOrEraserAndCancellationClearsIt() {
        val input=input(InkInputMode.TOUCH,usePressure=true)
        input.send(MotionEvent.ACTION_DOWN,finger);input.send(MotionEvent.ACTION_UP,finger)
        input.send(MotionEvent.ACTION_DOWN,pen.copy(type=MotionEvent.TOOL_TYPE_ERASER));input.send(MotionEvent.ACTION_UP,pen.copy(type=MotionEvent.TOOL_TYPE_ERASER))
        assertTrue(writtenPressures.all { it.isEmpty() })
        input.send(MotionEvent.ACTION_DOWN,pen);assertTrue(pressures.isNotEmpty())
        input.send(MotionEvent.ACTION_CANCEL,pen);assertTrue(pressures.isEmpty());assertEquals(2,written.size)
    }
    @Test fun configuredBarrelIsTemporaryAndPrimarySecondaryRemainIndependent() {
        val input=input(usePressure=true,button=InkSideButton.PRIMARY)
        input.send(MotionEvent.ACTION_DOWN,pen,buttons=MotionEvent.BUTTON_STYLUS_PRIMARY);assertTrue(input.erasing)
        input.send(MotionEvent.ACTION_MOVE,pen.copy(x=150f),buttons=MotionEvent.BUTTON_STYLUS_PRIMARY)
        input.send(MotionEvent.ACTION_UP,pen.copy(x=200f))
        assertTrue(written.single().second);assertTrue(writtenPressures.single().isEmpty());assertFalse(input.erasing)
        input.send(MotionEvent.ACTION_DOWN,pen,buttons=MotionEvent.BUTTON_STYLUS_SECONDARY)
        input.send(MotionEvent.ACTION_UP,pen)
        assertFalse(written.last().second);assertFalse(writtenPressures.last().isEmpty())
    }
    @Test fun changingBarrelWhileMovingCancelsWholeUnfinishedActionAndRequiresFreshDown() {
        val input=input(button=InkSideButton.SECONDARY)
        input.send(MotionEvent.ACTION_DOWN,pen)
        input.send(MotionEvent.ACTION_MOVE,pen.copy(x=200f),buttons=MotionEvent.BUTTON_STYLUS_SECONDARY)
        input.send(MotionEvent.ACTION_MOVE,pen.copy(x=250f),buttons=MotionEvent.BUTTON_STYLUS_SECONDARY)
        input.send(MotionEvent.ACTION_UP,pen)
        assertTrue(written.isEmpty());assertEquals(1,cancels)
        input.send(MotionEvent.ACTION_DOWN,pen,buttons=MotionEvent.BUTTON_STYLUS_SECONDARY)
        input.send(MotionEvent.ACTION_MOVE,pen.copy(x=200f))
        input.send(MotionEvent.ACTION_UP,pen)
        assertTrue(written.isEmpty());assertEquals(2,cancels)
    }
    @Test fun penModeIgnoresFingerAndMouseWithoutInventingCancellation() {
        val input = input()
        for (p in listOf(finger, P(9, MotionEvent.TOOL_TYPE_MOUSE))) {
            input.send(MotionEvent.ACTION_DOWN, p); input.send(MotionEvent.ACTION_UP, p)
        }
        assertEquals(0, starts); assertEquals(0, cancels); assertTrue(written.isEmpty())
    }
    @Test fun palmAfterPenAndCanceledPalmUpDoNotDiscardOrPolluteValidPen() {
        val input = input()
        input.send(MotionEvent.ACTION_DOWN, pen)
        input.send(MotionEvent.ACTION_POINTER_DOWN, pen, finger, index = 1)
        input.send(MotionEvent.ACTION_MOVE, finger, pen.copy(x = 200f)) // Pointer indices can reorder.
        input.send(MotionEvent.ACTION_POINTER_UP, pen.copy(x = 200f), finger, index = 1, flags = MotionEvent.FLAG_CANCELED)
        input.send(MotionEvent.ACTION_UP, pen.copy(x = 300f))
        assertEquals(listOf(.25f, .5f, .75f), written.single().first.map { it.x })
        assertEquals(0, cancels); assertEquals(1, ends)
    }
    @Test fun palmBeforePenAllowsSeveralSeparatePenStrokesWhilePalmStaysDown() {
        val input = input()
        input.send(MotionEvent.ACTION_DOWN, finger)
        repeat(2) {
            input.send(MotionEvent.ACTION_POINTER_DOWN, finger, pen, index = 1)
            input.send(MotionEvent.ACTION_POINTER_UP, finger, pen.copy(x = 200f), index = 1)
        }
        input.send(MotionEvent.ACTION_UP, finger)
        assertEquals(2, written.size); assertEquals(2, starts); assertEquals(0, cancels)
    }
    @Test fun penTakesOverUnfinishedTouchWithoutCommittingTouchInTouchMode() {
        val input = input(InkInputMode.TOUCH)
        input.send(MotionEvent.ACTION_DOWN, finger)
        input.send(MotionEvent.ACTION_POINTER_DOWN, finger, pen, index = 1)
        input.send(MotionEvent.ACTION_POINTER_UP, finger, pen.copy(x = 200f), index = 1)
        input.send(MotionEvent.ACTION_UP, finger)
        assertEquals(1, cancels); assertEquals(1, written.size); assertEquals(.25f, written.single().first.first().x)
    }
    @Test fun secondTouchCancelsAndNeverResurrectsFirstFingerOnLift() {
        val input = input(InkInputMode.TOUCH)
        val other = finger.copy(id = 4)
        input.send(MotionEvent.ACTION_DOWN, finger)
        input.send(MotionEvent.ACTION_POINTER_DOWN, finger, other, index = 1)
        input.send(MotionEvent.ACTION_POINTER_UP, finger, other, index = 1)
        input.send(MotionEvent.ACTION_UP, finger)
        assertTrue(written.isEmpty()); assertEquals(1, cancels); assertTrue(trail.isEmpty())
    }
    @Test fun systemCancelAndFlaggedOwnerUpPreserveAlreadyCompletedStrokeOnly() {
        val input = input()
        input.send(MotionEvent.ACTION_DOWN, pen); input.send(MotionEvent.ACTION_UP, pen)
        input.send(MotionEvent.ACTION_DOWN, pen); input.send(MotionEvent.ACTION_CANCEL, pen)
        input.send(MotionEvent.ACTION_DOWN, pen); input.send(MotionEvent.ACTION_UP, pen, flags = MotionEvent.FLAG_CANCELED)
        assertEquals(1, written.size); assertEquals(2, cancels); assertEquals(3, ends)
    }
    @Test fun eraserEndHasExplicitTemporaryEraserMeaning() {
        val input = input()
        val eraser = pen.copy(type = MotionEvent.TOOL_TYPE_ERASER)
        input.send(MotionEvent.ACTION_DOWN, eraser); assertTrue(input.erasing)
        input.send(MotionEvent.ACTION_UP, eraser)
        assertTrue(written.single().second); assertFalse(input.erasing)
    }
    @Test fun secondPenOrDisappearingOwnerCannotCommitPartialStroke() {
        val input = input()
        input.send(MotionEvent.ACTION_DOWN, pen)
        input.send(MotionEvent.ACTION_POINTER_DOWN, pen, pen.copy(id = 8), index = 1)
        input.send(MotionEvent.ACTION_UP, pen)
        input.send(MotionEvent.ACTION_DOWN, pen)
        input.send(MotionEvent.ACTION_MOVE, finger)
        assertEquals(2, cancels); assertTrue(written.isEmpty())
    }
    @Test fun leavingPageAndInputDisposalCancelInsteadOfJoiningAcrossMargins() {
        val input = input()
        input.send(MotionEvent.ACTION_DOWN, pen)
        input.send(MotionEvent.ACTION_MOVE, pen.copy(x = -1f))
        input.send(MotionEvent.ACTION_UP, pen)
        input.send(MotionEvent.ACTION_DOWN, pen); input.cancel(); input.cancel()
        assertEquals(2, cancels); assertEquals(2, ends); assertTrue(written.isEmpty())
    }
    @Test fun batchedSamplesStayOrderedAndOverflowDiscardsWholeUnfinishedStroke() {
        val input = input()
        input.send(MotionEvent.ACTION_DOWN, pen)
        val e = event(MotionEvent.ACTION_MOVE, pen.copy(x = 120f))
        e.addBatch(30L, 180f, 100f, 1f, .1f, 0)
        try { input.event(e) } finally { e.recycle() }
        input.send(MotionEvent.ACTION_UP, pen.copy(x = 200f))
        assertEquals(listOf(.25f, .3f, .45f, .5f), written.single().first.map { it.x })
        input.send(MotionEvent.ACTION_DOWN, pen)
        repeat(4100) { input.send(MotionEvent.ACTION_MOVE, pen.copy(x = if (it % 2 == 0) 110f else 120f)) }
        input.send(MotionEvent.ACTION_UP, pen)
        assertEquals(1, cancels); assertEquals(1, written.size)
    }
}
