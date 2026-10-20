package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import android.os.Looper
import org.robolectric.Shadows
import androidx.lifecycle.ViewModelStore

@RunWith(RobolectricTestRunner::class)
class NumberReleaseTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val db=Room.inMemoryDatabaseBuilder(context,ScoreLibraryDatabase::class.java).build()
    private val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Team","admin","Admin","admin",true,numberingProtocol=2)
    private val session=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,principal)
    private val c=TeamCollection("account-key","member",db,context.cacheDir,Long.MAX_VALUE,session)
    private val storage=UUID.randomUUID().toString()
    @After fun close()=db.close()
    private fun view()=JSONObject().put("protocolVersion",1).put("numberReleaseReady",true).put("storageId",storage)
        .put("teamId",principal.teamId).put("accountId",principal.accountId).put("revision",7)
        .put("eligible",JSONArray().put(JSONObject().put("sequence","001").put("deletedParts",3).put("latestDeletedAt",100)))
    private fun preview()=JSONObject().put("protocolVersion",1).put("storageId",storage).put("accountId",principal.accountId)
        .put("revision",7).put("sequences",JSONArray().put("001")).put("deletedParts",3).put("scopeDigest","a".repeat(64))
    @Test fun authenticatedPreviewPinsSelectionIdentityAndStableRetryRequest() {
        val inventory=NumberReleaseInventory.parse(view(),c);val wanted=mutableSetOf("001")
        val result=NumberReleasePreview.parse(preview(),inventory,wanted);wanted.clear()
        assertEquals(setOf("001"),result.sequences)
        assertEquals(result.body().toString(),result.body().toString())
        assertEquals(true,result.body().get("confirmed"));assertEquals(7L,result.revision)
    }
    @Test fun foreignAccountTeamStorageOrChangedRevisionCannotEnableRelease() {
        for(row in listOf(view().put("accountId",UUID.randomUUID().toString()),view().put("teamId",UUID.randomUUID().toString()),
            view().put("storageId","bad"),view().put("numberReleaseReady",false),view().put("revision",1.2)))
            assertTrue(runCatching{NumberReleaseInventory.parse(row,c)}.isFailure)
        val inventory=NumberReleaseInventory.parse(view(),c)
        for(row in listOf(preview().put("storageId",UUID.randomUUID().toString()),preview().put("accountId",UUID.randomUUID().toString()),
            preview().put("revision",8),preview().put("sequences",JSONArray().put("003")),preview().put("deletedParts",4)))
            assertTrue(runCatching{NumberReleasePreview.parse(row,inventory,setOf("001"))}.isFailure)
    }
    @Test fun malformedDuplicateOrZeroSequencesAreRejected() {
        for(value in listOf("1","000","0001","9223372036854775808")) {
            val row=view();row.getJSONArray("eligible").getJSONObject(0).put("sequence",value)
            assertTrue(runCatching{NumberReleaseInventory.parse(row,c)}.isFailure)
        }
        val row=view();row.getJSONArray("eligible").put(row.getJSONArray("eligible").getJSONObject(0))
        assertTrue(runCatching{NumberReleaseInventory.parse(row,c)}.isFailure)
        val zero=view();zero.getJSONArray("eligible").getJSONObject(0).put("deletedParts",0)
        assertTrue(runCatching{NumberReleaseInventory.parse(zero,c)}.isFailure)
    }
    @Test fun viewModelRejectsCopiedPreviewAndRefreshesInventoryAfterAuthorizedSubmission() {
        val scope=TeamLibraryScope(context);val collection=scope.activate(session);val models=ViewModelStore()
        var released=false;var writes=0;var revision=7
        val gateway=object:TeamAccountGateway {
            override val server=TEAM_SERVICE_URL
            override val device=UUID.randomUUID().toString()
            override fun restore()=session
            override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
            override fun clear() {}
            override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject=when(path) {
                "/v2/admin/capabilities"->JSONObject().put("protocolVersion",2).put("teamId",principal.teamId).put("candidateOnly",false)
                    .put("expandedPublicationReady",true).put("numbering",JSONObject().put("namingVersions",JSONArray().put(1).put(2))
                        .put("maximumSequence",Long.MAX_VALUE.toString()).put("sequenceEncoding","decimal-string").put("expandedSeparator","-"))
                "/v2/admin/number-inventory"->JSONObject().put("protocolVersion",2).put("revision",revision).put("maximumSequence",Long.MAX_VALUE.toString())
                    .put("occupiedSequences",if(released)JSONArray().put("003") else JSONArray().put("001").put("003"))
                    .put("availableCount",(Long.MAX_VALUE-if(released)1 else 2).toString()).put("firstFreeSequence",if(released)"001" else "002").put("pendingUploads",0)
                "/v2/admin/number-releases"->view().put("revision",revision).also { if(released)it.put("eligible",JSONArray()) }
                "/v2/admin/number-releases/preview"->preview()
                "/v2/admin/number-releases/commit"->{
                    require(body!!.getJSONArray("sequences").toString()=="[\"001\"]" && body.get("confirmed")==true)
                    writes++;released=true;revision=8
                    JSONObject().put("ok",true).put("requestId",body.getString("requestId")).put("sequences",JSONArray().put("001")).put("releasedCount",1).put("revision",8)
                }
                "/v2/admin/ensemble"->JSONObject().put("revision",revision).put("groups",JSONArray()).put("programs",JSONArray())
                else->error(path)
            }
        }
        val vm=TeamEnsembleViewModel(scope,gateway);models.put("number-release",vm)
        fun waitUntil(test:()->Boolean) {
            val end=System.currentTimeMillis()+15000
            while(!test() && System.currentTimeMillis()<end) { Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
            assertTrue(test())
        }
        try {
            waitUntil { vm.state.value.key==collection.key }
            vm.checkNumbers();waitUntil { !vm.state.value.busy && vm.state.value.numberRelease!=null }
            vm.previewNumberRelease(setOf("001"));waitUntil { !vm.state.value.busy && vm.state.value.numberReleasePreview!=null }
            val accepted=vm.state.value.numberReleasePreview!!
            vm.confirmNumberRelease(accepted.copy());waitUntil { !vm.state.value.busy }
            assertEquals(0,writes)
            vm.confirmNumberRelease(accepted);waitUntil { !vm.state.value.busy && writes==1 }
            assertNull(vm.state.value.numberReleasePreview);assertEquals(1L,vm.state.value.numberInventory!!.firstFree)
            assertEquals(1L,vm.state.value.changes);assertEquals(8L,vm.state.value.revision)
        } finally {
            models.clear();scope.deactivate();Shadows.shadowOf(Looper.getMainLooper()).idle();collection.database.close()
        }
    }
}
