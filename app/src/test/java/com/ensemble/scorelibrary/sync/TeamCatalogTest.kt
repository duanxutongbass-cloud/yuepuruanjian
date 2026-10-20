package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TeamCatalogTest {
    private val p=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Synthetic","user","User","member",true)
    private val session=TeamSession(TEAM_SERVICE_URL,"synthetic",Long.MAX_VALUE,p)
    private fun principal()=JSONObject().put("accountId",p.accountId).put("memberId",p.memberId).put("teamId",p.teamId)
        .put("teamName",p.teamName).put("username",p.username).put("displayName",p.name).put("role",p.role).put("backupConsent",true)
    private fun row(number:String="0101001")=JSONObject().put("artifactId",UUID.randomUUID().toString()).put("title","Score")
        .put("partName","Violin").put("hash","a".repeat(64)).put("size",100).put("pageCount",1)
        .put("scoreNumber",number).put("category","Test").put("independentSource",true)
    private fun catalog(vararg rows:JSONObject)=JSONObject().put("principal",principal()).put("revision",1).put("artifacts",JSONArray(rows))
    @Test fun numberGroupsPartsButNameDoesNot() {
        val result=TeamCatalog.parse(catalog(row(),row("0201001").put("partName","Cello"),row("0101002")),session)
        assertEquals(result.artifacts[0].piece,result.artifacts[1].piece)
        assertNotEquals(result.artifacts[0].part,result.artifacts[1].part)
        assertNotEquals(result.artifacts[0].piece,result.artifacts[2].piece)
    }
    @Test fun releasedNumberUsesNewPieceAndVoiceIdentityAndSurvivesOfflineEncoding() {
        val gen=UUID.randomUUID().toString();val first=row();val second=row("0201001")
        val old=TeamCatalog.parse(catalog(first),session).artifacts.single()
        first.put("pieceGeneration",gen);second.put("pieceGeneration",gen)
        val reused=TeamCatalog.parse(catalog(first,second),session)
        assertEquals(reused.artifacts[0].piece,reused.artifacts[1].piece)
        assertNotEquals(old.piece,reused.artifacts[0].piece);assertNotEquals(old.part,reused.artifacts[0].part)
        assertEquals(old.pdf,reused.artifacts[0].pdf) // Artifact identity is independent of numbering identity.
        assertEquals("sequence:001:released:$gen",reused.artifacts[0].pieceKey)
        assertEquals(reused,TeamCatalog.parse(reused.json(session),session))
    }
    @Test fun malformedOrMixedNumberGenerationsAreRejected() {
        listOf(row().put("pieceGeneration","bad"),row().put("pieceGeneration",JSONObject.NULL),
            row("").put("pieceGeneration",UUID.randomUUID().toString())).forEach {
            assertTrue(runCatching{TeamCatalog.parse(catalog(it),session)}.isFailure)
        }
        assertTrue(runCatching{TeamCatalog.parse(catalog(row(),row("0201001").put("pieceGeneration",UUID.randomUUID().toString())),session)}.isFailure)
    }
    @Test fun anotherIdentityAndCombinedSourceAreRejected() {
        val other=catalog(row()).put("principal",principal().put("memberId",UUID.randomUUID().toString()))
        assertTrue(runCatching{TeamCatalog.parse(other,session)}.isFailure)
        assertTrue(runCatching{TeamCatalog.parse(catalog(row().put("independentSource",false)),session)}.isFailure)
        assertTrue(runCatching{TeamCatalog.parse(catalog(row().put("artifactId","../other.pdf")),session)}.isFailure)
    }
    @Test fun duplicatePartOrConflictingNumberDoesNotPickAnArbitraryVersion() {
        assertTrue(runCatching{TeamCatalog.parse(catalog(row(),row()),session)}.exceptionOrNull() is TeamSyncFailure)
        assertTrue(runCatching{TeamCatalog.parse(catalog(row(),row("0201001").put("title","Other")),session)}.exceptionOrNull() is TeamSyncFailure)
        assertTrue(runCatching{TeamCatalog.parse(catalog(row().put("hash","bad")),session)}.isFailure)
    }
    @Test fun frozenProgrammeCheckpointPreservesExactAuthorizedPartsAndSceneIdentity() {
        val a=row();val b=row("0201001");val id=UUID.randomUUID().toString();val freeze=UUID.randomUUID().toString()
        val programme=JSONObject().put("id",id).put("title","Concert").put("pieceKeys",JSONArray().put("sequence:001"))
            .put("freezeId",freeze).put("frozenArtifactIds",JSONArray().put(a.getString("artifactId")))
        val parsed=TeamCatalog.parse(catalog(a,b).put("programs",JSONArray().put(programme)),session)
        assertEquals(parsed,TeamCatalog.parse(parsed.json(session),session))
        assertNotEquals(TeamProgram(id,"Concert",listOf("sequence:001")).concert,parsed.programs.single().concert)
        assertNotEquals(parsed.programs.single().concert,parsed.programs.single().copy(frozenArtifacts=listOf(b.getString("artifactId"))).concert)
        programme.put("frozenArtifactIds",JSONArray().put(UUID.randomUUID().toString()))
        assertTrue(runCatching { TeamCatalog.parse(catalog(a,b).put("programs",JSONArray().put(programme)),session) }.isFailure)
    }
}
