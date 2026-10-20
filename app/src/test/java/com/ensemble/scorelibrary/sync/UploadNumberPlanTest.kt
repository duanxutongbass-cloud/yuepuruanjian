package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.OrganizerRules
import org.junit.Assert.*
import org.junit.Test

class UploadNumberPlanTest {
    private val rules = OrganizerRules(mapOf("01" to "第一小提琴", "03" to "中提琴", "04" to "大提琴", "09" to "组合谱"),
        mapOf("01" to "动画", "02" to "中国风格"))
    private fun row(title: String = "天空之城", part: String = "03 中提琴", category: String = "01 动画", number: String = "") =
        TeamPublishDetails(title, part, number, category, setOf("admin"))
    private val first = ExistingTeamScore("0101007", "天空之城", "01 动画", "01 第一小提琴", false)
    private fun plan(rows: List<TeamPublishDetails>, old: List<ExistingTeamScore> = listOf(first), manual: Set<Int> = emptySet(), distinct: Set<Int> = emptySet()) =
        planUploadNumbers(rows, old, rules, manual, distinct)

    @Test fun newPieceUsesFirstTrulyFreeGlobalNumberWithoutChangingExistingNumbers() {
        val p = plan(listOf(row("新乐曲")))
        assertTrue(p.issues.isEmpty()); assertEquals("0301001", p.rows.single().number)
        assertEquals("0101007",first.number)
    }
    @Test fun differentInstrumentForSamePieceKeepsExistingNumber() {
        val p = plan(listOf(row()))
        assertTrue(p.issues.isEmpty()); assertEquals("0301007", p.rows.single().number)
        assertEquals(first.title, p.rows.single().title)
    }
    @Test fun normalizedExactTitleUsesOriginalDisplayMetadata() {
        val p = plan(listOf(row(" 天 空之城 ")))
        assertTrue(p.issues.isEmpty()); assertEquals("天空之城", p.rows.single().title)
        assertEquals(first.category, p.rows.single().category)
    }
    @Test fun repeatedExistingInstrumentRequiresExplicitDecision() {
        val p = plan(listOf(row(part = "01 第一小提琴")))
        assertEquals(UploadIssueKind.DUPLICATE, p.issues.single().kind)
        assertEquals(listOf(first), p.issues.single().matches)
    }
    @Test fun multipleUnnumberedPartsShareOneNewPieceAndNextPieceUsesAnother() {
        val p = plan(listOf(row("新乐曲"), row("新乐曲", "04 大提琴"), row("另首")))
        assertTrue(p.issues.isEmpty())
        assertEquals(listOf("0301001", "0401001", "0301002"), p.rows.map { it.number })
    }
    @Test fun autoModeUsesFileNumberOnlyForInstrumentAndCategoryNotItsOldSequence() {
        val p = plan(listOf(row("新乐曲", number = "0301999")))
        assertTrue(p.issues.isEmpty()); assertEquals("0301001", p.rows.single().number)
    }
    @Test fun explicitlyChosenFreeSpecialNumberIsPreserved() {
        val p = plan(listOf(row("新乐曲", number = "0301042")), manual = setOf(0))
        assertTrue(p.issues.isEmpty()); assertEquals("0301042", p.rows.single().number)
    }
    @Test fun occupiedSpecialNumberCannotRenameOrOverwriteAnotherPiece() {
        val p = plan(listOf(row("新乐曲", number = "0301007")), manual = setOf(0))
        assertEquals(UploadIssueKind.MANUAL_CONFLICT, p.issues.single().kind)
        assertEquals("天空之城", first.title)
    }
    @Test fun removedPieceStillReservesItsGlobalNumber() {
        val p = plan(listOf(row("新乐曲")), listOf(first.copy(number = "0101001", removed = true)))
        assertTrue(p.issues.isEmpty()); assertEquals("0301002", p.rows.single().number)
    }
    @Test fun similarPieceDoesNotSilentlyMergeOrAllocateBeforeConfirmation() {
        val p = plan(listOf(row("天空之城交响版")))
        assertEquals(UploadIssueKind.SIMILAR, p.issues.single().kind)
        assertEquals("", p.rows.single().number)
        val confirmed = plan(listOf(row("天空之城交响版")), distinct = setOf(0))
        assertTrue(confirmed.issues.isEmpty()); assertEquals("0301001", confirmed.rows.single().number)
    }
    @Test fun multipleSameNamedGroupsRequireSelectionRatherThanGuessing() {
        val p = plan(listOf(row()), listOf(first, first.copy(number = "0101009")))
        assertEquals(UploadIssueKind.AMBIGUOUS, p.issues.single().kind)
    }
    @Test fun anotherCategoryDoesNotAutomaticallyJoinSameTitle() {
        val p = plan(listOf(row(category = "02 中国风格")))
        assertTrue(p.issues.isEmpty()); assertEquals("0302001", p.rows.single().number)
    }
    @Test fun capacityNeverWrapsAroundOrReusesNumbers() {
        val p = plan(listOf(row("新乐曲")), (1..999).map { first.copy(number="0101"+it.toString().padStart(3,'0')) })
        assertEquals(UploadIssueKind.CAPACITY, p.issues.single().kind)
        assertEquals("", p.rows.single().number)
    }
    @Test fun sameBatchRepeatedInstrumentIsAlsoAConflict() {
        val p = plan(listOf(row("新乐曲"), row("新乐曲")))
        assertEquals(UploadIssueKind.DUPLICATE, p.issues.single().kind)
    }
    @Test fun unspecifiedInstrumentRequiresMetadataRatherThanInventingOne() {
        val p = plan(listOf(row(part = "")))
        assertEquals(UploadIssueKind.METADATA, p.issues.single().kind)
    }
    @Test fun newVersionTitleUsesExplicitVersionAndReceivesANewNumber() {
        assertEquals("天空之城（第二版）", nextUploadVersionTitle("天空之城", listOf(first)))
        val second = first.copy(number = "0101008", title = "天空之城（第二版）")
        assertEquals("天空之城（第三版）", nextUploadVersionTitle(second.title, listOf(first, second)))
        val p = plan(listOf(row(second.title, part = "01 第一小提琴")), distinct = setOf(0))
        assertTrue(p.issues.isEmpty()); assertEquals("0101001", p.rows.single().number)
    }
    @Test fun pendingOrHistoricalReservationsAreSkippedAndCannotBeManuallyReused() {
        val rows=listOf(row("新乐曲"))
        val planned=planUploadNumbers(rows,listOf(first),rules,reservedSequences=setOf(1,2,3,7,999))
        assertTrue(planned.issues.isEmpty());assertEquals("0301004",planned.rows.single().number)
        val manual=planUploadNumbers(listOf(row("新乐曲",number="0301002")),listOf(first),rules,
            manual=setOf(0),reservedSequences=setOf(1,2,3,7,999))
        assertEquals(UploadIssueKind.MANUAL_CONFLICT,manual.issues.single().kind)
        val supplement=planUploadNumbers(listOf(row()),listOf(first),rules,reservedSequences=setOf(1,7))
        assertTrue(supplement.issues.isEmpty());assertEquals("0301007",supplement.rows.single().number)
    }
    @Test fun invalidManualSequenceIsRejected() {
        val p = plan(listOf(row("新乐曲", number = "0301000")), manual = setOf(0))
        assertEquals(UploadIssueKind.MANUAL_CONFLICT, p.issues.single().kind)
    }
}
