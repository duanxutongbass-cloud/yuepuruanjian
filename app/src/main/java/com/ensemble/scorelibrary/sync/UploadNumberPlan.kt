package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.OrganizerRules
import com.ensemble.scorelibrary.domain.ScoreNumber
import java.util.Locale

internal enum class UploadIssueKind { METADATA, DUPLICATE, SIMILAR, AMBIGUOUS, CAPACITY, MANUAL_CONFLICT }
internal data class UploadNumberIssue(val index: Int, val kind: UploadIssueKind, val message: String,
    val matches: List<ExistingTeamScore> = emptyList())
internal data class UploadNumberPlan(val rows: List<TeamPublishDetails>, val issues: List<UploadNumberIssue>)
internal fun uploadTitleKey(title: String) = title.trim().replace(Regex("\\s+"), "").lowercase(Locale.ROOT)

/** Preview only. The existing server publication transaction remains authoritative. */
internal fun planUploadNumbers(input: List<TeamPublishDetails>, existing: List<ExistingTeamScore>,
    rules: OrganizerRules, manual: Set<Int> = emptySet(), distinctConfirmed: Set<Int> = emptySet(),
    reservedSequences: Set<Long> = emptySet(), maximum: Long = 999): UploadNumberPlan {
    require(maximum in setOf(999L, Long.MAX_VALUE) && reservedSequences.all { it in 1..maximum }) { "占号信息无效，请重新检查编号" }
    val numbered = existing.filter { it.number.isNotEmpty() }.onEach { ScoreNumber.parse(it.number) }
    val used = numbered.map { ScoreNumber.parse(it.number).sequence.toLong() }.toMutableSet()
    used += reservedSequences
    manual.mapNotNull { i -> input.getOrNull(i)?.number?.let { runCatching { ScoreNumber.parse(it).sequence.toLong() }.getOrNull() } }.forEach(used::add)
    val staged = mutableListOf<ExistingTeamScore>()
    val issues = mutableListOf<UploadNumberIssue>()
    fun code(label: String, dictionary: Map<String, String>): String? {
        val leading = label.trim().take(2)
        if (leading in dictionary) return leading
        return dictionary.entries.singleOrNull { uploadTitleKey(it.value) == uploadTitleKey(label) }?.key
    }
    val rows = input.mapIndexed { i, raw ->
        var d = raw.copy(title = raw.title.trim(), part = raw.part.trim(), category = raw.category.trim())
        val supplied = runCatching { ScoreNumber.parse(d.number) }.getOrNull()
        val part = code(d.part, rules.instruments) ?: supplied?.instrument?.takeIf { it in rules.instruments }
        val exact = (numbered + staged).filter { uploadTitleKey(it.title) == uploadTitleKey(d.title) }
        val category = code(d.category, rules.categories) ?: supplied?.category?.takeIf { it in rules.categories }
            ?: exact.map { ScoreNumber.parse(it.number).category }.distinct().singleOrNull()
        if (d.title.isBlank() || part == null || part == "09" || category == null) {
            issues += UploadNumberIssue(i, UploadIssueKind.METADATA, "请填写曲名并选择乐器、分类；无需自行填写流水号")
            return@mapIndexed d.copy(number = if (i in manual) d.number else "")
        }
        val matches = exact.filter { ScoreNumber.parse(it.number).category == category }
        val groups = matches.groupBy { ScoreNumber.parse(it.number).sequence }
        var sequence: String? = null
        if (i in manual) {
            if (supplied == null || supplied.sequence.toLong() > maximum || supplied.instrument != part || supplied.category != category) {
                issues += UploadNumberIssue(i, UploadIssueKind.MANUAL_CONFLICT, "特殊编号须与乐器、分类及服务支持的流水号范围一致")
                return@mapIndexed d
            }
            sequence = supplied.sequence
            val occupied = (numbered + staged).filter { ScoreNumber.parse(it.number).sequence == sequence }
            if (sequence.toLong() in reservedSequences && occupied.isEmpty()) {
                issues += UploadNumberIssue(i, UploadIssueKind.MANUAL_CONFLICT, "特殊编号已被未完成上传或历史记录保留，请选择真正空号")
                return@mapIndexed d
            }
            if (occupied.any { uploadTitleKey(it.title) != uploadTitleKey(d.title) || ScoreNumber.parse(it.number).category != category }) {
                issues += UploadNumberIssue(i, UploadIssueKind.MANUAL_CONFLICT, "特殊编号已被另一首乐曲占用", occupied)
                return@mapIndexed d
            }
            if (occupied.isEmpty() && matches.isNotEmpty()) {
                issues += UploadNumberIssue(i, UploadIssueKind.MANUAL_CONFLICT, "同曲补谱应沿用原号；如需另一版，请明确新建版本", matches)
                return@mapIndexed d
            }
        } else if (groups.size > 1) {
            issues += UploadNumberIssue(i, UploadIssueKind.AMBIGUOUS, "曲库有多组同名记录，请选择原曲目或明确新建版本", matches)
            return@mapIndexed d.copy(number = "")
        } else if (groups.size == 1) sequence = groups.keys.single()
        val same = if (sequence == null) emptyList() else (numbered + staged).filter { ScoreNumber.parse(it.number).sequence == sequence }
        if (same.any { it.number.take(2) == part }) {
            issues += UploadNumberIssue(i, UploadIssueKind.DUPLICATE, "该曲已有相同乐器：请对比PDF，选择新建第二版或取消本份", same.filter { it.number.take(2) == part })
            return@mapIndexed d.copy(number = ScoreNumber.format(part, category, checkNotNull(sequence)))
        }
        if (same.any { it.removed }) {
            issues += UploadNumberIssue(i, UploadIssueKind.AMBIGUOUS, "原曲目在回收站，请先恢复或明确新建版本", same)
            return@mapIndexed d.copy(number = ScoreNumber.format(part, category, checkNotNull(sequence)))
        }
        if (sequence == null) {
            val key = uploadTitleKey(d.title)
            val similar = numbered.filter { ScoreNumber.parse(it.number).category == category && uploadTitleKey(it.title).let { old ->
                old != key && minOf(old.length, key.length) >= 3 && (old.contains(key) || key.contains(old))
            } }.distinctBy { ScoreNumber.parse(it.number).sequence }
            if (similar.isNotEmpty() && i !in distinctConfirmed) {
                issues += UploadNumberIssue(i, UploadIssueKind.SIMILAR, "发现相似曲名，请确认是已有乐曲还是独立新曲", similar)
                return@mapIndexed d.copy(number = "")
            }
            val next = TeamNumberInventory(0, used, 0, maximum).firstFree
            if (next == null) {
                issues += UploadNumberIssue(i, UploadIssueKind.CAPACITY, "服务支持的编号均已使用或保留；不会更改已有编号")
                return@mapIndexed d.copy(number = "")
            }
            sequence = next.toString().padStart(3, '0')
            used += next
        }
        val owner = same.firstOrNull()
        d = d.copy(title = owner?.title ?: d.title, category = owner?.category ?: "$category ${rules.categories.getValue(category)}",
            part = "$part ${rules.instruments.getValue(part)}", number = ScoreNumber.format(part, category, checkNotNull(sequence)))
        staged += ExistingTeamScore(d.number, d.title, d.category, d.part, false)
        d
    }
    return UploadNumberPlan(rows, issues)
}

internal fun nextUploadVersionTitle(title: String, existing: List<ExistingTeamScore>): String {
    val suffix = Regex("[（(]第(二|三|四|五|六|七|八|九|十|[0-9]+)版[）)]$")
    val names = listOf("", "", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    val base = title.trim().replace(suffix, "")
    val highest = existing.filter { uploadTitleKey(it.title.replace(suffix, "")) == uploadTitleKey(base) }.maxOfOrNull {
        suffix.find(it.title)?.groupValues?.get(1)?.let { n -> n.toIntOrNull() ?: names.indexOf(n) } ?: 1
    } ?: 1
    val version = highest + 1
    return "$base（第${names.getOrNull(version) ?: version.toString()}版）"
}
