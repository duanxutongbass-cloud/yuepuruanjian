package com.ensemble.scorelibrary.annotation

import android.view.MotionEvent
import com.ensemble.scorelibrary.pdf.PagePoint
import com.ensemble.scorelibrary.pdf.PdfPageTransform

enum class InkInputMode(val label: String) { TOUCH("手指书写"), PEN("手写笔优先") }

/** One owner per stroke. Other fingers never join a pen's trail or finish it. */
internal class InkPointerInput(
    private val mode: InkInputMode,
    private val transform: PdfPageTransform,
    private val trail: MutableList<InkPoint>,
    private val started: () -> Unit,
    private val finished: (List<InkPoint>, Boolean) -> Unit,
    private val canceled: () -> Unit,
    private val ended: () -> Unit,
    private val pressures:MutableList<Float>?=null,
    private val sideButton:InkSideButton=InkSideButton.OFF,
) {
    private var owner: Int? = null
    private var ownerType = MotionEvent.TOOL_TYPE_UNKNOWN
    private var barrelErase=false
    val erasing get() = owner != null && (ownerType == MotionEvent.TOOL_TYPE_ERASER || barrelErase)
    private fun pen(type: Int) = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER

    fun cancel() {
        if (owner == null) return
        owner = null
        trail.clear()
        pressures?.clear();barrelErase=false
        canceled()
        ended()
    }

    /** Consume editing sequences, even ignored palms; navigation remains explicit via 阅读. */
    fun event(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
            cancel(); return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> Unit
            else -> return true
        }
        // A fresh sequence cannot inherit an unfinished stroke from a lost system event.
        if (action == MotionEvent.ACTION_DOWN) cancel()
        val actionIndex = event.actionIndex
        val down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        if (down) {
            val type = event.getToolType(actionIndex)
            if (owner != null && !pen(ownerType) && (pen(type) || event.pointerCount > 1)) cancel()
            if (owner != null && pen(ownerType) && pen(type) && event.getPointerId(actionIndex) != owner) {
                cancel(); return true // Two pens are ambiguous; neither partial stroke is committed.
            }
            val singlePen = pen(type) && (0 until event.pointerCount).count { pen(event.getToolType(it)) } == 1
            if (owner == null && (singlePen || mode == InkInputMode.TOUCH &&
                    type == MotionEvent.TOOL_TYPE_FINGER && event.pointerCount == 1)) {
                val point = point(event.getX(actionIndex), event.getY(actionIndex)) ?: return true
                owner = event.getPointerId(actionIndex); ownerType = type
                barrelErase=type==MotionEvent.TOOL_TYPE_STYLUS && sideButton.mask!=0 && event.buttonState and sideButton.mask!=0
                trail.clear(); trail.add(point);pressures?.clear()
                if(type==MotionEvent.TOOL_TYPE_STYLUS && !barrelErase)pressures?.add(InkPressure.sample(event.getPressure(actionIndex)))
                started()
            }
        }
        val id = owner ?: return true
        val index = event.findPointerIndex(id)
        if (index < 0 || event.getToolType(index) != ownerType) { cancel(); return true }
        if(action==MotionEvent.ACTION_MOVE && ownerType==MotionEvent.TOOL_TYPE_STYLUS && sideButton.mask!=0 &&
            (event.buttonState and sideButton.mask!=0)!=barrelErase) { cancel();return true }
        val up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
        val ownerUp = up && event.getPointerId(actionIndex) == id
        // FLAG_CANCELED belongs only to the pointer going up, not another valid pen.
        if (ownerUp && event.flags and MotionEvent.FLAG_CANCELED != 0) { cancel(); return true }
        if (action == MotionEvent.ACTION_MOVE || ownerUp) {
            for (sample in 0 until event.historySize) {
                if (!append(event.getHistoricalX(index, sample), event.getHistoricalY(index, sample),event.getHistoricalPressure(index,sample))) return true
            }
            if (!append(event.getX(index), event.getY(index),event.getPressure(index))) return true
        }
        if (ownerUp) {
            val erase = erasing
            val points = trail.toList()
            owner = null
            try { finished(points, erase) } finally { trail.clear();pressures?.clear();barrelErase=false; ended() }
        }
        return true
    }

    private fun point(x: Float, y: Float): InkPoint? {
        if (!x.isFinite() || !y.isFinite()) return null
        return transform.screenToNormalized(PagePoint(x, y))?.let { InkPoint(it.x, it.y) }
    }
    private fun append(x: Float, y: Float,rawPressure:Float): Boolean {
        val point = point(x, y)
        if (point == null || trail.size >= 4096) { cancel(); return false }
        val pressure=if(ownerType==MotionEvent.TOOL_TYPE_STYLUS && !barrelErase)pressures?.let { InkPressure.sample(rawPressure,it.lastOrNull()) } else null
        if (trail.lastOrNull() != point) {
            trail.add(point);if(pressure!=null)pressures?.add(pressure)
        } else if(pressure!=null && pressures?.isNotEmpty()==true)pressures[pressures.lastIndex]=pressure
        return true
    }
}
