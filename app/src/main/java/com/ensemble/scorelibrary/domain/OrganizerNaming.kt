package com.ensemble.scorelibrary.domain

import org.json.JSONObject

data class OrganizerRules(val instruments: Map<String, String>, val categories: Map<String, String>) {
    companion object {
        fun read(text: String): OrganizerRules {
            require(text.toByteArray().size <= 64 * 1024) { "字典文件过大" }
            val root = JSONObject(text)
            require(root.getString("format") == "score-organizer-naming" && root.getInt("version") == 1 && root.getInt("sequenceDigits") == 3) { "不支持的整理器规则格式" }
            fun dictionary(key: String): Map<String, String> {
                val obj = root.getJSONObject(key)
                require(obj.length() in 1..100) { "字典不能为空" }
                return obj.keys().asSequence().associateWith { code ->
                    require(code.matches(Regex("[0-9]{2}"))) { "编号必须为两位数字" }
                    val name = obj.get(code)
                    require(name is String && name.isNotBlank() && name.length <= 100) { "字典名称无效" }
                    name.trim()
                }.also { require(it.values.toSet().size == it.size) { "字典名称重复" } }
            }
            return OrganizerRules(dictionary("instruments"), dictionary("categories"))
        }
    }
}

data class OrganizerFilename(val title: String?, val instrumentCode: String?, val instrumentName: String?,
    val categoryCode: String?, val categoryName: String?, val sequence: String?, val error: String? = null)

/** Exact desktop V1 framing: 2 instrument digits + 2 category digits + optional 3 sequence digits. */
fun parseOrganizerFilename(filename: String, rules: OrganizerRules): OrganizerFilename {
    val name = filename.substringAfterLast('/').substringAfterLast('\\')
    if (!name.endsWith(".pdf", ignoreCase = true)) return OrganizerFilename(null, null, null, null, null, null, "不是 PDF 文件")
    val stem = name.dropLast(4)
    if (stem.matches(Regex("[0-9]{2}[-_].*"))) {
        val match = Regex("([0-9]{2})-([0-9]{2})-([1-9][0-9]{3,18})-(.+)").matchEntire(stem)
        if (match == null || runCatching { ScoreNumber.sequence(match.groupValues[3]) }.isFailure)
            return OrganizerFilename(null, null, null, null, null, null, "扩位编号格式无效，请人工核对完整编号")
        val instrument=match.groupValues[1];val category=match.groupValues[2];val sequence=match.groupValues[3]
        val title=match.groupValues[4].trim()
        val error=when {
            instrument !in rules.instruments -> "乐器编号 $instrument 未定义"
            category !in rules.categories -> "分类编号 $category 未定义"
            title.isEmpty() -> "曲名为空"
            else -> null
        }
        return OrganizerFilename(title.ifEmpty { null },instrument,rules.instruments[instrument],category,rules.categories[category],sequence,error)
    }
    val isNew = stem.length >= 8 && stem.take(7).all { it in '0'..'9' }
    if (!isNew && !(stem.length >= 5 && stem.take(4).all { it in '0'..'9' }))
        return OrganizerFilename(null, null, null, null, null, null, "未识别编号，请人工填写曲目、声部和分类")
    val instrument = stem.take(2); val category = stem.substring(2, 4)
    val title = stem.drop(if (isNew) 7 else 4).trim()
    val sequence = if (isNew) stem.substring(4, 7) else null
    val error = when {
        instrument !in rules.instruments -> "乐器编号 $instrument 未定义，请加载整理器字典或人工填写"
        category !in rules.categories -> "分类编号 $category 未定义，请加载整理器字典或人工填写"
        title.isEmpty() -> "曲名为空"
        sequence == "000" -> "曲目序号无效"
        else -> null
    }
    return OrganizerFilename(title.ifEmpty { null }, instrument, rules.instruments[instrument], category,
        rules.categories[category], sequence, error)
}
