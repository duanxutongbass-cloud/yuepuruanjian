package com.ensemble.scorelibrary.domain

import com.ensemble.scorelibrary.core.database.entity.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConcertAudienceTest {
    private val member=MemberEntity(id="m",displayName="Member",defaultPartDefinitionId="violin")
    @Test fun audienceSupportsExplicitMembersOrDefaultPartsAndEmptyMeansNobody() {
        val c=ConcertEntity(id="c",title="Concert")
        assertTrue(concertAudienceAllows(c,member))
        assertTrue(concertAudienceAllows(c.copy(audienceMembers="[\"m\"]",audienceParts="[]"),member))
        assertTrue(concertAudienceAllows(c.copy(audienceMembers="[]",audienceParts="[\"violin\"]"),member))
        assertFalse(concertAudienceAllows(c.copy(audienceMembers="[]",audienceParts="[]"),member))
        assertFalse(concertAudienceAllows(c.copy(audienceMembers="[\"other\"]"),member))
        assertFalse(concertAudienceAllows(c.copy(audienceParts="[\"violin\"]"),member.copy(defaultPartDefinitionId=null)))
    }
    @Test fun hiddenConcertCannotBeOpenedByDirectIdAndAudienceNeverGrantsScores() {
        val c=ConcertEntity(id="c",title="Concert",audienceMembers="[]",audienceParts="[]")
        val data=LibrarySnapshot(members=listOf(member),concerts=listOf(c))
        assertTrue((ScoreResolver(data).dashboard("m") as DomainResult.Success).value.concerts.isEmpty())
        assertEquals(ErrorCode.UNAUTHORIZED,(ScoreResolver(data).resolveConcert("m","c") as DomainResult.Failure).error.code)
        val visible=data.copy(concerts=listOf(c.copy(audienceMembers="[\"m\"]")),
            pieces=listOf(PieceEntity(id="p",title="Piece")),definitions=listOf(PartDefinitionEntity(id="violin",name="Violin")),
            parts=listOf(PiecePartEntity(id="part",pieceId="p",partDefinitionId="violin")),
            items=listOf(ConcertItemEntity(id="i",concertId="c",pieceId="p",sortOrder=0)))
        assertEquals(ErrorCode.UNAUTHORIZED,(ScoreResolver(visible).resolveConcert("m","c") as DomainResult.Failure).error.code)
    }
    @Test fun malformedAudienceCannotActAsAllMembers() {
        assertThrows(Exception::class.java) { audienceIds("[12]") }
        assertThrows(Exception::class.java) { audienceIds("not json") }
    }
}
