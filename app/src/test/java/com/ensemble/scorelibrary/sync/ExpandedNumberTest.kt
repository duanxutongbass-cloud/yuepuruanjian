package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ExpandedNumberTest {
    @Test fun encryptedOfflineRestorePreservesFullNumbersAndRejectsProtocolDowngrade() {
        fun id()=UUID.randomUUID().toString()
        val root=java.nio.file.Files.createTempDirectory("x-offline-").toFile()
        try {
            val now=System.currentTimeMillis()/1000
            val principal=TeamPrincipal(id(),id(),id(),"Team","director","Director","admin",true,numberingProtocol=2)
            val session=TeamSession(TEAM_SERVICE_URL,"synthetic",now+3600,principal,now,now,id())
            val key=javax.crypto.KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
            val values=listOf("0101999","01-01-1000","01-01-10000","01-01-9223372036854775807").map {
                TeamArtifact(id(),"Work $it","01 Violin","a".repeat(64),10,1,it,"01 Style")
            }
            val catalog=TeamCatalog(3,values)
            TeamOfflineStore(root,{key},{now}).save(session,catalog)
            val restored=TeamOfflineStore(root,{key},{now}).read(session.copy(offline=true)).catalog
            assertEquals(catalog,restored)
            assertEquals(4,restored.artifacts.map { it.piece }.distinct().size)
            assertTrue(runCatching { TeamOfflineStore(root,{key},{now}).read(session.copy(principal=principal.copy(numberingProtocol=1))) }.isFailure)
        } finally { root.deleteRecursively() }
    }
    private val rules=OrganizerRules(mapOf("01" to "Violin","02" to "Cello"),mapOf("01" to "Style"))
    private fun row(title:String="New",part:String="01 Violin",number:String="")=
        TeamPublishDetails(title,part,number,"01 Style",emptySet())
    @Test fun exactNumbersAndFilenameBoundaries() {
        for(n in listOf("999","1000","9999","10000",Long.MAX_VALUE.toString())) {
            val formatted=ScoreNumber.format("01","01",n)
            assertEquals(n,ScoreNumber.parse(formatted).sequence)
            val filename=if(n.length==3) formatted+"Sky.pdf" else "$formatted-Sky.pdf"
            val parsed=parseOrganizerFilename(filename,rules)
            assertNull(parsed.error);assertEquals(n,parsed.sequence)
        }
        for(n in listOf("0101000","01011000","01-01-01000","01-01-9223372036854775808","０1-01-1000","01-01-1e4"," 0101001")) {
            assertTrue(n,runCatching { ScoreNumber.parse(n) }.isFailure)
        }
    }
    @Test fun globalGapCrosses999WithoutReusingReservedOrManualNumbers() {
        val reserved=(1L..999L).toSet()+1001L
        val plan=planUploadNumbers(listOf(row("First"),row("Second")),emptyList(),rules,
            reservedSequences=reserved,maximum=Long.MAX_VALUE)
        assertTrue(plan.issues.toString(),plan.issues.isEmpty())
        assertEquals(listOf("01-01-1000","01-01-1002"),plan.rows.map { it.number })
        val manual=planUploadNumbers(listOf(row("First"),row("Manual",number="0101001")),emptyList(),rules,
            manual=setOf(1),maximum=Long.MAX_VALUE)
        assertEquals(listOf("0101002","0101001"),manual.rows.map { it.number })
    }
    @Test fun supplementationAndRetirementKeepFullSequence() {
        val old=ExistingTeamScore("01-01-10000","Same","01 Style","01 Violin",false)
        val supplement=planUploadNumbers(listOf(row("Same","02 Cello")),listOf(old),rules,maximum=Long.MAX_VALUE)
        assertTrue(supplement.issues.isEmpty());assertEquals("02-01-10000",supplement.rows.single().number)
        val retired=planUploadNumbers(listOf(row(number="01-01-1000")),emptyList(),rules,manual=setOf(0),
            reservedSequences=setOf(1000L),maximum=Long.MAX_VALUE)
        assertEquals(UploadIssueKind.MANUAL_CONFLICT,retired.issues.single().kind)
    }
    @Test fun expandedInventoryIsExactSortedAndCannotTruncate() {
        val root=JSONObject().put("protocolVersion",2).put("revision",3).put("maximumSequence",Long.MAX_VALUE.toString())
            .put("occupiedSequences",JSONArray(listOf("001","999","1000",Long.MAX_VALUE.toString())))
            .put("availableCount",(Long.MAX_VALUE-4).toString()).put("firstFreeSequence","002").put("pendingUploads",0)
        val parsed=TeamNumberInventory.parse(root)
        assertEquals(2L,parsed.firstFree);assertEquals(Long.MAX_VALUE-4,parsed.availableCount)
        assertTrue(runCatching { TeamNumberInventory.parse(JSONObject(root.toString()).put("availableCount",Long.MAX_VALUE-4)) }.isFailure)
        assertTrue(runCatching { TeamNumberInventory.parse(JSONObject(root.toString()).put("occupiedSequences",JSONArray(listOf("001","10000","9999")))) }.isFailure)
    }
    @Test fun authenticatedProtocolIsRememberedAndNeverFallsBackForWrites() {
        fun id()=UUID.randomUUID().toString()
        val principal=TeamPrincipal(id(),id(),id(),"Team","director","Director","admin",true,numberingProtocol=2)
        assertEquals(principal,TeamPrincipal.parse(principal.json()))
        val session=TeamSession(TEAM_SERVICE_URL,"synthetic",Long.MAX_VALUE,principal)
        val paths=mutableListOf<String>()
        val gateway=object:TeamAccountGateway {
            override val server=TEAM_SERVICE_URL;override val device=id()
            override fun restore()=session
            override fun clear() {}
            override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
            override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject {
                paths+=path
                return JSONObject().put("protocolVersion",2).put("teamId",principal.teamId)
                    .put("candidateOnly",true).put("expandedPublicationReady",false)
            }
        }
        assertEquals("/v2/catalog",session.scorePath("/v1/catalog"))
        assertTrue(runCatching { gateway.scoreRequest(session,"/v1/admin/uploads",JSONObject()) }.isFailure)
        assertEquals(listOf("/v2/admin/capabilities"),paths)
    }
}
