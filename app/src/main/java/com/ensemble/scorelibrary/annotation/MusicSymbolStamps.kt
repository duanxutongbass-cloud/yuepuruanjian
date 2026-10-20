package com.ensemble.scorelibrary.annotation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

internal fun DrawScope.drawMusicStamp(symbol: String, p: Offset, size: Float, color: Color) {
                val stroke = (size / 12).coerceAtLeast(1f)
                when (symbol) {
                    "downbow" -> {
                        drawLine(color, p, p + Offset(0f,-size*.7f),stroke)
                        drawLine(color, p + Offset(0f,-size*.7f),p + Offset(size,-size*.7f),stroke*2)
                        drawLine(color, p + Offset(size,-size*.7f),p + Offset(size,0f),stroke)
                    }
                    "upbow" -> {
                        drawLine(color,p + Offset(0f,-size*.7f),p + Offset(size/2,0f),stroke)
                        drawLine(color,p + Offset(size/2,0f),p + Offset(size,-size*.7f),stroke)
                    }
                    "<", ">" -> drawHairpin(symbol, p + Offset(0f,-size/4),
                        p + Offset(size * 4, -size/4), size / 3, color, (size / 22).coerceAtLeast(1f))
                    "accent" -> {
                        val point = p + Offset(size, -size/4)
                        drawLine(color, point, p + Offset(0f,-size/2), stroke)
                        drawLine(color, point, p, stroke)
                    }
                    "staccato" -> drawCircle(color, size / 10, p)
                    "tenuto" -> drawLine(color, p, p + Offset(size, 0f), stroke)
                    "fermata" -> {
                        drawArc(color, 180f, 180f, false, p + Offset(0f, -size / 2), androidx.compose.ui.geometry.Size(size, size), style = Stroke(stroke))
                        drawCircle(color, size / 12, p + Offset(size / 2, -size / 8))
                    }
                    else -> drawContext.canvas.nativeCanvas.drawText(symbol, p.x, p.y,
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color.toArgb(); textSize = size
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD_ITALIC)
                    })
                }

}

internal fun DrawScope.drawHairpin(symbol: String, left: Offset, right: Offset, opening: Float, color: Color, width: Float) {
    val tip = if (symbol == "<") left else right
    val open = if (symbol == "<") right else left
    drawLine(color, tip, open + Offset(0f, -opening/2), width, StrokeCap.Round)
    drawLine(color, tip, open + Offset(0f, opening/2), width, StrokeCap.Round)
}
