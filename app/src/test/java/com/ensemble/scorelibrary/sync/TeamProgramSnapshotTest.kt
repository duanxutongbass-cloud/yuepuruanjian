package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TeamProgramSnapshotTest {
    private fun id()=UUID.randomUUID().toString()
    private val member=id()
    private val a=TeamArtifact(id(),"Score","Violin","a".repeat(64),123,2,"0101001","Category")
    private val b=a.copy(id=id(),partName="Cello",number="0201001")
    private val program=TeamProgram(id(),"Concert",listOf(a.pieceKey),id(),listOf(a.id))
    private val catalog=TeamCatalog(1,listOf(a,b),listOf(program))
    private val mark=InkMark(kind=MarkKind.SYMBOL,points=listOf(InkPoint(.2f,.3f)),symbolScale=.75f)
    private fun body()=JSONObject().put("protocolVersion",1).put("programId",program.id).put("freezeId",program.freezeId)
        .put("programRevision",1).put("title",program.title).put("pieceKeys",JSONArray(program.pieceKeys))
        .put("artifacts",JSONArray().put(JSONObject().put("artifactId",a.id).put("hash",a.hash).put("size",a.size).put("pageCount",a.pages)
            .put("title",a.title).put("partName",a.partName).put("scoreNumber",a.number).put("category",a.category).put("pieceKey",a.pieceKey)))
        .put("pages",JSONArray().put(JSONObject().put("artifactId",a.id).put("pageIndex",1).put("revision",3)
            .put("marks",JSONArray(InkCodec.encode(listOf(mark))))))
    private fun reply(body:JSONObject):JSONObject {
        val payload=body.toString()
        val checksum=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        return JSONObject().put("protocolVersion",1).put("programId",program.id).put("freezeId",program.freezeId).put("sha256",checksum).put("payload",payload)
    }
    private fun data()=LibrarySnapshot(
        pieces=listOf(PieceEntity(a.piece,a.title)),definitions=listOf(PartDefinitionEntity(a.definition,a.partName),PartDefinitionEntity(b.definition,b.partName)),
        parts=listOf(PiecePartEntity(a.part,a.piece,a.definition),PiecePartEntity(b.part,b.piece,b.definition)),
        pdfs=listOf(a,b).map { ScorePdfEntity(it.pdf,it.title,it.version) },
        versions=listOf(a,b).map { ScorePdfVersionEntity(it.version,it.pdf,1,"synthetic","team-artifact:${it.id}",it.hash,it.size,it.pages) },
        bindings=listOf(a,b).map { PartScoreBindingEntity(teamId("binding/${it.id}"),it.part,null,it.pdf,1,it.pages) },
        members=listOf(MemberEntity(member,"Player")),
        extraPermissions=listOf(a,b).map { MemberPiecePartPermissionEntity(id(),member,it.part) },
        concerts=listOf(ConcertEntity(program.concert,program.title)),items=listOf(ConcertItemEntity(program.item(a.pieceKey),program.concert,a.piece,0)))
    @Test fun persistentSnapshotUsesOnlyFrozenVoicesAndKeepsStampSize() {
        val frozen=TeamProgramSnapshot.parse(reply(body()),program,catalog)
        val raw=data();val snapshot=frozen.entity(raw,member)
        assertTrue(TeamProgramSnapshot.matches(snapshot,program,catalog,member))
        val projected=TeamProgramSnapshot.project(raw,snapshot,member)
        val concert=(ScoreResolver(projected).resolveConcert(member,program.concert) as DomainResult.Success).value
        assertEquals(a.part,concert.items.single().part.id)
        assertEquals(a.version,concert.items.single().segments.single().versionId)
        assertEquals(2,raw.parts.size)
        assertEquals(listOf(mark),InkCodec.decode(JSONObject(snapshot.payload).getString("${a.version}:1")))
        assertEquals("[]",JSONObject(snapshot.payload).getString("${a.version}:0"))
    }
    @Test fun corruptHashWrongPageUnauthorizedInkAndWrongFileAreRejected() {
        assertTrue(runCatching { TeamProgramSnapshot.parse(reply(body()).put("sha256","0".repeat(64)),program,catalog) }.isFailure)
        for(mutation in listOf<(JSONObject)->Unit>(
            { it.getJSONArray("artifacts").getJSONObject(0).put("hash","b".repeat(64)) },
            { it.getJSONArray("pages").getJSONObject(0).put("artifactId",b.id) },
            { it.getJSONArray("pages").getJSONObject(0).put("pageIndex",2) },
            { it.getJSONArray("pages").put(it.getJSONArray("pages").getJSONObject(0)) })) {
            val body=body();mutation(body)
            assertTrue(runCatching { TeamProgramSnapshot.parse(reply(body),program,catalog) }.isFailure)
        }
    }
    @Test fun cachedSnapshotDoesNotGrantRevokedPartOrPermitDifferentProgramme() {
        val raw=data();val snapshot=TeamProgramSnapshot.parse(reply(body()),program,catalog).entity(raw,member)
        val revoked=raw.copy(extraPermissions=raw.extraPermissions.map { it.copy(isArchived=it.piecePartId==a.part) })
        assertTrue(ScoreResolver(TeamProgramSnapshot.project(revoked,snapshot,member)).resolveConcert(member,program.concert) is DomainResult.Failure)
        assertFalse(TeamProgramSnapshot.matches(snapshot,program.copy(freezeId=id()),catalog,member))
        assertFalse(TeamProgramSnapshot.matches(snapshot,program,catalog,id()))
        assertTrue(runCatching { TeamProgramSnapshot.project(raw,snapshot.copy(payload="{}"),member) }.isFailure)
        assertTrue(runCatching { TeamProgramSnapshot.project(raw.copy(items=raw.items.map { it.copy(sortOrder=2) }),snapshot,member) }.isFailure)
    }
    @Test fun explicitReadingPartOutsideFrozenSceneIsNotSilentlyReplaced() {
        val raw=data().let { it.copy(members=it.members.map { m -> m.copy(defaultPartDefinitionId=b.definition) }) }
        val snapshot=TeamProgramSnapshot.parse(reply(body()),program,catalog).entity(raw,member)
        assertTrue(ScoreResolver(TeamProgramSnapshot.project(raw,snapshot,member)).resolveConcert(member,program.concert) is DomainResult.Failure)
    }
}
