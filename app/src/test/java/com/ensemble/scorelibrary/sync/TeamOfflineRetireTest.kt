package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class TeamOfflineRetireTest {
    @Test fun reusedNumberSurvivesOfflineWhileOldArtifactRemainsRetiredAcrossReopen() {
        val old=a("0101001");store().save(s,TeamCatalog(1,listOf(old)));store().retire(s,setOf(old.id)) {}
        val reused=a("0101001").copy(pieceGeneration=UUID.randomUUID().toString())
        store().save(s,TeamCatalog(3,listOf(old,reused)))
        val restored=store().read(s).catalog.artifacts.single()
        assertEquals(reused,restored);assertNotEquals(old.piece,restored.piece)
        store().retire(s,setOf(old.id)) {}
        assertEquals(listOf(reused),store().read(s).catalog.artifacts)
    }
    @get:Rule val temporary=TemporaryFolder()
    private val key=SecretKeySpec(ByteArray(32) { 4 },"AES")
    private val now=System.currentTimeMillis()/1000
    private val s=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),now+3600,TeamPrincipal(
        UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
        "Team","player","Player","member",true),now,now,UUID.randomUUID().toString())
    private fun store()=TeamOfflineStore(temporary.root,{key},{now})
    private fun a(n:String)=TeamArtifact(UUID.randomUUID().toString(),"Score","Violin","a".repeat(64),100,1,n,"")
    private fun checkpoint()=AccountVault(java.io.File(temporary.root,
        "team-libraries/${teamId("${s.server}/${s.principal.teamId}/${s.principal.accountId}")}/authorization.enc"),5*1024*1024) { key }
    @Test fun unmarkedLegacyCheckpointKeepsOriginalProtocol() {
        val gone=a("0101001");val keep=a("0101002");store().save(s,TeamCatalog(8,listOf(gone,keep)))
        val old=org.json.JSONObject(checkpoint().read()!!)
        old.getJSONObject("catalog").remove("protocolVersion")
        old.getJSONObject("catalog").getJSONObject("principal").remove("numberingProtocol")
        checkpoint().write(old.toString())
        val upgraded=s.copy(principal=s.principal.copy(numberingProtocol=2))
        store().retire(upgraded,setOf(gone.id)) {}
        assertEquals(listOf(keep),store().read(s).catalog.artifacts)
        assertEquals(1,org.json.JSONObject(checkpoint().read()!!).getJSONObject("catalog").getJSONObject("principal").getInt("numberingProtocol"))
    }
    @Test fun foreignCatalogStillDeniedAndOriginalCiphertextKept() {
        val gone=a("0101001");store().save(s,TeamCatalog(1,listOf(gone)))
        val corrupt=org.json.JSONObject(checkpoint().read()!!)
        corrupt.getJSONObject("catalog").getJSONObject("principal").put("accountId",UUID.randomUUID().toString())
        checkpoint().write(corrupt.toString());val raw=checkpoint().read()
        val upgraded=s.copy(principal=s.principal.copy(numberingProtocol=2))
        assertThrows(IllegalArgumentException::class.java) { store().retire(upgraded,setOf(gone.id)) {} }
        assertEquals(raw,checkpoint().read())
        store().save(upgraded,TeamCatalog(2,listOf(gone)))
        assertTrue(store().read(upgraded).catalog.artifacts.isEmpty()) // Retirement barrier survived refusal.
    }
    @Test fun reverseProtocolIsDeniedWithoutRewritingCheckpoint() {
        val expanded=s.copy(principal=s.principal.copy(numberingProtocol=2))
        val gone=a("01-01-1000");val keep=a("01-01-9223372036854775807")
        store().save(expanded,TeamCatalog(2,listOf(gone,keep)));val before=checkpoint().read()
        assertThrows(IllegalArgumentException::class.java) { store().retire(s,setOf(gone.id)) {} }
        assertEquals(before,checkpoint().read())
        store().retire(expanded,setOf(gone.id)) {}
        assertEquals(listOf(keep),store().read(expanded).catalog.artifacts)
        assertEquals("9223372036854775807",org.json.JSONObject(checkpoint().read()!!).getJSONObject("catalog").getJSONArray("artifacts").getJSONObject(0).get("sequence"))
    }
    @Test fun legacyCheckpointRetiresAfterProtocolUpgradeWithoutGrantingNewProtocol() {
        val gone=a("0101001");val keep=a("0101002")
        val program=TeamProgram(UUID.randomUUID().toString(),"Programme",listOf(gone.pieceKey,keep.pieceKey))
        val catalog=TeamCatalog(7,listOf(gone,keep),listOf(program))
        store().save(s,catalog)
        val upgraded=s.copy(principal=s.principal.copy(numberingProtocol=2))
        // A live catalog remains strictly bound to the current protocol.
        assertThrows(IllegalArgumentException::class.java) { TeamCatalog.parse(catalog.json(s),upgraded) }
        store().retire(upgraded,setOf(gone.id)) {}
        val remaining=store().read(s).catalog
        assertEquals(listOf(keep),remaining.artifacts)
        assertEquals(listOf(program.copy(pieceKeys=listOf(keep.pieceKey))),remaining.programs)
        // Retry after restart neither resurrects retired IDs nor upgrades a stale grant.
        store().retire(upgraded,setOf(gone.id)) {}
        assertEquals(remaining,store().read(s).catalog)
        val root=java.io.File(temporary.root,"team-libraries/${teamId("${s.server}/${s.principal.teamId}/${s.principal.accountId}")}")
        val raw=AccountVault(java.io.File(root,"authorization.enc"),5*1024*1024) { key }.read()!!
        val stored=org.json.JSONObject(raw).getJSONObject("catalog")
        assertEquals(1,stored.getJSONObject("principal").getInt("numberingProtocol"))
        assertThrows(IllegalArgumentException::class.java) { TeamCatalog.parse(stored,upgraded) }
    }
    @Test fun lateSave() {
        val gone=a("0101001");val keep=a("0101002")
        val ordinary=TeamProgram(UUID.randomUUID().toString(),"Programme",listOf(gone.pieceKey,keep.pieceKey))
        val frozen=TeamProgram(UUID.randomUUID().toString(),"Frozen",listOf(gone.pieceKey),UUID.randomUUID().toString(),listOf(gone.id))
        val c=TeamCatalog(1,listOf(gone,keep),listOf(ordinary,frozen))
        store().save(s,c);store().retire(s,setOf(gone.id)) {}
        store().save(s,c)
        val result=store().read(s.copy(offline=true)).catalog
        assertEquals(listOf(keep),result.artifacts)
        assertEquals(listOf(ordinary.copy(pieceKeys=listOf(keep.pieceKey))),result.programs)
        store().invalidate(s);store().save(s,c)
        assertEquals(listOf(keep),store().read(s).catalog.artifacts)
    }
    @Test fun isolationAndDenial() {
        val a=a("0101001");val c=TeamCatalog(1,listOf(a))
        val other=s.copy(principal=s.principal.copy(accountId=UUID.randomUUID().toString()))
        store().save(s,c);store().save(other,c)
        assertThrows(IllegalStateException::class.java) { store().retire(s,setOf(a.id)) { error("changed") } }
        assertEquals(c,store().read(s).catalog)
        store().retire(s,setOf(a.id)) {}
        assertTrue(store().read(s).catalog.artifacts.isEmpty())
        assertEquals(c,store().read(other).catalog)
    }
}
