package com.ensemble.scorelibrary.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.sync.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class MembershipInteropTest {
    @Test fun signupStatuses() {
        // The frozen 0.10.10 backend was independently exercised over real HTTP:
        // unknown signup returns 401/session_invalid without creating an account.
        // This bounded wire fixture also covers a router returning 404 and a TLS
        // policy failure that must retain its original error.
        val directory=Files.createTempDirectory("signup-status-").toFile()
        val program="""
            import json,sys
            from pathlib import Path
            from http.server import HTTPServer,BaseHTTPRequestHandler
            class Handler(BaseHTTPRequestHandler):
                index=0
                def log_message(self,*args):pass
                def do_POST(self):
                    length=int(self.headers.get('Content-Length','0'))
                    assert self.path=='/v1/auth/signup' and 0<length<=16384
                    self.rfile.read(length)
                    status,code=[(401,'session_invalid'),(404,'not_found'),(403,'https_required')][Handler.index]
                    Handler.index+=1
                    payload=json.dumps(dict(error=code)).encode()
                    self.send_response(status)
                    self.send_header('Content-Type','application/json')
                    self.send_header('Content-Length',str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
            server=HTTPServer(('127.0.0.1',0),Handler)
            root=Path(sys.argv[1]);pending=root/'ready.pending'
            pending.write_text(str(server.server_port),encoding='ascii');pending.replace(root/'ready')
            server.serve_forever()
        """.trimIndent()
        val python=System.getenv("TEAM_SERVICE_TEST_PYTHON")?:"python"
        val process=ProcessBuilder(python,"-c",program,directory.path).redirectOutput(File(directory,"stdout.log"))
            .redirectError(File(directory,"stderr.log")).start()
        try {
            val ready=File(directory,"ready");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!ready.exists()&&process.isAlive&&System.nanoTime()<deadline)Thread.sleep(30)
            check(ready.exists()){"Signup protocol fixture did not start"}
            val server="http://127.0.0.1:"+ready.readText().trim();val api=TeamAccountApi();val device=UUID.randomUUID().toString()
            repeat(2) {
                val error=runCatching{api.signup(server,device,"Synthetic pad","new-player","synthetic password","新成员",false)}
                    .exceptionOrNull() as AccountRejected
                assertEquals("registration_unavailable",error.code)
                assertTrue(accountError(error).contains("尚未开放"))
            }
            val transport=runCatching{api.signup(server,device,"Synthetic pad","new-player","synthetic password","新成员",false)}
                .exceptionOrNull() as AccountRejected
            assertEquals(403,transport.status);assertEquals("https_required",transport.code)
        } finally {
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS)}
            check(directory.canonicalFile.parentFile==File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
            directory.deleteRecursively()
        }
    }
    @Test fun realHttpReviewAndRoom()=runBlocking {
        val fixture=listOf(File("../team-service/interop_fixture.py"),File("team-service/interop_fixture.py")).first{it.isFile}.canonicalFile
        val directory=Files.createTempDirectory("membership-http-").toFile()
        val python=System.getenv("TEAM_SERVICE_TEST_PYTHON")?:"python"
        val process=ProcessBuilder(python,fixture.path,directory.path).redirectOutput(File(directory,"stdout.log"))
            .redirectError(File(directory,"stderr.log")).start()
        val context=ApplicationProvider.getApplicationContext<Context>();val scope=TeamLibraryScope(context)
        var collection:TeamCollection?=null
        try {
            val ready=File(directory,"ready-port");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!ready.exists()&&process.isAlive&&System.nanoTime()<deadline)Thread.sleep(30)
            check(ready.exists()){"Real membership fixture did not start"}
            val server="http://127.0.0.1:"+ready.readText().trim();val device=UUID.randomUUID().toString();val api=TeamAccountApi()
            val admin=api.login(server,device,"Synthetic admin","director","synthetic rehearsal passphrase")
            val applicant=api.signup(server,device,"Synthetic applicant","new-player","synthetic rehearsal passphrase","新成员",false)
            assertEquals("unjoined",applicant.principal.membership);assertTrue(applicant.principal.membershipSupported)
            assertFalse(applicant.principal.backupConsent)
            assertTrue(runCatching{scope.activate(applicant)}.isFailure);assertNull(scope.active.value)
            val root=File(context.noBackupFilesDir,"team-libraries/"+teamId("$server/${applicant.principal.teamId}/${applicant.principal.accountId}"))
            assertFalse(root.exists())
            val denied=runCatching{api.request(server,"/v1/catalog",device,applicant)}.exceptionOrNull() as AccountRejected
            assertEquals("membership_required",denied.code)
            val code=api.request(server,"/v1/admin/join-code",device,admin,JSONObject().put("baseRevision",0)).getString("code")
            val apply=JSONObject().put("requestId",UUID.randomUUID().toString()).put("baseRevision",0).put("code",code)
            assertEquals("pending",TeamMembership.parse(api.request(server,"/v1/me/membership",device,applicant,apply)).state)
            val row=api.request(server,"/v1/admin/join-requests",device,admin).getJSONArray("requests").getJSONObject(0)
            api.request(server,"/v1/admin/join-requests/review",device,admin,JSONObject().put("requestId",UUID.randomUUID().toString())
                .put("accountId",row.getString("accountId")).put("baseRevision",row.getLong("revision")).put("approved",true))
            // The old unjoined principal cannot activate even after server approval; refresh identity first.
            assertTrue(runCatching{scope.activate(applicant)}.isFailure)
            val identity=api.request(server,"/v1/me",device,applicant)
            val joined=applicant.copy(principal=TeamPrincipal.parse(identity.getJSONObject("principal")),expiresAt=identity.getLong("expiresAt"))
            assertTrue(joined.principal.joined);assertEquals("member",joined.principal.role)
            val gateway=object:TeamAccountGateway {
                override val server=server;override val device=device
                override fun restore()=joined
                override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=joined
                override fun request(session:TeamSession,path:String,body:JSONObject?)=api.request(server,path,device,session,body)
                override fun clear(){}
            }
            val sync=TeamLibrarySync(scope,TeamScoreClient(gateway),object:TeamPdfVerifier {
                override fun verify(file:File,pages:Int){error("An approved account has not been assigned any PDF")}
            })
            sync.activate(joined);collection=scope.active.value!!
            assertTrue(runCatching { sync.activate(applicant) }.isFailure)
            assertNull(scope.active.value);assertNull(sync.downloads.value.key)
            sync.activate(joined);collection=scope.active.value!!
            assertTrue(runCatching { scope.activate(applicant) }.isFailure)
            assertNull(scope.active.value)
            sync.activate(joined);collection=scope.active.value!!
            assertTrue(runCatching { sync.sync(applicant){_,_->} }.isFailure)
            assertNull(scope.active.value)
            sync.activate(joined);collection=scope.active.value!!
            assertEquals(0,sync.sync(joined){_,_->})
            assertTrue(collection.database.resolutionDao().pieces().isEmpty())
            assertTrue(collection.database.resolutionDao().snapshot().extraPermissions.isEmpty())
            api.request(server,"/v1/auth/logout",device,joined,JSONObject())
            assertEquals(401,(runCatching{api.request(server,"/v1/me",device,joined)}.exceptionOrNull() as AccountRejected).status)
            sync.deactivate();assertNull(scope.active.value)
        } finally {
            scope.deactivate();collection?.database?.close()
            collection?.directory?.parentFile?.canonicalFile?.let{target->
                check(target.parentFile==File(context.noBackupFilesDir,"team-libraries").canonicalFile)
                target.deleteRecursively()
            }
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS)}
            check(directory.canonicalFile.parentFile==File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
            directory.deleteRecursively()
        }
    }
    @Test fun encryptedSessionRetainsEveryNonJoinedStateAcrossColdReadAndOfflineVerification() {
        val directory=Files.createTempDirectory("membership-vault-").toFile()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val file=File(directory,"session.enc");val vault=AccountVault(file){key};val device=UUID.randomUUID().toString()
        val now=System.currentTimeMillis()/1000
        val memory=VerifiedSessionMemory(vault,TEAM_SERVICE_URL,device){now}
        try {
            for(state in listOf("unjoined","pending","rejected")) {
                val p=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                    "17弦乐团","new-player","新成员","member",false,state,true)
                memory.save(memory.confirm(TeamSession(TEAM_SERVICE_URL,"s".repeat(43),now+OFFLINE_SECONDS,p)))
                val restored=memory.read()!!
                assertEquals(state,restored.principal.membership);assertFalse(restored.principal.joined)
                val offline=memory.verify(restored){throw java.io.IOException("offline")}
                assertTrue(offline.offline);assertEquals(state,offline.principal.membership)
                assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("new-player"))
            }
        } finally {vault.clear();check(directory.delete())}
    }
    @Test fun oldPrincipalRemainsJoinedButUnknownOrAdminPendingPrincipalFailsClosed() {
        val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            "17弦乐团","violinist","Violinist","member",true)
        val legacy=principal.json();assertFalse(legacy.has("membership"))
        assertTrue(TeamPrincipal.parse(legacy).joined);assertFalse(TeamPrincipal.parse(legacy).membershipSupported)
        assertTrue(runCatching{TeamPrincipal.parse(legacy.put("membership","unknown"))}.isFailure)
        assertTrue(runCatching{TeamPrincipal.parse(legacy.put("membership","pending").put("role","admin"))}.isFailure)
        assertFalse(TeamPrincipal.parse(principal.copy(membership="pending").json()).joined)
    }
}
