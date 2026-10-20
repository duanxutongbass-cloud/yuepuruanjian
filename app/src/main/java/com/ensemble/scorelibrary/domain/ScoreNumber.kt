package com.ensemble.scorelibrary.domain

/** Canonical ASCII strings; Long is used only for exact integer comparison. */
data class ScoreNumber(val instrument: String, val category: String, val sequence: String) {
    val namingVersion get() = if (sequence.length == 3) 1 else 2
    val value get() = if (namingVersion == 1) instrument + category + sequence else "$instrument-$category-$sequence"
    companion object {
        fun sequence(value: String): Long {
            require(value.matches(Regex("[0-9]{3}|[1-9][0-9]{3,18}"))) { "曲目序号格式无效" }
            val number = value.toLongOrNull()
            require(number != null && number > 0) { "曲目序号超出范围" }
            return number
        }
        fun parse(value: String): ScoreNumber {
            val old = Regex("([0-9]{2})([0-9]{2})([0-9]{3})").matchEntire(value)
            val match = old ?: Regex("([0-9]{2})-([0-9]{2})-([1-9][0-9]{3,18})").matchEntire(value)
            require(match != null) { "编号须为旧七位号或如01-01-1000的完整编号" }
            sequence(match.groupValues[3])
            return ScoreNumber(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        fun optional(value: String): ScoreNumber? = if (value.isEmpty()) null else parse(value)
        fun format(instrument: String, category: String, sequence: String): String =
            ScoreNumber(instrument, category, sequence).value.also { parse(it) }
    }
}
