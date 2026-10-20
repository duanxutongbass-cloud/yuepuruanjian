package com.ensemble.scorelibrary.annotation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Two normalized centre-line endpoints use the existing ink wire format. */
object HairpinGeometry {
    fun isHairpin(symbol: String) = symbol == "<" || symbol == ">"
    fun placement(points: List<InkPoint>, length: Float): List<InkPoint> {
        require(points.isNotEmpty())
        val first = points.first()
        val last = points.last()
        if (abs(last.x - first.x) >= .02f)
            return listOf(InkPoint(min(first.x, last.x), first.y), InkPoint(max(first.x, last.x), first.y))
        val span = length.coerceIn(.04f, .65f)
        val left = first.x.coerceAtMost(1f - span)
        return listOf(InkPoint(left, first.y), InkPoint((left + span).coerceAtMost(1f), first.y))
    }
    fun resize(mark: InkMark, length: Float, opening: Float): InkMark {
        require(mark.kind == MarkKind.SYMBOL && isHairpin(mark.symbol))
        return mark.copy(points = placement(listOf(mark.points.first()), length), symbolScale = opening)
    }
}
