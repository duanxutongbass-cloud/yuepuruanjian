package com.ensemble.scorelibrary.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.sync.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AccountTeamsInteropTest {
    // Native Windows SQLite also includes this method name in its temp path.
    @Test fun migrateAndSwitch()=runBlocking {
        val source=listOf(File("../team-service/multiteam_fixture.py"),File("team-service/multiteam_fixture.py")).first {it.isFile}.canonicalFile
        val root=Files.createTempDirectory("multi-team-http-").toFile();val python=System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python"
        val process=ProcessBuilder(python,source.path,root.path).redirectOutput(File(root,"stdout.log")).redirectError(File(root,"stderr.log")).start()
        val scope=TeamLibraryScope(ApplicationProvider.getApplicationContext<Context>());val collections=mutableListOf<TeamCollection>()
        try {
            val ready=File(root,"ready.json");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
            while(!ready.exists() && process.isAlive && System.nanoTime()<deadline)Thread.sleep(50)
            check(ready.exists()) {"Synthetic multi-team fixture failed: "+File(root,"stderr.log").readText().take(2500)}
            val json=JSONObject(ready.readText());val server="http://127.0.0.1:"+json.getInt("port");val device=UUID.randomUUID().toString();val api=TeamAccountApi()
            val first=api.login(server,device,"Synthetic multi pad","director","synthetic rehearsal passphrase").copy(deviceId=device)
            assertEquals(0,first.principal.multiTeamProtocol)
            val gateway=object:TeamAccountGateway {
                override val server=server;override val device=device
                override fun restore()=first
                override fun clear(){}
                override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=first
                override fun request(session:TeamSession,path:String,body:JSONObject?)=api.request(server,path,device,session,body)
            }
            val client=TeamScoreClient(gateway)
            val verifier=object:TeamPdfVerifier {
                override fun verify(file:File,pages:Int) {
                    val validation=ProcessBuilder(python,"-c","from pypdf import PdfReader;import sys;assert len(PdfReader(sys.argv[1]).pages)==int(sys.argv[2])",file.path,pages.toString()).start()
                    try {check(validation.waitFor(20,TimeUnit.SECONDS) && validation.exitValue()==0)} finally {if(validation.isAlive)validation.destroyForcibly()}
                }
            }
            val sync=TeamLibrarySync(scope,client,verifier);sync.activate(first);val a=checkNotNull(scope.active.value);collections+=a
            assertEquals(1,sync.sync(first){_,_->});val firstPdf=client.catalog(first).artifacts.single()
            val firstFile=a.database.resolutionDao().snapshot().versions.single().managedFilePath!!.let(::File)
            val hash=TeamLibrarySync.hash(firstFile);assertEquals(firstPdf.hash,hash)
            val before=checkNotNull(HttpTeamDeletions().page(first,TeamDeletionCheckpoint()))
            File(root,"migrate.request").writeText("Synthetic migration requested")
            val migrated=File(root,"migrated.json");val migrationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
            while(!migrated.exists() && !File(root,"migration-error.log").exists() && process.isAlive && System.nanoTime()<migrationDeadline)Thread.sleep(50)
            check(migrated.exists()) {"Synthetic migration failed: "+File(root,"migration-error.log").takeIf {it.exists()}?.readText().orEmpty().take(2500)}
            val changed=api.request(server,"/v1/me",device,first)
            val verified=first.copy(principal=TeamPrincipal.parse(changed.getJSONObject("principal")),expiresAt=changed.getLong("expiresAt"))
            assertEquals(first.principal.accountId,verified.principal.accountId);assertEquals(first.principal.teamId,verified.principal.teamId)
            assertEquals(1,verified.principal.multiTeamProtocol)
            val rebound=checkNotNull(HttpTeamDeletions().page(verified,TeamDeletionCheckpoint(storage=before.storage,cursor=before.next)))
            assertNotEquals(before.storage,rebound.storage)
            sync.activate(verified);collections+=checkNotNull(scope.active.value)
            assertEquals(1,sync.sync(verified){_,_->});assertEquals(hash,TeamLibrarySync.hash(firstFile))
            val teams=parseAccountTeams(api.request(server,"/v1/me/teams",device,verified),verified);assertEquals(2,teams.size)
            sync.deactivate()
            val result=api.request(server,"/v1/me/teams/switch",device,verified,JSONObject().put("teamId",JSONObject(migrated.readText()).getString("second")))
            val second=TeamSession(server,result.getString("token"),result.getLong("expiresAt"),TeamPrincipal.parse(result.getJSONObject("principal")),deviceId=device)
            assertEquals(first.principal.accountId,second.principal.accountId)
            assertTrue(runCatching {client.catalog(first)}.exceptionOrNull() is AccountRejected)
            sync.activate(second);val b=checkNotNull(scope.active.value);collections+=b
            assertEquals(1,sync.sync(second){_,_->});assertNotEquals(a.key,b.key);assertNotSame(a.database,b.database)
            assertEquals("001",a.database.resolutionDao().pieces().single().organizerSequence)
            assertEquals("001",b.database.resolutionDao().pieces().single().organizerSequence)
            assertEquals("多团队合成谱乙",b.database.resolutionDao().pieces().single().title)
            assertTrue(runCatching {scope.requireCurrent(a)}.isFailure)
            assertEquals(hash,TeamLibrarySync.hash(firstFile));assertNotEquals(firstPdf.hash,client.catalog(second).artifacts.single().hash)
        } finally {
            scope.deactivate();collections.distinctBy {it.database}.forEach {it.database.close()}
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS)}
            check(root.canonicalFile.parentFile==File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile);root.deleteRecursively()
        }
    }
}
