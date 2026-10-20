package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Python v4 storage/v2 HTTP and Android upload/download/Room/freeze consumers. */
@RunWith(RobolectricTestRunner::class)
class ExpandedHttpTest {
    @Test fun fullNumbersCrossHttpAndRoom()=runBlocking {
        fun id()=UUID.randomUUID().toString()
        val source=listOf(File("../team-service/expanded_interop_fixture.py"),File("team-service/expanded_interop_fixture.py")).first { it.isFile }.canonicalFile
        val root=Files.createTempDirectory("x-http-").toFile()
        val process=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python",source.path,root.path)
            .redirectOutput(File(root,"out.log")).redirectError(File(root,"err.log")).start()
        val context=ApplicationProvider.getApplicationContext<Context>()
        val scope=TeamLibraryScope(context)
        var collection:TeamCollection?=null
        try {
            val ready=File(root,"ready-port");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90)
            while(!ready.exists() && process.isAlive && System.nanoTime()<deadline) Thread.sleep(50)
            check(ready.exists()) { File(root,"err.log").readText().take(1500) }
            val server="http://127.0.0.1:${ready.readText().trim()}";val device=id();val api=TeamAccountApi()
            val session=api.login(server,device,"Expanded client","director","synthetic rehearsal passphrase")
            assertEquals(2,session.principal.numberingProtocol)
            val paths=mutableListOf<String>()
            val gateway=object:TeamAccountGateway {
                override val server=server;override val device=device
                override fun restore()=session
                override fun clear() {}
                override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
                override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject {
                    paths+=path
                    return api.request(server,path,device,session,body)
                }
            }
            val publisher=TeamPublisher(gateway);val file=File(root,"source.pdf")
            val numbers=listOf("0101999","01-01-1000","01-01-10000","01-01-9223372036854775807")
            val key=teamId(session.server+"/"+session.principal.teamId+"/"+session.principal.accountId)
            val jobs=numbers.map { number -> TeamPublishJob(key,file,"Synthetic.pdf",TeamLibrarySync.hash(file),file.length(),1,
                TeamPublishDetails("Work $number","01 Violin",number,"01 Style",setOf(session.principal.accountId))) }
            publisher.checkNewBatch(session,jobs)
            publisher.publishBatch(session,jobs,progress={_,_->})
            val client=TeamScoreClient(gateway)
            val catalog=client.catalog(session)
            assertEquals(numbers.toSet(),catalog.artifacts.map { it.number }.toSet())
            assertEquals(4,catalog.artifacts.map { it.piece }.distinct().size)
            assertEquals(setOf(999L,1000L,10000L,Long.MAX_VALUE),publisher.inventory(session).occupied)
            val program=id()
            gateway.scoreRequest(session,"/v1/admin/programs",JSONObject().put("id",program).put("title","Synthetic programme")
                .put("pieceKeys",JSONArray(catalog.artifacts.map { it.pieceKey })).put("accountIds",JSONArray(listOf(session.principal.accountId)))
                .put("archived",false).put("expectedRevision",0))
            gateway.scoreRequest(session,"/v1/admin/program-freezes",JSONObject().put("programId",program).put("requestId",id()).put("expectedRevision",1))
            val frozen=client.catalog(session)
            assertNotNull(client.frozen(session,frozen.programs.single(),frozen).program.freezeId)
            val verifier=object:TeamPdfVerifier {
                override fun verify(file:File,pages:Int) { require(pages==1 && file.readBytes().take(5)=="%PDF-".toByteArray().toList()) }
            }
            val sync=TeamLibrarySync(scope,client,verifier)
            sync.activate(session);collection=scope.active.value!!
            assertEquals(4,sync.sync(session) { _,_-> })
            val data=collection!!.database.resolutionDao().snapshot()
            assertEquals(setOf("999","1000","10000",Long.MAX_VALUE.toString()),data.pieces.map { it.organizerSequence }.toSet())
            assertTrue(data.pieces.all { it.categoryCode=="01" })
            assertTrue(data.versions.all { TeamLibrarySync.hash(File(it.managedFilePath))==TeamLibrarySync.hash(file) })
            assertTrue(paths.any { it=="/v2/admin/uploads" })
            assertFalse(paths.any { it=="/v1/admin/uploads" || it=="/v1/catalog" })
        } finally {
            scope.deactivate();collection?.let { it.database.close();it.directory.parentFile?.deleteRecursively() }
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS)}
            root.deleteRecursively()
        }
    }
}
