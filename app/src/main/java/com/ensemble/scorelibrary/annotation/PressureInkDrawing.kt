package com.ensemble.scorelibrary.annotation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.hypot

/** Shared by the live page and saved/recovery/cloud previews. Legacy marks use their original path. */
internal fun DrawScope.drawPressureInk(mark:InkMark,pageWidth:Float,color:Color,point:(InkPoint)->Offset) {
    checkNotNull(mark.pressures)
    val path=Path().apply { fillType=PathFillType.NonZero }
    var previous:Offset?=null;var previousRadius=0f
    for(i in mark.points.indices) {
        val current=point(mark.points[i]);val radius=InkPressure.width(mark,i)*pageWidth/2
        path.addOval(Rect(current.x-radius,current.y-radius,current.x+radius,current.y+radius))
        previous?.let { a ->
            val delta=current-a;val length=hypot(delta.x,delta.y)
            if(length>0f) {
                val normal=Offset(-delta.y/length,delta.x/length)
                val p=a+normal*previousRadius;val q=a-normal*previousRadius
                val u=current-normal*radius;val v=current+normal*radius
                // Match the oval caps' winding so overlaps add rather than cut holes.
                path.moveTo(p.x,p.y);path.lineTo(v.x,v.y);path.lineTo(u.x,u.y);path.lineTo(q.x,q.y);path.close()
            }
        }
        previous=current;previousRadius=radius
    }
    // One fill keeps translucent ink uniform where the generated contours overlap.
    drawPath(path,color)
}
