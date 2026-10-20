package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber
import org.json.JSONObject

data class TeamNumberInventory(val revision: Long, val occupied: Set<Long>, val pendingUploads: Int,
    val maximum: Long = 999) {
    val firstFree: Long? get() {
        var next = 1L
        for (n in occupied.sorted()) {
            if (n == next) { if (next == maximum) return null; next++ }
            else if (n > next) break
        }
        return next.takeIf { it <= maximum }
    }
    val availableCount get() = maximum - occupied.size
    companion object {
        fun parse(root: JSONObject): TeamNumberInventory {
            val version = root.get("protocolVersion")
            require(version == 1 || version == 2) { "编号规则不受支持，请先更新软件与服务" }
            val maximum = if (version == 1) 999L else Long.MAX_VALUE
            require(root.get("maximumSequence") == maximum.toString())
            val rev = root.get("revision").also { require(it is Int || it is Long) }.let { (it as Number).toLong() }
            require(rev >= 0)
            val list = root.getJSONArray("occupiedSequences").also { require(it.length() <= if (version == 1) 999 else 100000) }
            val numbers = (0 until list.length()).map { i ->
                require(list.get(i) is String)
                ScoreNumber.sequence(list.getString(i)).also { require(it <= maximum) }
            }
            require(numbers.distinct().size == numbers.size && numbers == numbers.sorted())
            val pending = root.get("pendingUploads").also { require(it is Int && it >= 0) } as Int
            val result = TeamNumberInventory(rev, numbers.toSet(), pending, maximum)
            require(root.get("availableCount") == if (version == 2) result.availableCount.toString() else result.availableCount.toInt())
            require(root.get("firstFreeSequence") == (result.firstFree?.toString()?.padStart(3, '0') ?: JSONObject.NULL))
            return result
        }
    }
}
