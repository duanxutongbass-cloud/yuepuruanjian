package com.ensemble.scorelibrary.annotation

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot

data class InkPoint(val x: Float, val y: Float) {
    init { require(x in 0f..1f && y in 0f..1f) }
}
enum class MarkKind { PEN, HIGHLIGHT, SYMBOL }
enum class AnnotationTool { READ, PEN, HIGHLIGHT, ERASER, LASSO, MOVE, SYMBOL }
enum class AnnotationLayer { PERSONAL, SHARED }
data class InkMark(val id: String = UUID.randomUUID().toString(), val kind: MarkKind,
    val points: List<InkPoint>, val color: Long = 0xffd32f2f, val width: Float = .003f, val symbol: String = "f") {
    init {
        require(points.isNotEmpty() && points.size <= 20_000)
        require(width in .0005f.. .05f)
        require(color in 0L..0xffffffffL)
        require(symbol in musicSymbols)
    }
}
val musicSymbolGroups = linkedMapOf(
    "力度" to listOf("ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "sfz", "fp"),
    "渐变" to listOf("cresc.", "dim.", "<", ">"),
    "变音" to listOf("♯", "♭", "♮"),
    "演奏" to listOf("fermata", "accent", "staccato", "tenuto", "rit.", "a tempo", "D.C.", "D.S.", "Fine"))
val musicSymbols = musicSymbolGroups.values.flatten()

object InkCodec {
    fun encode(marks: List<InkMark>): String {
        require(marks.size <= 2000)
        require(marks.sumOf { it.points.size } <= 100_000) { "本页笔迹过多，请精简后重试" }
        return JSONArray().apply { marks.forEach { mark -> put(JSONObject().apply {
            put("id", mark.id); put("kind", mark.kind.name); put("color", mark.color); put("width", mark.width)
            put("symbol", mark.symbol)
            put("points", JSONArray().apply { mark.points.forEach { put(JSONArray().put(it.x).put(it.y)) } })
        }) } }.toString()
    }
    fun decode(payload: String): List<InkMark> {
        require(payload.length <= 8_000_000)
        val array = JSONArray(payload)
        require(array.length() <= 2000)
        require((0 until array.length()).sumOf { array.getJSONObject(it).getJSONArray("points").length().toLong() } <= 100_000)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index); val points = item.getJSONArray("points")
            require(points.length() in 1..20_000)
            InkMark(item.getString("id"), MarkKind.valueOf(item.getString("kind")),
                (0 until points.length()).map { i -> InkPoint(points.getJSONArray(i).getDouble(0).toFloat(), points.getJSONArray(i).getDouble(1).toFloat()) },
                item.getLong("color"), item.getDouble("width").toFloat(), item.getString("symbol"))
        }.also { require(it.map { mark -> mark.id }.toSet().size == it.size) }
    }
}

object InkGeometry {
    fun inside(point: InkPoint, polygon: List<InkPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false; var previous = polygon.last()
        for (next in polygon) {
            if ((next.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - next.x) * (point.y - next.y) / (previous.y - next.y) + next.x) inside = !inside
            previous = next
        }
        return inside
    }
    fun selected(marks: List<InkMark>, polygon: List<InkPoint>) = marks.filter { mark ->
        mark.points.any { inside(it, polygon) }
    }.map { it.id }.toSet()
    fun erase(marks: List<InkMark>, trail: List<InkPoint>, radius: Float): List<InkMark> = marks.filterNot { mark ->
        trail.any { touch ->
            if (mark.points.size == 1) hypot(touch.x - mark.points[0].x, touch.y - mark.points[0].y) <= radius
            else mark.points.zipWithNext().any { (a, b) ->
                val dx = b.x - a.x; val dy = b.y - a.y
                val length = dx * dx + dy * dy
                val t = if (length == 0f) 0f else (((touch.x - a.x) * dx + (touch.y - a.y) * dy) / length).coerceIn(0f, 1f)
                hypot(touch.x - a.x - t * dx, touch.y - a.y - t * dy) <= radius + mark.width / 2
            }
        }
    }
    fun move(marks: List<InkMark>, selection: Set<String>, dx: Float, dy: Float): List<InkMark> {
        val points = marks.filter { it.id in selection }.flatMap { it.points }
        if (points.isEmpty()) return marks
        val x = dx.coerceIn(-points.minOf { it.x }, 1 - points.maxOf { it.x })
        val y = dy.coerceIn(-points.minOf { it.y }, 1 - points.maxOf { it.y })
        return marks.map { if (it.id !in selection) it else it.copy(points = it.points.map { p -> InkPoint(p.x + x, p.y + y) }) }
    }
}
