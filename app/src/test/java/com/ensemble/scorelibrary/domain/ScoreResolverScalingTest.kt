package com.ensemble.scorelibrary.domain

import com.ensemble.scorelibrary.core.database.entity.*
import org.junit.Assert.*
import org.junit.Test

class ScoreResolverScalingTest {
    private class Counted<T>(private val rows:List<T>):AbstractList<T>() {
        var reads=0L
        override val size get()=rows.size
        override fun get(index:Int):T { reads++;return rows[index] }
    }
    @Test fun libraryProjectionDoesNotRescanEveryPermissionAndPartForEveryPiece() {
        val pieces=Counted((0 until 200).map { PieceEntity(id="p$it",title="Synthetic $it") })
        val parts=Counted((0 until 200).flatMap { p -> (0 until 8).map { d ->
            PiecePartEntity(id="p$p-d$d",pieceId="p$p",partDefinitionId="d$d") } })
        val grants=Counted(parts.map { MemberPiecePartPermissionEntity(memberId="m",piecePartId=it.id) })
        parts.reads=0
        val data=LibrarySnapshot(pieces=pieces,parts=parts,extraPermissions=grants,
            definitions=(0 until 8).map { PartDefinitionEntity(id="d$it",name="Part $it") },
            members=listOf(MemberEntity(id="m",displayName="Synthetic")))
        val start=System.nanoTime()
        val result=ScoreResolver(data).dashboard("m") as DomainResult.Success
        assertEquals(200,result.value.pieces.size)
        assertTrue(result.value.pieces.all { it.partNames.size==8 })
        val reads=pieces.reads+parts.reads+grants.reads
        println("LIBRARY_200_PIECES_1600_PARTS reads=$reads elapsedMs=${(System.nanoTime()-start)/1_000_000}")
        assertTrue("Projection must scale with input size; observed $reads source-row visits",reads<20_000)
        val revoked=(ScoreResolver(data.copy(extraPermissions=emptyList())).dashboard("m") as DomainResult.Success).value
        assertTrue(revoked.pieces.all { it.partNames.isEmpty() })
    }
}
